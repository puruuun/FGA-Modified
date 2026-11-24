package io.github.fate_grand_automata.sensing

import android.content.Context
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine

/**
 * Owner decision (issue #568, kept by design 0026): users of the public app see nothing of the
 * Rust engine, no setting, text or mode. A build that packages the engine ([BuildConfig.NATIVE_READER]:
 * the beta, the stable build made with -Pfga.native=true, and the rust build type) gives the support selection the real engine, which falls
 * back to the existing reader without a word when it cannot load. Every other build gets
 * [NoSupportScoreEngine], and [create] (the only path to `System.loadLibrary`) is never called there.
 */
fun supportScoreEngineFor(nativeReader: Boolean, create: () -> SupportScoreEngine): SupportScoreEngine =
    if (nativeReader) create() else NoSupportScoreEngine

/** [stopRequested] reaches only the real engine; it must answer without waiting. */
fun supportScoreEngine(context: Context, stopRequested: () -> Boolean): SupportScoreEngine =
    supportScoreEngineFor(BuildConfig.NATIVE_READER) { RustSupportScoreEngine.forApp(context, stopRequested = stopRequested) }
