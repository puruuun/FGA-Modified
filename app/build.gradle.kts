import com.android.build.api.artifact.SingleArtifact
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
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

/*
 * Rust core (designs 0025 and 0026, docs/agents/rust-native.md). Which variants carry the Rust
 * engine (libfga_ffi.so for the shipped ABIs, the OCR model and the third-party notices) is decided
 * once, by carriesEngine; the packaged files, BuildConfig.NATIVE_READER, the keep rules and the
 * checks below all read it, so they cannot disagree.
 *
 * Design 0026, D-6: beta and rust carry it by default; debug, ci, deviceTest and release do not,
 * so a Kotlin-only build (Main CI's assembleCi and unit tests) needs no Rust tools and has no Rust
 * task in its task graph. The stable APK is the ci build type built with `-Pfga.native=true`, which
 * adds the engine to every variant (also the device-test and probe builds of rust-native.md); the
 * release procedure checks the staged file with tools/ci/apk_check.py, since nothing in the build
 * can tell a stable build from Main CI's. release is upstream's Play build type, which this fork
 * does not ship, so it has no default of its own. `-Pfga.native=false` takes the engine out of
 * every variant, the emergency switch of the design's risk table. D-7: a carrying variant fails
 * when a Rust tool is missing; it never silently packages no engine.
 */
val nativeSwitch: Boolean? = when (val raw = providers.gradleProperty("fga.native").orNull) {
    null -> null
    "true" -> true
    "false" -> false
    else -> throw GradleException("-Pfga.native must be true or false, got '$raw'")
}
val engineBuildTypes = setOf("beta", "rust")
fun carriesEngine(buildType: String?): Boolean = nativeSwitch ?: (buildType in engineBuildTypes)
// Some variant may carry the engine, so the Rust tasks are registered. Registering runs nothing:
// only a carrying variant's tasks depend on them.
val engineRegistered = nativeSwitch != false
val shippedAbis = listOf("armeabi-v7a", "arm64-v8a")
// Issue #535, see the ONNX Runtime block below.
val nativeOrtEnabled = providers.gradleProperty("fga.native.ort").orNull == "true"
if (nativeOrtEnabled && nativeSwitch != true) {
    throw GradleException("-Pfga.native.ort=true only works together with -Pfga.native=true")
}
// Issue #549: tract's multi-threaded kernels for the tract probes (fga-ffi's tract-threads
// feature), a measurement switch for device-test builds. Off, the cargo-ndk commands are the
// ones without it, so the library is the one built before the switch existed.
val nativeThreadsEnabled = providers.gradleProperty("fga.native.threads").orNull == "true"
if (nativeThreadsEnabled && nativeSwitch != true) {
    throw GradleException("-Pfga.native.threads=true only works together with -Pfga.native=true")
}
/** `--features` arguments for fga-ffi; empty when no switch is on, so the command stays as it was. */
fun cargoFeatures(vararg features: String): List<String> {
    val wanted = features.toList() + if (nativeThreadsEnabled) listOf("tract-threads") else emptyList()
    return if (wanted.isEmpty()) emptyList() else listOf("--features", wanted.joinToString(","))
}

/** rustup installs into ~/.cargo/bin, which is often not on the PATH of a Gradle daemon started by Android Studio. */
fun cargoExecutable(): String {
    val homeCargo = File(System.getProperty("user.home"), ".cargo/bin/" + if (File.separatorChar == '\\') "cargo.exe" else "cargo")
    return if (homeCargo.isFile) homeCargo.path else "cargo"
}

