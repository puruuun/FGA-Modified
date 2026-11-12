import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    id("kotlin-parcelize")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
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

/*
 * MIT requires the copyright and licence text to ship with every copy, so the root LICENSE is
 * copied into the APK assets at build time (never by hand) and shown by the licence screen.
 */
abstract class CopyLicenseAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(licenseFile)
            into(outputDir)
        }
    }
}

val copyLicenseAsset = tasks.register<CopyLicenseAsset>("copyLicenseAsset") {
    description = "Copies the repository LICENSE into the generated APK assets."
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    outputDir.set(layout.buildDirectory.dir("generated/licenseAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLicenseAsset,
            CopyLicenseAsset::outputDir,
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

/*
 * Release signing (issue #128). AGP debug-signs the ci and beta APKs; the finalizer task below
 * re-signs them in place with APK Signature Scheme v3 key rotation: the public debug key stays the
 * original signer and the owner's private key is the rotated one. See
 * docs/agents/releases-and-versioning.md ("Release signing").
 *
 * The key is only looked up when the task runs, so unit tests and other builds never need it.
 * Passwords reach apksigner through environment variables of the child process only.
 */
abstract class SignRotatedApk : DefaultTask() {
    @get:Internal
    abstract val apk: RegularFileProperty

    /** Debug signing only: leaves the AGP output alone and logs that it is for testing. */
    @get:Input
    abstract val debugOnly: Property<Boolean>

    @get:Internal
    abstract val debugKeystore: RegularFileProperty

    @get:Internal
    abstract val lineage: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val propertiesPathOverride: Property<String>

    @get:Input
    abstract val userHome: Property<String>

    @get:Input
    abstract val buildToolsVersion: Property<String>

    @get:Internal
    abstract val sdkDirectory: DirectoryProperty

    init {
        // The task rewrites AGP's output in place, so it must run every time it is requested.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun sign() {
        val apkFile = apk.get().asFile
        if (debugOnly.get()) {
            logger.lifecycle(
                "NOTE: ${apkFile.name} is signed with the public debug key only (CI or -PfgaDebugSigning=true). " +
                    "For testing; it cannot update a release-signed install.",
            )
            return
        }
        if (!apkFile.isFile) throw GradleException("APK to sign is missing: ${apkFile.path}")
        try {
            signRotated(apkFile)
        } catch (e: Exception) {
            // Never leave the debug-signed APK at the release path, where it could be shipped by mistake.
            apkFile.delete()
            throw e
        }
    }

    private fun signRotated(apkFile: File) {
        val guide = "docs/agents/releases-and-versioning.md (Release signing)"

        val propsFile = File(
            propertiesPathOverride.orNull ?: "${userHome.get()}/.fga-signing/signing.properties",
        )
        if (!propsFile.isFile) {
            throw GradleException(
                "Release signing properties file not found: ${propsFile.path}. " +
                    "Create it or set FGA_SIGNING_PROPERTIES, or pass -PfgaDebugSigning=true for a test build. See $guide.",
            )
        }
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        fun prop(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("Key '$key' is missing in ${propsFile.path}. See $guide.")
        val storeFile = File(prop("storeFile"))
        if (!storeFile.isFile) {
            throw GradleException("Release keystore not found: ${storeFile.path} (storeFile in ${propsFile.path}). See $guide.")
        }
        val lineageFile = lineage.get().asFile
        if (!lineageFile.isFile) {
            throw GradleException("Key rotation lineage not found: ${lineageFile.path}. See app/signing/README.md and $guide.")
        }
        val debugStore = debugKeystore.get().asFile
        val jar = File(sdkDirectory.get().asFile, "build-tools/${buildToolsVersion.get()}/lib/apksigner.jar")
        if (!jar.isFile) throw GradleException("apksigner.jar not found: ${jar.path}. Install Android build-tools ${buildToolsVersion.get()}.")

        val signed = File(apkFile.parentFile, apkFile.name + ".rotated")
        val javaExe = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(
            javaExe, "-jar", jar.path, "sign",
            "--lineage", lineageFile.path,
            "--rotation-min-sdk-version", "28",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--in", apkFile.path,
            "--out", signed.path,
            "--ks", debugStore.path, "--ks-key-alias", "fgadebug",
            "--ks-pass", "env:FGA_OLD_STORE_PASS", "--key-pass", "env:FGA_OLD_KEY_PASS",
            "--next-signer",
            "--ks", storeFile.path, "--ks-key-alias", prop("keyAlias"),
            "--ks-pass", "env:FGA_NEW_STORE_PASS", "--key-pass", "env:FGA_NEW_KEY_PASS",
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().apply {
            put("FGA_OLD_STORE_PASS", "123abc")
            put("FGA_OLD_KEY_PASS", "123abc")
            put("FGA_NEW_STORE_PASS", prop("storePassword"))
            put("FGA_NEW_KEY_PASS", prop("keyPassword"))
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            signed.delete()
            throw GradleException("apksigner failed for ${apkFile.name}:\n$output")
        }
        Files.move(signed.toPath(), apkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Signed ${apkFile.name} with the rotated release key (lineage ${lineageFile.name}).")
    }
}

val debugSigningOnly: Provider<Boolean> = providers.environmentVariable("CI").map { it == "true" }
    .orElse(false)
    .zip(providers.gradleProperty("fgaDebugSigning").map { it == "true" }.orElse(false)) { a, b -> a || b }

listOf("ci", "beta").forEach { buildType ->
    val signTask = tasks.register<SignRotatedApk>("sign${buildType.replaceFirstChar { it.uppercase() }}Rotated") {
        description = "Re-signs the $buildType APK with the rotated release key (or logs debug-only signing)."
        apk.set(layout.buildDirectory.file("outputs/apk/$buildType/app-$buildType.apk"))
        debugOnly.set(debugSigningOnly)
        debugKeystore.set(layout.projectDirectory.file("fgadebug.keystore"))
        lineage.set(layout.projectDirectory.file("signing/fga-lineage.bin"))
        propertiesPathOverride.set(providers.environmentVariable("FGA_SIGNING_PROPERTIES"))
        userHome.set(providers.systemProperty("user.home"))
        buildToolsVersion.set(provider { android.buildToolsVersion })
        sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
    }
    tasks.matching { it.name == "assemble${buildType.replaceFirstChar { c -> c.uppercase() }}" }
        .configureEach { finalizedBy(signTask) }
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
        aidl = true
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

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest to render the real screens.
            isIncludeAndroidResources = true
        }
    }

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

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.core.remoteviews)
    implementation(libs.androidx.work.runtime)

    // Chaldea sync: Shizuku API and provider (the provider is disabled in the manifest until the switch is on).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(platform(libs.junit.bom)) {
        because("kotlin-test comes with conflicting junit versions")
    }
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.willowtreeapps.assertk)

    // Screenshot tests (Roborazzi on Robolectric). Test-only; they run on JUnit 4 through the vintage engine.
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.glance.appwidget.testing)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Robolectric's SDK 36 environment reaches into JDK internals.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")

    val supportAssets = layout.projectDirectory.dir("src/main/assets/Support")

    /*
     * Assets are not on the unit test classpath, and relying on the working directory breaks
     * as soon as the test is run from the IDE instead of Gradle.
     */
    systemProperty("fga.supportAssets", supportAssets.asFile.path)

    // The bundled LICENSE is a generated asset, so tests read the copy the build produced.
    dependsOn(copyLicenseAsset)
    systemProperty(
        "fga.licenseAsset",
        layout.buildDirectory.file("generated/licenseAssets/LICENSE").get().asFile.path,
    )

    /*
     * Triggers the task when the support assets change, so that the system property above is always
     * up to date.
     */
    inputs.dir(supportAssets).withPathSensitivity(PathSensitivity.RELATIVE)
}

/*
 * Screenshot tests render the real screens under Robolectric, which costs seconds per image, and
 * their images are for the owner's review, not a CI gate. The Roborazzi record task drives
 * `testCiUnitTest` itself, so the tests are filtered out of that task unless a Roborazzi task was
 * asked for: `:app:testCiUnitTest` (Main CI) skips them, `:app:recordRoborazziCi` runs them.
 */
val screenshotRunRequested = gradle.startParameter.taskNames.any { it.contains("Roborazzi", ignoreCase = true) }
if (!screenshotRunRequested) {
    tasks.withType<Test>().configureEach {
        filter.excludeTestsMatching("*ScreenshotTest")
    }
}
