package io.github.fate_grand_automata.qa

/**
 * Stage 1-0 probes of design 0025, implemented in native/fga-ffi. Each call returns
 * `OK: <detail>` or `ERR: <detail>`; a Rust panic comes back as `ERR: panic: <message>`.
 */
object NativeProbe {
    /**
     * The loader's message when `System.loadLibrary("fga_ffi")` failed, null when it worked. It
     * tells a library that is not in the APK apart from one that is there but cannot be loaded,
     * for example because the ONNX Runtime build's libonnxruntime.so is missing or does not load.
     */
    var loadError: String? = null
        private set

    /** False when libfga_ffi.so did not load; [skipReason] says why. */
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("fga_ffi")
            true
        } catch (error: UnsatisfiedLinkError) {
            loadError = error.message ?: error.toString()
            false
        }
    }

    /**
     * The skipped reason for a case that needs the library. "Not packaged" only when the class
     * loader says it could not find libfga_ffi.so itself (libcore's Runtime.loadLibrary0 message);
     * any other failure, such as dlopen naming a missing libonnxruntime.so, is reported as a load
     * failure with the loader's own message.
     */
    fun skipReason(): String {
        // Reading `loaded` runs the load first, so loadError is set before it is read.
        if (loaded) return "libfga_ffi.so is loaded"
        val message = loadError ?: "no error recorded"
        return if (message.contains("couldn't find \"libfga_ffi.so\"")) {
            "libfga_ffi.so is not packaged; build with -Pfga.native=true ($message)"
        } else {
            "libfga_ffi.so is packaged but could not be loaded: $message"
        }
    }

    external fun ping(): String
    external fun panicProbe(): String
    external fun ringProbe(): String
    external fun rustlsProbe(): String
    external fun tractProbe(): String
    external fun cpuProbe(): String

    // Issue #530: the PP-OCRv6 recogniser with the tract backend. `OK: ` is followed by a JSON object.
    /** Loads the model (replacing any loaded one): `{"loadMs":…,"classes":…}`. Nullable so a probe can check that null comes back as `ERR`. */
    external fun ocrLoad(modelPath: String?): String
    /** One grayscale crop, one byte per pixel; empty [allowed] means no restriction: `{"text":…,"confidence":…,"micros":…}`. */
    external fun ocrRead(pixels: ByteArray, width: Int, height: Int, allowed: String): String
    /** `{"dropped":true|false}`. */
    external fun ocrUnload(): String
    /** `/proc/self/status`: `{"vmRssKb":…,"vmHwmKb":…,"vmSizeKb":…}`. */
    external fun memoryStatus(): String

    // Issue #549: the same tract recogniser with a chosen minimum model width (8..4096, 320 is
    // what ocrLoad uses) and kernel thread count (0..16, 0 and 1 mean the calling thread). It
    // fills the slot ocrRead and ocrUnload use. More than one thread needs a library built with
    // `-Pfga.native.threads=true`; otherwise the call returns `ERR`.
    /** `{"loadMs":…,"classes":…,"minWidth":…,"threads":…}`. */
    external fun ocrLoadTract(modelPath: String?, minWidth: Int, threads: Int): String
    /** `{"threadsBuilt":true|false,"maxThreads":16}`. */
    external fun ocrTractFeatures(): String

    // Issue #555: the support-score reader on the recogniser that ocrLoad loaded (no load call of
    // its own). `ERR` before ocrLoad.
    /**
     * One 60x30 gray score crop: `{"class":62|null,"grand":16|null,"display":"ordinary"|"compound"|"unknown",
     * "text":...,"confidence":...,"minCharConfidence":...|null,"classReason":...|null,"grandReason":...|null,
     * "clipRight":...,"clipLeft":...,"clipVertical":...,"featureless":...,"micros":...}`.
     */
    external fun supportScoreRead(pixels: ByteArray, width: Int, height: Int): String

    // Issue #535: the same recogniser with ONNX Runtime. Only in a library built with
    // `-Pfga.native.ort=true`; otherwise each call throws UnsatisfiedLinkError.
    /** [threads] is ONNX Runtime's intra-op thread count, 0 for its default: `{"loadMs":…,"classes":…,"intraThreads":…}`. */
    external fun ocrLoadOrt(modelPath: String?, threads: Int): String
    /** As [ocrRead], on the ONNX Runtime model. */
    external fun ocrReadOrt(pixels: ByteArray, width: Int, height: Int, allowed: String): String
    /** `{"dropped":true|false}`. */
    external fun ocrUnloadOrt(): String
}