/*
 * One cargo-ndk run. Everything that decides the libraries is an input: the sources, the ABIs, the
 * platform level, the cargo arguments and environment, and the NDK, named by its directory and
 * the revision in its source.properties, so a changed NDK builds again. The NDK is resolved when
 * the task runs (ANDROID_NDK_HOME, else the newest NDK under the SDK's ndk/ folder, the order of
 * cargo-ndk itself) and handed to cargo-ndk as ANDROID_NDK_HOME, so the NDK in the inputs is the
 * one that built the output. The output folder is cleared first and must then hold exactly one
 * libfga_ffi.so per ABI: a library of an earlier build or of another ABI is never packaged.
 */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Input
    abstract val cargo: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val platform: Property<Int>

    @get:Input
    abstract val cargoArguments: ListProperty<String>

    @get:Input
    abstract val extraEnvironment: MapProperty<String, String>

    @get:Input
    @get:Optional
    abstract val ndkHome: Property<String>

    @get:Input
    abstract val sdkDirectory: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @get:Input
    val ndk: String
        get() {
            val dir = resolveNdk()
            return "${dir.path} ${NdkLocator.revision(dir)}"
        }

    private fun resolveNdk(): File {
        ndkHome.orNull?.let { explicit ->
            val dir = File(explicit)
            if (!File(dir, "source.properties").isFile) {
                throw GradleException("ANDROID_NDK_HOME is $explicit, which holds no NDK (no source.properties)")
            }
            return dir
        }
        val root = File(sdkDirectory.get(), "ndk")
        return NdkLocator.newest(root)
            ?: throw GradleException("No NDK: ANDROID_NDK_HOME is not set and ${root.path} holds none")
    }

    init {
        // Gradle does not count a file added to an output folder as a change, so without this a
        // stray library left there by hand or by another build would keep the task up to date and
        // be packaged.
        outputs.upToDateWhen { (it as CargoNdkBuild).unexpectedOutput() == null }
    }

    private fun unexpectedOutput(): String? {
        val out = outputDir.get().asFile
        val found = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }.sorted().toList()
        val expected = abis.get().map { "$it/libfga_ffi.so" }.sorted()
        return if (found == expected) null else "cargo-ndk left $found in ${out.path}; expected exactly $expected"
    }

    @TaskAction
    fun build() {
        val ndkDir = resolveNdk()
        val out = outputDir.get().asFile
        files.delete { delete(out) }
        out.mkdirs()
        val wanted = abis.get()
        logger.info("cargo-ndk for $wanted with ANDROID_NDK_HOME=${ndkDir.path}")
        execOperations.exec {
            workingDir = workspace.get().asFile
            executable = cargo.get()
            environment(extraEnvironment.get())
            environment("ANDROID_NDK_HOME", ndkDir.path)
            args(
                listOf("ndk") + wanted.flatMap { listOf("-t", it) } +
                    listOf("--platform", platform.get().toString(), "-o", out.path) + cargoArguments.get(),
            )
        }
        unexpectedOutput()?.let { throw GradleException(it) }
    }
}

/** The settings that every cargo-ndk task shares; the NDK comes from the environment as checkRustTools reads it. */
fun CargoNdkBuild.configureCargoNdk(abiList: List<String>, features: List<String>) {
    dependsOn("checkRustTools")
    val native = rootProject.layout.projectDirectory.dir("native")
    workspace.set(native)
    nativeSources.from(fileTree(native) { exclude("target/**") })
    cargo.set(cargoExecutable())
    abis.set(abiList)
    // minSdk, so the library links against no newer libc than the app supports.
    platform.set(24)
    cargoArguments.set(listOf("build", "--release", "-p", "fga-ffi") + features)
    ndkHome.set(providers.environmentVariable("ANDROID_NDK_HOME"))
    sdkDirectory.set(
        providers.environmentVariable("ANDROID_HOME")
            .orElse(androidComponents.sdkComponents.sdkDirectory.map { it.asFile.path }),
    )
}

/*
 * D-7: every variant that carries the engine runs this before it compiles anything (its
 * pre<Variant>Build depends on it), so a missing tool stops the build at once, naming the tool and
 * the opt-out, instead of deep inside the cargo build.
 */
abstract class CheckRustTools : DefaultTask() {
    @get:Input
    abstract val cargo: Property<String>

    @get:Input
    @get:Optional
    abstract val ndkHome: Property<String>

    @get:Input
    abstract val sdkDirectory: Property<String>

    @get:Input
    abstract val pinnedNdk: Property<String>

    @get:Input
    abstract val allowOtherNdk: Property<Boolean>

    init {
        // Tools are installed and removed outside Gradle's view, so the answer is never reused.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val optOut = "This build type packages the Rust engine (design 0026). Install it, or build without the " +
            "engine with -Pfga.native=false (docs/agents/rust-native.md, \"Which builds carry the engine\")."
        val named = File(cargo.get())
        val cargoExe = if (named.isAbsolute) named.takeIf { it.isFile } else System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .flatMap { dir -> listOf(File(dir, "cargo"), File(dir, "cargo.exe")) }
            .firstOrNull { it.isFile }
        if (cargoExe == null) throw GradleException("cargo was not found (not in ~/.cargo/bin, not on PATH). $optOut")
        val (exit, output) = try {
            val process = ProcessBuilder(cargoExe.path, "ndk", "--version").redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor() to text
        } catch (e: java.io.IOException) {
            throw GradleException("cargo-ndk could not be started through ${cargoExe.path}: ${e.message}. $optOut")
        }
        if (exit != 0) {
            throw GradleException("cargo-ndk is not installed (`cargo ndk --version`: ${output.trim().take(300)}). $optOut")
        }
        // cargo-ndk takes ANDROID_NDK_HOME, else an NDK under the SDK's ndk/ folder; cargoNdkBuild
        // resolves it the same way (NdkLocator).
        val explicit = ndkHome.orNull
        val ndkDir = if (explicit != null) {
            File(explicit).also {
                if (!File(it, "source.properties").isFile) {
                    throw GradleException("ANDROID_NDK_HOME is $explicit, which holds no NDK (no source.properties). $optOut")
                }
            }
        } else {
            val ndkRoot = File(sdkDirectory.get(), "ndk")
            NdkLocator.newest(ndkRoot) ?: throw GradleException(
                "The Android NDK is missing: ANDROID_NDK_HOME is not set and ${ndkRoot.path} holds no NDK " +
                    "(install NDK ${pinnedNdk.get()}, the version native/ndk-version.txt names). $optOut",
            )
        }
        // The libraries of a local build must come from the NDK that CI checks, or a local APK is not
        // the one CI proved.
        val revision = NdkLocator.revision(ndkDir)
        if (revision != pinnedNdk.get() && !allowOtherNdk.get()) {
            throw GradleException(
                "The NDK at ${ndkDir.path} is $revision, but native/ndk-version.txt pins ${pinnedNdk.get()}. " +
                    "Set ANDROID_NDK_HOME to NDK ${pinnedNdk.get()}, or pass -Pfga.native.otherNdk=true to try " +
                    "this NDK on purpose (never for a release build).",
            )
        }
    }
}

