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
}
