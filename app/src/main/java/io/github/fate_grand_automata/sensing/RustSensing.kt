package io.github.fate_grand_automata.sensing

/**
 * The calls of the Rust support-score engine (native/fga-ffi/src/score_engine), as
 * [RustSupportScoreEngine] uses them; a unit test replaces them with a fake.
 */
interface ScoreNative {
    /** Null when the library is loaded, otherwise the loader's message. */
    fun loadLibrary(): String?

    /** `OK: <load ms>` or `ERR: <message>`; a second load without unload is `ERR`. */
    fun load(modelPath: String): String

    /** The reply layout of fga-ffi's `score_engine::wire`, parsed by [ScoreWire]; null on a JNI failure. */
    fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray?

    /** 1 dropped, 0 nothing was loaded, negative an error ([ScoreWire.CODE_ERROR], [ScoreWire.CODE_PENDING_EXCEPTION]). */
    fun unload(): Int

    /** 1 loaded, 0 not, 2 busy (another call holds the engine lock; answered without waiting), negative an error. */
    fun isLoaded(): Int
}

/**
 * JNI binding to libfga_ffi.so for product code (issue #568). Rust keeps this engine in its own
 * slot, apart from the device probes (`qa.NativeProbe`): neither can load over, read from or
 * unload the other's model. Only [RustSupportScoreEngine] calls it, and only in the rust build type.
 *
 * The parameters are nullable so that a null reaching Rust is answered there instead of failing here.
 */
object RustSensing {
    external fun load(modelPath: String?): String
    external fun readScore(pixels: ByteArray?, width: Int, height: Int): IntArray?
    external fun unload(): Int
    external fun isLoaded(): Int
}

/** [ScoreNative] on the real library. */
object RustScoreNative : ScoreNative {
    override fun loadLibrary(): String? = try {
        System.loadLibrary("fga_ffi")
        null
    } catch (error: UnsatisfiedLinkError) {
        error.message ?: error.toString()
    }

    override fun load(modelPath: String) = RustSensing.load(modelPath)
    override fun readScore(pixels: ByteArray, width: Int, height: Int) = RustSensing.readScore(pixels, width, height)
    override fun unload() = RustSensing.unload()
    override fun isLoaded() = RustSensing.isLoaded()
}