/** How cargo-ndk picks an NDK when ANDROID_NDK_HOME is not set: the newest version under the SDK's ndk/. */
object NdkLocator {
    fun newest(root: File): File? {
        fun version(dir: File) = dir.name.split('.').map { it.toIntOrNull() ?: -1 }
        val comparator = Comparator<File> { a, b ->
            val va = version(a)
            val vb = version(b)
            (0 until maxOf(va.size, vb.size)).map { (va.getOrNull(it) ?: -1).compareTo(vb.getOrNull(it) ?: -1) }
                .firstOrNull { it != 0 } ?: 0
        }
        return root.listFiles().orEmpty().filter { File(it, "source.properties").isFile }.maxWithOrNull(comparator)
    }

    fun revision(dir: File): String =
        Properties().apply { File(dir, "source.properties").inputStream().use { load(it) } }
            .getProperty("Pkg.Revision")?.trim()
            ?: throw GradleException("${dir.path}/source.properties has no Pkg.Revision")
}

if (engineRegistered) {
    val checkRustTools = tasks.register<CheckRustTools>("checkRustTools") {
        description = "Fails early when cargo, cargo-ndk or the NDK that the Rust engine needs is missing."
        cargo.set(cargoExecutable())
        ndkHome.set(providers.environmentVariable("ANDROID_NDK_HOME"))
        sdkDirectory.set(
            providers.environmentVariable("ANDROID_HOME")
                .orElse(androidComponents.sdkComponents.sdkDirectory.map { it.asFile.path }),
        )
        pinnedNdk.set(
            providers.fileContents(rootProject.layout.projectDirectory.file("native/ndk-version.txt")).asText.map { text ->
                text.lines().map { it.trim() }.single { it.isNotEmpty() && !it.startsWith("#") }
            },
        )
        allowOtherNdk.set(providers.gradleProperty("fga.native.otherNdk").map { it == "true" }.orElse(false))
    }

    val cargoNdkBuild = tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        description = "Builds native/ with cargo-ndk for the shipped ABIs."
        configureCargoNdk(shippedAbis, cargoFeatures())
        outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
    }

    androidComponents {
        onVariants { variant ->
            if (carriesEngine(variant.buildType)) {
                // With ONNX Runtime the per-ABI builds below provide libfga_ffi.so instead.
                if (!nativeOrtEnabled) {
                    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
                }
                val preBuild = "pre${variant.name.replaceFirstChar { it.uppercase() }}Build"
                tasks.matching { it.name == preBuild }.configureEach { dependsOn(checkRustTools) }
            }
        }
    }
}

/*
 * Issue #535: `-Pfga.native.ort=true` (with `fga.native`) adds the ONNX Runtime probes of
 * fga-ffi, so the device suite can measure ONNX Runtime next to tract. fga-ffi links the shared
 * libonnxruntime.so from Microsoft's onnxruntime-android AAR, so that library is extracted for
 * the linker and packaged next to libfga_ffi.so. Off, none of this is registered or resolved.
 */
abstract class ExtractOnnxRuntime : DefaultTask() {
    @get:InputFiles
    abstract val aar: ConfigurableFileCollection

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        // Gradle does not check downloads against a checksum unless dependency verification is
        // set up, which this project does not use, so the binary that gets linked is pinned here.
        val digest = MessageDigest.getInstance("SHA-256")
        aar.singleFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            throw GradleException("${aar.singleFile.name} has SHA-256 $actual, expected ${sha256.get()}")
        }
        val wanted = abis.get()
        files.sync {
            from(archives.zipTree(aar.singleFile)) {
                include(wanted.map { "jni/$it/libonnxruntime.so" })
                eachFile { path = path.removePrefix("jni/") }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        // A missing ABI would otherwise surface as a link error deep inside the cargo build.
        val missing = wanted.filterNot { outputDir.get().file("$it/libonnxruntime.so").asFile.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("${aar.singleFile.name} has no libonnxruntime.so for $missing")
        }
    }
}

