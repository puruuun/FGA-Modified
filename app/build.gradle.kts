import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    id("kotlin-parcelize")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * OpenCV is pinned to 4.11.0 because every later release bundles a KleidiCV that runs
 * SVE instructions, and BlueStacks Air on macOS advertises SVE2 in HWCAP2 without
 * implementing SVE, so those builds die with SIGILL. Do not bump `opencv_version`
 * until that is fixed upstream.
 *
 * The 4.11.0 AAR ships a 4 KB aligned libc++_shared.so, which Play's 16 KB page size
 * requirement rejects, so the 16 KB aligned copy from a current OpenCV release is
 * packaged in its place. libc++_shared.so only ever gains symbols, so the newer one
 * still satisfies the older libopencv_java4.so.
 */
val alignedLibCxx = configurations.create("alignedLibCxx") {
    isTransitive = false
}

abstract class ExtractAlignedLibCxx : DefaultTask() {
    @get:InputFiles
    abstract val aar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        files.sync {
            from(archives.zipTree(aar.singleFile)) {
                include("jni/*/libc++_shared.so")
                // jniLibs source dirs are laid out as <abi>/<lib>, the AAR as jni/<abi>/<lib>.
                eachFile { path = path.removePrefix("jni/") }
            }
            includeEmptyDirs = false
            into(outputDir)
        }

        /*
         * On an empty extraction the packaging step falls back to OpenCV's own 4 KB aligned
         * copy and the build still succeeds, so Play would be the first thing to complain.
         */
        if (!outputDir.get().file("arm64-v8a/libc++_shared.so").asFile.exists()) {
            throw GradleException(
                "No arm64-v8a/libc++_shared.so in ${aar.singleFile.name} — its layout changed.",
            )
        }
    }
}

val extractAlignedLibCxx = tasks.register<ExtractAlignedLibCxx>("extractAlignedLibCxx") {
    description = "Extracts the 16 KB aligned libc++_shared.so packaged in place of OpenCV's."
    aar.from(alignedLibCxx)
    outputDir.set(layout.buildDirectory.dir("alignedLibCxx"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractAlignedLibCxx,
            ExtractAlignedLibCxx::outputDir,
        )
    }
}

// Release versions come from version.properties. FGA_VERSION_NAME / FGA_VERSION_CODE override them
// (PR builds set them to run_number). A malformed file fails the build here, not in CI.
val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (!file.isFile) throw GradleException("version.properties is missing at ${file.path}")
    file.inputStream().use { load(it) }
}

fun versionProperty(key: String): String =
    versionProperties.getProperty(key)?.trim() ?: throw GradleException("version.properties has no $key")

val stableVersionName = versionProperty("stable.versionName").also {
    if (!Regex("""\d+\.\d+\.\d+""").matches(it)) throw GradleException("stable.versionName must be X.Y.Z, got $it")
}
val stableVersionCode = versionProperty("stable.versionCode").toInt()
val betaVersionName = versionProperty("beta.versionName").also {
    if (!Regex("""\d+\.\d+\.\d+-beta\.\d+""").matches(it)) throw GradleException("beta.versionName must be X.Y.Z-beta.N, got $it")
}
val betaVersionCode = versionProperty("beta.versionCode").toInt().also { code ->
    val (major, minor, patch, beta) = Regex("""(\d+)\.(\d+)\.(\d+)-beta\.(\d+)""").find(betaVersionName)!!.destructured
    val expected = major.toInt() * 1_000_000 + minor.toInt() * 1000 + patch.toInt() * 100 + beta.toInt()
    if (code != expected) throw GradleException("beta.versionCode $code does not match $betaVersionName (expected $expected)")
}
val envVersionName: String? = System.getenv("FGA_VERSION_NAME")
val envVersionCode: Int? = System.getenv("FGA_VERSION_CODE")?.toInt()

// AGP has no per-build-type version fields, so the variant outputs get the value for their build type.
androidComponents {
    onVariants { variant ->
        val beta = variant.buildType == "beta" || variant.buildType == "deviceTest"
        val name = envVersionName ?: if (beta) betaVersionName else stableVersionName
        val code = envVersionCode ?: if (beta) betaVersionCode else stableVersionCode
        variant.outputs.forEach { output ->
            output.versionName.set(name)
            output.versionCode.set(code)
        }
    }
}

android {
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "io.github.fate_grand_automata"
        minSdk = 24
        targetSdk = 36
        versionCode = envVersionCode ?: stableVersionCode
        versionName = envVersionName ?: stableVersionName
        testInstrumentationRunner = "io.github.fate_grand_automata.qa.SupportDeviceTestRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("fgadebug.keystore")
            storePassword = "123abc"
            keyAlias = "fgadebug"
            keyPassword = "123abc"
        }
        create("release") {
            storeFile = file("fgautomata.keystore")
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "fgautomata"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".test"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            // TODO test app extensively before enabling
            // isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        create("ci") {
            initWith(getByName("release"))
            // Keep the modified app separate from all upstream variants, preserving its update identity.
            applicationIdSuffix = ".cardcount"
            signingConfig = signingConfigs.getByName("debug")

            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters.add("armeabi-v7a")
                abiFilters.add("arm64-v8a")
            }
        }
        create("beta") {
            initWith(getByName("ci"))
            applicationIdSuffix = ".beta"
            matchingFallbacks += listOf("ci", "release")
        }
        // Isolated, non-minified target for signed on-device reader tests. No product UI or permissions.
        create("deviceTest") {
            initWith(getByName("beta"))
            applicationIdSuffix = ".qa"
            isMinifyEnabled = false
            matchingFallbacks += listOf("beta", "ci", "release")
        }
    }
    lint {
        abortOnError = false
        disable += "MissingTranslation"
    }
    // run tests in CI builds instad of debug
    testBuildType = if (providers.gradleProperty("fgaDeviceTests").orNull == "true") "deviceTest" else "ci"

    packaging {
        jniLibs {
            // Project-local jniLibs are merged ahead of any dependency's, so ours wins.
            pickFirsts += "**/libc++_shared.so"
        }
    }

    namespace = "io.github.fate_grand_automata"
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        optIn.add("androidx.compose.ui.ExperimentalComposeUiApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        optIn.add("androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi")
    }
}

dependencies {
    implementation(project(":libautomata"))
    implementation(project(":scripts"))
    implementation(project(":prefs"))


    implementation(libs.kotlin.stdlib)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.opencv)
    alignedLibCxx("${libs.opencv.aligned.libcxx.get()}@aar")
    implementation(libs.tesseract4android)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.viewmodel.compose)


    implementation(libs.kotlinx.serialization.json)

    implementation(libs.dagger.hilt.android)
    ksp(libs.dagger.hilt.compiler)

    implementation(libs.timber)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)



    implementation(libs.accompanist.permissions)

    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.network)

    implementation(libs.reorderable)

    testImplementation(platform(libs.junit.bom)) {
        because("kotlin-test comes with conflicting junit versions")
    }
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.willowtreeapps.assertk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    val supportAssets = layout.projectDirectory.dir("src/main/assets/Support")

    /*
     * Assets are not on the unit test classpath, and relying on the working directory breaks
     * as soon as the test is run from the IDE instead of Gradle.
     */
    systemProperty("fga.supportAssets", supportAssets.asFile.path)

    /*
     * Triggers the task when the support assets change, so that the system property above is always
     * up to date.
     */
    inputs.dir(supportAssets).withPathSensitivity(PathSensitivity.RELATIVE)
}
