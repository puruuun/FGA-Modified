package io.github.fate_grand_automata.qa

/**
 * Stage 1-0 probes of design 0025, implemented in native/fga-ffi. Each call returns
 * `OK: <detail>` or `ERR: <detail>`; a Rust panic comes back as `ERR: panic: <message>`.
 */
object NativeProbe {
    /** False when the APK was built without `-Pfga.native=true`, so there is no libfga_ffi.so. */
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("fga_ffi")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
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

    // Issue #535: the same recogniser with ONNX Runtime. Only in a library built with
    // `-Pfga.native.ort=true`; otherwise each call throws UnsatisfiedLinkError.
    /** [threads] is ONNX Runtime's intra-op thread count, 0 for its default: `{"loadMs":…,"classes":…,"intraThreads":…}`. */
    external fun ocrLoadOrt(modelPath: String?, threads: Int): String
    /** As [ocrRead], on the ONNX Runtime model. */
    external fun ocrReadOrt(pixels: ByteArray, width: Int, height: Int, allowed: String): String
    /** `{"dropped":true|false}`. */
    external fun ocrUnloadOrt(): String
}