if (nativeOrtEnabled) {
    val onnxRuntimeAar = configurations.create("onnxRuntimeAar") {
        isTransitive = false
    }
    dependencies {
        onnxRuntimeAar("${libs.onnxruntime.android.get()}@aar")
    }
    val extractOnnxRuntime = tasks.register<ExtractOnnxRuntime>("extractOnnxRuntime") {
        description = "Extracts libonnxruntime.so from the onnxruntime-android AAR for fga-ffi."
        // A build that is going to stop for a missing Rust tool must not download the AAR first.
        dependsOn("checkRustTools")
        aar.from(onnxRuntimeAar)
        abis.set(shippedAbis)
        // onnxruntime-android 1.31.0; its SHA-1 equals Maven Central's published .sha1. Version
        // and SHA-256 are pinned in three places that change together: gradle/libs.versions.toml,
        // here, and .github/workflows/native-ci.yml.
        sha256.set("72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7")
        // ORT_LIB_PATH below names the same folder.
        outputDir.set(layout.buildDirectory.dir("onnxruntime/jniLibs"))
    }

    /*
     * ort-sys takes one ORT_LIB_PATH per build, and each ABI needs its own libonnxruntime.so, so
     * each ABI gets its own cargo-ndk run. They write next to, not into, the default build's
     * output, so switching the property off again never packages an ONNX Runtime build.
     */
    val ortBuilds = shippedAbis.map { abi ->
        val taskSuffix = abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
        tasks.register<CargoNdkBuild>("cargoNdkBuildOrt$taskSuffix") {
            description = "Builds native/ with the ort-probe feature for $abi."
            configureCargoNdk(listOf(abi), cargoFeatures("ort-probe"))
            outputDir.set(layout.buildDirectory.dir("generated/rustJniLibsOrt/$abi"))
            val runtimeDir = extractOnnxRuntime.flatMap { it.outputDir.dir(abi) }
            inputs.dir(runtimeDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("onnxRuntime")
            // ort-sys's documented way to link a supplied runtime: the folder that holds it, and
            // dynamic linking, since the AAR has only the shared library.
            extraEnvironment.put("ORT_PREFER_DYNAMIC_LINK", "1")
            extraEnvironment.put("ORT_LIB_PATH", runtimeDir.map { it.asFile.path })
        }
    }

    androidComponents {
        // fga.ort needs -Pfga.native=true, under which every variant carries the engine.
        onVariants { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(extractOnnxRuntime, ExtractOnnxRuntime::outputDir)
            ortBuilds.forEach { variant.sources.jniLibs?.addGeneratedSourceDirectory(it, CargoNdkBuild::outputDir) }
        }
    }
}

/*
 * Issue #568: the PP-OCRv6 recogniser model for the Rust support-score engine. It is pinned in
 * native/ocr-model/model.properties (file name, URL, size, SHA-256) and never committed. Every
 * variant that carries the engine (carriesEngine) packages the model under assets/ocr/, with the
 * pin file and the licence notice beside it. The model comes from
 * `-Pfga.ocr.model=<path>` or, without it, from the pinned URL; either way a file whose size or
 * SHA-256 differs stops the build, and nothing that failed the check is left where a later build
 * could pick it up. With -Pfga.native=false, none of this is registered.
 */
abstract class ProvideOcrModel : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticeFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localModel: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Downloads kept by SHA-256; not an input, since the pin decides what the output must be. */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun provide() {
        val pin = Properties().apply { pinFile.get().asFile.inputStream().use { load(it) } }
        fun pinned(key: String) = pin.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("${pinFile.get().asFile.name} has no $key")
        val name = pinned("file")
        if (!Regex("""[A-Za-z0-9._-]+\.onnx""").matches(name)) throw GradleException("model file name '$name' is not a plain .onnx name")
        val sha256 = pinned("sha256").lowercase()
        if (!Regex("[0-9a-f]{64}").matches(sha256)) throw GradleException("sha256 '$sha256' is not 64 hex digits")
        val size = pinned("size").toLongOrNull()?.takeIf { it > 0 } ?: throw GradleException("size '${pin.getProperty("size")}' is not a positive number")
        val url = pinned("url")

        val root = outputDir.get().asFile
        // Whatever an earlier run left, a model that passed or a stray part file, is never reused.
        root.deleteRecursively()
        val dir = File(root, "ocr").apply { mkdirs() }
        val part = File(dir, "$name.part")
        try {
            val local = localModel.orNull?.asFile
            if (local != null) {
                logger.lifecycle("OCR model: checking $local")
                Files.copy(local.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else if (!fromCache(sha256, name, part, size)) {
                logger.lifecycle("OCR model: downloading $url")
                download(url, part, size)
                check(part, size, sha256)
                toCache(sha256, name, part)
            }
            check(part, size, sha256)
            Files.move(part.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        Files.copy(pinFile.get().asFile.toPath(), File(dir, "ocr-model.properties").toPath())
        Files.copy(noticeFile.get().asFile.toPath(), File(dir, name.removeSuffix(".onnx") + ".NOTICE.txt").toPath())
    }

    /*
     * A download is kept in the Gradle user home under its SHA-256, so a clean build or another
     * worktree on the same PC does not download it again. A cached copy is checked like a download
     * and, if it fails, deleted and downloaded again.
     */
    private fun cached(sha256: String, name: String): File? = cacheDir.orNull?.asFile?.let { File(it, "$sha256/$name") }

    private fun fromCache(sha256: String, name: String, target: File, size: Long): Boolean {
        val file = cached(sha256, name)?.takeIf { it.isFile } ?: return false
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return try {
            check(target, size, sha256)
            logger.lifecycle("OCR model: from the local cache $file")
            true
        } catch (e: GradleException) {
            logger.warn("OCR model: the cached copy $file failed its check (${e.message}); downloading again")
            file.delete()
            target.delete()
            false
        }
    }

    private fun toCache(sha256: String, name: String, checked: File) {
        val file = cached(sha256, name) ?: return
        try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "$name.${ProcessHandle.current().pid()}.part")
            Files.copy(checked.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            // The build has its checked model; only the next build loses the shortcut.
            logger.warn("OCR model: could not keep a copy in $file: ${e.message}")
        }
    }

    /** Only HTTPS, redirects included: the hash protects the content, not the request. */
    private fun httpsUri(text: String, what: String): URI {
        val uri = try { URI(text) } catch (e: java.net.URISyntaxException) { throw GradleException("$what '$text' is not a URL: ${e.message}") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) {
            throw GradleException("$what '$text' is not an https URL; the OCR model is only downloaded over HTTPS")
        }
        return uri
    }

    private fun download(url: String, target: File, size: Long) {
        var uri = httpsUri(url, "The pinned OCR model URL")
        var redirects = 0
        while (true) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            // Every connection is closed here, also when the status or a header read throws.
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                // Redirects are followed here, one by one, so each target can be held to HTTPS.
                connection.instanceFollowRedirects = false
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw GradleException("OCR model download from $uri answered HTTP $status without a Location")
                    if (++redirects > 5) throw GradleException("OCR model download from $url was redirected more than 5 times")
                    val next = try {
                        uri.resolve(URI(location))
                    } catch (e: java.net.URISyntaxException) {
                        throw GradleException("A redirect of the OCR model download to '$location' is not a URL: ${e.message}")
                    }
                    uri = httpsUri(next.toString(), "A redirect of the OCR model download to")
                    continue
                }
                if (status != 200) throw GradleException("OCR model download from $uri answered HTTP $status")
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            // A server that sends more than the pinned size is wrong; stop before the disk fills.
                            if (total > size) throw GradleException("OCR model download from $url is longer than the pinned $size bytes")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun check(file: File, size: Long, sha256: String) {
        val actualSize = file.length()
        if (actualSize != size) throw GradleException("OCR model has $actualSize bytes, the pin says $size (truncated or another file)")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) throw GradleException("OCR model has SHA-256 $actual, the pin says $sha256")
    }
}

// A relative path is taken from the repository root, where the commands in rust-native.md run. A
// missing file is reported by Gradle's check of the task's input file (localModel).
val ocrModelPath: File? = providers.gradleProperty("fga.ocr.model").orNull?.let { rootProject.file(it) }
if (ocrModelPath != null && !engineRegistered) {
    throw GradleException("-Pfga.ocr.model has no use with -Pfga.native=false: no variant packages the model")
}

/*
 * Design 0026, D-10: the notices of the model and of every crate linked into libfga_ffi.so, kept in
 * native/THIRD_PARTY_NOTICES.txt (written and checked by tools/ci/native_notices.py). Packaged beside
 * the model's notice, in the variants that carry the engine only; the licence screen shows it when
 * the asset is there.
 */
abstract class CopyEngineNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(noticesFile)
            into(outputDir.dir("ocr"))
        }
    }
}

