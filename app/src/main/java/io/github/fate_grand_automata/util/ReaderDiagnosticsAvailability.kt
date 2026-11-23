package io.github.fate_grand_automata.util

import io.github.fate_grand_automata.BuildConfig

/**
 * Where the support reader diagnostics switch is offered and honoured: the beta, and the owner's
 * Rust test APK so a real farming run with the engine-first reader can be recorded (design 0026).
 */
fun readerDiagnosticsOffered(buildType: String = BuildConfig.BUILD_TYPE): Boolean =
    buildType == "beta" || buildType == "rust"
