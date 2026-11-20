package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.OcrService
import timber.log.Timber

/**
 * The engines of one script run's component, which [ScriptManager] frees as soon as the run ends
 * instead of waiting on a finalizer: the Tesseract OCR service (its native context) and the Rust
 * support-score engine (issue #568, its model).
 *
 * [release] and [attach] are called from the main thread (`startScript`, the service's
 * `onDestroy`) and from `Dispatchers.Default` (`onScriptExit` and the early exits inside the
 * launched start), and those calls can overlap. Closing can take long: `TesseractOcrService.close`
 * waits on its own lock for an OCR call that is still running. So the monitor guards only the two
 * fields; whoever takes an engine out of them closes it, after leaving the monitor. Each engine is
 * taken out once, so it is closed exactly once, and no caller waits for another caller's close.
 */
class RunEngines {
    private var ocrService: OcrService? = null
    private var supportScoreEngine: SupportScoreEngine? = null

    /**
     * Holds the engines of a new run. Whatever an earlier run left is swapped out under the monitor
     * and closed after leaving it, so the new engines are already held while the old ones close.
     */
    fun attach(ocrService: OcrService, supportScoreEngine: SupportScoreEngine) {
        val old = synchronized(this) {
            val taken = this.ocrService to this.supportScoreEngine
            this.ocrService = ocrService
            this.supportScoreEngine = supportScoreEngine
            taken
        }
        close(old.first, old.second)
    }

    fun release() {
        val taken = synchronized(this) {
            val taken = ocrService to supportScoreEngine
            ocrService = null
            supportScoreEngine = null
            taken
        }
        close(taken.first, taken.second)
    }

    private fun close(ocrService: OcrService?, supportScoreEngine: SupportScoreEngine?) {
        ocrService?.let {
            runCatching { it.close() }
                .onFailure { e -> Timber.w(e, "Failed to release the OCR service") }
        }
        supportScoreEngine?.let {
            runCatching { it.close() }
                .onFailure { e -> Timber.w(e, "Failed to release the support-score engine") }
        }
    }
}