/*
 * Fails a carrying variant's assemble when its APK lacks a file of the engine or its BuildConfig does
 * not say NATIVE_READER = true, so the packaging and the flag are checked on what was built, not on
 * the build script's intent. Variants without the engine get no such task: their task graph stays
 * the one of a build before the engine (Main CI's apk_check.py checks the ci APK instead).
 */
abstract class CheckEngineApk : DefaultTask() {
    /** The APKs (from the APK artifact folder) or the app bundle to check. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    /** "" for an APK; "base/" for an app bundle, which keeps the base module's files under base/. */
    @get:Input
    abstract val entryPrefix: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildConfigSources: ConfigurableFileCollection

    @get:Input
    abstract val requiredEntries: ListProperty<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val found = archives.files.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".aab")) }.sortedBy { it.name }
        if (found.isEmpty()) throw GradleException("No APK or bundle in ${archives.files}; cannot check that it carries the Rust engine")
        val problems = mutableListOf<String>()
        for (archive in found) {
            val names = ZipFile(archive).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
            requiredEntries.get().map { entryPrefix.get() + it }.filterNot { it in names }.forEach { problems += "${archive.name} lacks $it" }
        }
        val buildConfig = buildConfigSources.asFileTree.matching { include("**/BuildConfig.java") }.files
        if (buildConfig.size != 1) {
            problems += "expected one generated BuildConfig.java, found ${buildConfig.size}"
        } else if (!Regex("""\bNATIVE_READER\s*=\s*true\s*;""").containsMatchIn(buildConfig.single().readText())) {
            problems += "BuildConfig.NATIVE_READER is not true in ${buildConfig.single()}"
        }
        if (problems.isNotEmpty()) {
            // A failed build leaves nothing that looks shippable.
            found.forEach { it.delete() }
            throw GradleException(
                "This variant carries the Rust engine (design 0026), but: " + problems.joinToString("; ") +
                    ". Deleted ${found.joinToString { it.path }}.",
            )
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

/*
 * JNI binds RustSensing's natives by class and method name, and R8 renames or drops whatever
 * proguard-rust.pro does not keep; a missing rule shows only on a device, as an
 * UnsatisfiedLinkError the engine turns into "unavailable". So packaging the APK or bundle of a
 * minified variant that carries the engine fails unless R8's seeds file
 * (outputs/mapping/<variant>/seeds.txt, written next to the mapping file) lists the class, INSTANCE
 * and the four natives, and the mapping file maps the class to its own name (design 0026, D-8).
 */
abstract class CheckRustKeepRules : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    // @InputFiles, not @InputFile: a missing seeds file then reaches the check below with its own
    // message instead of Gradle's. The mapping file input orders this task after R8, which writes both.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val seedsFile: RegularFileProperty

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val seeds = seedsFile.get().asFile
        if (!seeds.isFile) throw GradleException("R8 wrote no ${seeds.path}; cannot check that RustSensing keeps its native methods")
        val seedLines = seeds.readLines().map { it.trim() }.toSet()
        val cls = "io.github.fate_grand_automata.sensing.RustSensing"
        val wanted = listOf(
            cls,
            "$cls: $cls INSTANCE",
            "$cls: java.lang.String load(java.lang.String)",
            "$cls: int[] readScore(byte[],int,int)",
            "$cls: int unload()",
            "$cls: int isLoaded()",
        )
        val missing = wanted.filterNot { it in seedLines }
        val renamed = mappingFile.get().asFile.useLines { lines ->
            lines.none { it == "$cls -> $cls:" }
        }
        if (missing.isNotEmpty() || renamed) {
            throw GradleException(
                "R8 did not keep RustSensing for JNI (check app/proguard-rust.pro). " +
                    "Missing from ${seeds.path}: $missing" +
                    (if (renamed) "; ${mappingFile.get().asFile.path} does not map $cls to itself" else ""),
            )
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

// The model's file name, for the content check; ProvideOcrModel checks the pin itself.
val ocrModelFileName: String = Properties().apply {
    rootProject.file("native/ocr-model/model.properties").inputStream().use { load(it) }
}.getProperty("file")?.trim() ?: throw GradleException("native/ocr-model/model.properties has no file")
// app/engine-apk-entries.txt is the one list of what a carrying APK holds; tools/ci/apk_check.py
// present reads the same file, so the build's check and the release check cannot disagree.
val engineApkEntries: List<String> = run {
    val listFile = layout.projectDirectory.file("engine-apk-entries.txt").asFile
    val abis = mutableListOf<String>()
    val templates = mutableListOf<String>()
    listFile.readLines().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val kind = line.substringBefore(' ')
        val value = line.substringAfter(' ', "").trim()
        if (kind !in setOf("abi", "entry") || value.isEmpty() || ' ' in value) {
            throw GradleException("${listFile.path}:${index + 1} is not \"abi NAME\" or \"entry PATH\": $raw")
        }
        if (kind == "abi") abis += value else templates += value
    }
    if (abis != shippedAbis) {
        throw GradleException("The abi lines of ${listFile.path} ($abis) must equal shippedAbis ($shippedAbis)")
    }
    val stem = ocrModelFileName.removeSuffix(".onnx")
    templates.flatMap { template ->
        (if ("{abi}" in template) abis.map { it as String? } else listOf(null)).map { abi ->
            val named = template.replace("{model_stem}", stem).replace("{model}", ocrModelFileName)
            val entry = if (abi != null) named.replace("{abi}", abi) else named
            if ('{' in entry || '}' in entry) throw GradleException("Unknown placeholder in ${listFile.path}: $template")
            entry
        }
    }
}

if (engineRegistered) {
    val provideOcrModel = tasks.register<ProvideOcrModel>("provideOcrModel") {
        description = "Provides the pinned OCR model (checked by size and SHA-256) as an APK asset."
        // A build that is going to stop for a missing Rust tool must not download 21 MB first.
        dependsOn("checkRustTools")
        pinFile.set(rootProject.layout.projectDirectory.file("native/ocr-model/model.properties"))
        noticeFile.set(rootProject.layout.projectDirectory.file("native/ocr-model/NOTICE.txt"))
        if (ocrModelPath != null) localModel.set(ocrModelPath)
        cacheDir.set(File(gradle.gradleUserHomeDir, "caches/fga-ocr-model"))
        outputDir.set(layout.buildDirectory.dir("generated/ocrModelAssets"))
    }
    val copyEngineNotices = tasks.register<CopyEngineNotices>("copyEngineNotices") {
        description = "Copies native/THIRD_PARTY_NOTICES.txt into the assets of the variants that carry the engine."
        noticesFile.set(rootProject.layout.projectDirectory.file("native/THIRD_PARTY_NOTICES.txt"))
        outputDir.set(layout.buildDirectory.dir("generated/engineNoticeAssets"))
    }
    androidComponents {
        onVariants { variant ->
            if (!carriesEngine(variant.buildType)) return@onVariants
            val cap = variant.name.replaceFirstChar { it.uppercase() }
            variant.sources.assets?.addGeneratedSourceDirectory(provideOcrModel, ProvideOcrModel::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(copyEngineNotices, CopyEngineNotices::outputDir)
            // JNI finds RustSensing's native methods by name (proguard-rust.pro); a build without the
            // engine does not need the class at all, and R8 removes it there.
            variant.proguardFiles.add(layout.projectDirectory.file("proguard-rust.pro"))

            val checkApk = tasks.register<CheckEngineApk>("checkEngineApk$cap") {
                description = "Fails the ${variant.name} build when its APK lacks the Rust engine or NATIVE_READER is not true."
                archives.from(variant.artifacts.get(SingleArtifact.APK).map { dir -> dir.asFileTree.matching { include("*.apk") } })
                entryPrefix.set("")
                buildConfigSources.from(layout.buildDirectory.dir("generated/source/buildConfig/${variant.name}"))
                dependsOn("generate${cap}BuildConfig")
                requiredEntries.set(engineApkEntries)
                stamp.set(layout.buildDirectory.file("intermediates/checkEngineApk/${variant.name}/ok.txt"))
            }
            val checkBundle = tasks.register<CheckEngineApk>("checkEngineBundle$cap") {
                description = "Fails the ${variant.name} bundle when it lacks the Rust engine or NATIVE_READER is not true."
                archives.from(variant.artifacts.get(SingleArtifact.BUNDLE))
                entryPrefix.set("base/")
                buildConfigSources.from(layout.buildDirectory.dir("generated/source/buildConfig/${variant.name}"))
                dependsOn("generate${cap}BuildConfig")
                requiredEntries.set(engineApkEntries)
                stamp.set(layout.buildDirectory.file("intermediates/checkEngineBundle/${variant.name}/ok.txt"))
            }
            // The three ways to get an APK or a bundle out of a variant: assemble<V> and install<V>
            // both take the APK of package<V> (install<V> does not run assemble<V>), and bundle<V>
            // writes the bundle. sign<V>Rotated re-signs the assembled APK afterwards and checks
            // that the entries stayed the same (SignRotatedApk).
            tasks.matching { it.name == "assemble$cap" || it.name == "install$cap" }.configureEach { dependsOn(checkApk) }
            tasks.matching { it.name == "bundle$cap" }.configureEach { dependsOn(checkBundle) }

            if (variant.isMinifyEnabled) {
                val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                val checkKeep = tasks.register<CheckRustKeepRules>("checkRustKeepRules$cap") {
                    description = "Fails the ${variant.name} build when R8 did not keep RustSensing and its natives."
                    mappingFile.set(mapping)
                    seedsFile.set(mapping.map { it.asFile.resolveSibling("seeds.txt") }.let { layout.file(it) })
                    stamp.set(layout.buildDirectory.file("intermediates/checkRustKeepRules/${variant.name}/ok.txt"))
                }
                // Every minified artifact goes through one of these packaging tasks (assemble<V> and
                // install<V> through package<V>, bundle<V> through package<V>Bundle), so none is
                // written without the check. A dependency, not a finalizer, so a failed check leaves no APK.
                tasks.matching { it.name == "package$cap" || it.name == "package${cap}Bundle" }
                    .configureEach { dependsOn(checkKeep) }
            }
        }
    }
}

/*
 * Design 0026, D-6: two flags with one meaning each, both from carriesEngine. NATIVE_READER: this
 * variant packages the engine, so the support selection gets the real engine
 * (SupportScoreEngineBinding.kt). SCORE_SCAN: this variant offers the owner's support score scan;
 * the rust build type only for now (the beta follows in PR C of the design).
 */
androidComponents {
    onVariants { variant ->
        val carries = carriesEngine(variant.buildType)
        variant.buildConfigFields?.put(
            "NATIVE_READER",
            com.android.build.api.variant.BuildConfigField("boolean", carries.toString(), "the variant packages the Rust engine"),
        )
        variant.buildConfigFields?.put(
            "SCORE_SCAN",
            com.android.build.api.variant.BuildConfigField("boolean", (carries && variant.buildType == "rust").toString(), "the owner's support score scan is offered"),
        )
    }
}

/*
 * Issue #568: the `rust` build type is the owner's Rust test APK, built only from the `rust`
 * branch and never published. It always carries the engine; without it, it would be a beta with
 * another name, so with `-Pfga.native=false` its variant does not exist (beforeVariants below), and
 * aggregate tasks such as `assemble`, `test` and `check` run without it. The tasks listed in
 * rustVariantTasks stop with this message instead of Gradle's "not found"; an abbreviation or any
 * other rust task gets Gradle's own message, because matching task names by pattern also caught
 * unrelated tasks.
 */
val rustBuildNumber: Int = providers.gradleProperty("fga.rust.build").orNull.let { raw ->
    when {
        raw == null -> 1
        Regex("[1-9][0-9]?").matches(raw) -> raw.toInt()
        else -> throw GradleException("-Pfga.rust.build must be a whole number from 1 to 99, got '$raw'")
    }
}
val rustNativeMissing = "The rust build type is the Rust test APK and always packages the Rust engine; " +
    "it cannot be built with -Pfga.native=false (see docs/agents/rust-native.md, \"Rust test APK\")."
val rustVariantTasks = listOf("assembleRust", "installRust", "bundleRust", "packageRust")
    .flatMap { listOf(it, ":app:$it") }
    .toSet()
if (!engineRegistered && gradle.startParameter.taskNames.any { it in rustVariantTasks }) {
    throw GradleException(rustNativeMissing)
}

if (!engineRegistered) {
    androidComponents {
        beforeVariants(selector().withBuildType("rust")) { it.enable = false }
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
        // The Rust test APK is a build of the current beta; the suffix tells its builds apart
        // without a key in version.properties, which main shares.
        val rust = variant.buildType == "rust"
        val name = if (rust) "$betaVersionName-rust.$rustBuildNumber" else envVersionName ?: if (beta) betaVersionName else stableVersionName
        val code = if (rust) betaVersionCode else envVersionCode ?: if (beta) betaVersionCode else stableVersionCode
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

    /** Entry names without JAR (v1) signature files, which a re-sign may add, drop or replace. */
    private fun entryNames(file: File): Set<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().map { it.name }.filterNot { name ->
            name == "META-INF/MANIFEST.MF" ||
                (name.startsWith("META-INF/") && listOf(".SF", ".RSA", ".DSA", ".EC").any { name.endsWith(it) })
        }.toSet()
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
        // checkEngineApk<V> checked the file before this re-sign. apksigner only adds signature
        // data, so the entries must stay the same; if they did not, the checked file is not the
        // one that ships, and nothing is left at the release path.
        val before = entryNames(apkFile)
        val after = entryNames(signed)
        if (before != after) {
            signed.delete()
            throw GradleException(
                "apksigner changed the entries of ${apkFile.name}: added ${(after - before).sorted()}, removed ${(before - after).sorted()}",
            )
        }
        Files.move(signed.toPath(), apkFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Signed ${apkFile.name} with the rotated release key (lineage ${lineageFile.name}).")
    }
}

val debugSigningOnly: Provider<Boolean> = providers.environmentVariable("CI").map { it == "true" }
    .orElse(false)
    .zip(providers.gradleProperty("fgaDebugSigning").map { it == "true" }.orElse(false)) { a, b -> a || b }

(listOf("ci", "beta") + if (engineRegistered) listOf("rust") else emptyList()).forEach { buildType ->
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
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

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
        // NATIVE_READER and SCORE_SCAN are set per variant, from carriesEngine (the Rust core block above).
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
                abiFilters.addAll(shippedAbis)
            }
        }
        create("beta") {
            initWith(getByName("ci"))
            applicationIdSuffix = ".beta"
            matchingFallbacks += listOf("ci", "release")
        }
        // Issue #568: the owner's Rust test APK (see the rust block above); installs beside the beta.
        create("rust") {
            initWith(getByName("beta"))
            applicationIdSuffix = ".rust"
            matchingFallbacks += listOf("beta", "ci", "release")
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
        abortOnError = true
        baseline = file("lint-baseline.xml")
        error += "StringFormatCount"
        disable += "MissingTranslation"
        // Dependency versions are handled by dependencyUpdates (see docs/agents/dependencies.md).
        disable += listOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion", "SimilarGradleDependency")
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
    // The nativeProbe device suite runs its test class through JUnitCore inside the custom runner.
    androidTestImplementation(libs.junit4)
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
    // Chaldea sync over root (issue #362): libsu's RootService runs the same sync service as uid 0.
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

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

    // The app parses the model pin from its assets; the test checks the pin file itself.
    val ocrModelPin = rootProject.layout.projectDirectory.file("native/ocr-model/model.properties")
    systemProperty("fga.ocrModelPin", ocrModelPin.asFile.path)
    inputs.file(ocrModelPin).withPathSensitivity(PathSensitivity.NONE).withPropertyName("ocrModelPin")
    // The reply codes of the Rust support-score engine, which the Rust test checks too.
    val scoreWireCodes = rootProject.layout.projectDirectory.file("native/fga-ffi/src/score_engine/wire-codes.txt")
    systemProperty("fga.scoreWireCodes", scoreWireCodes.asFile.path)
    inputs.file(scoreWireCodes).withPathSensitivity(PathSensitivity.NONE).withPropertyName("scoreWireCodes")

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
