package io.github.fate_grand_automata.sensing

import android.content.Context
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine

/**
 * Owner decision (issue #568): users of the public app see nothing of the Rust engine. So every
 * build but the rust build type gets [NoSupportScoreEngine], and [create] (the only path to
 * `System.loadLibrary`) is never called there.
 */
fun supportScoreEngineFor(rustTestBuild: Boolean, create: () -> SupportScoreEngine): SupportScoreEngine =
    if (rustTestBuild) create() else NoSupportScoreEngine

/** [stopRequested] reaches only the rust build type's engine; it must answer without waiting. */
fun supportScoreEngine(context: Context, stopRequested: () -> Boolean): SupportScoreEngine =
    supportScoreEngineFor(BuildConfig.RUST_TEST_BUILD) { RustSupportScoreEngine.forApp(context, stopRequested = stopRequested) }
