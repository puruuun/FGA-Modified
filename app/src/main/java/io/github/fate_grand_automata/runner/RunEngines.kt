package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.OcrService
import timber.log.Timber

/**
 * The engines of one script run's component, which [ScriptManager] frees as soon as the run ends
 * instead of waiting on a finalizer: the Tesseract OCR service (its native context) and the Rust
 * support-score engine (issue #568, its model). Every path that ends a run calls [release]: the
 * script exit, a failed screen detection, a skill screen without a servant, a cancelled launcher
 * and the service's destruction. Releasing twice closes nothing twice.
 */
class RunEngines {
    private var ocrService: OcrService? = null
    private var supportScoreEngine: SupportScoreEngine? = null

    /** Holds the engines of a new run; whatever an earlier run left is released first. */
    fun attach(ocrService: OcrService, supportScoreEngine: SupportScoreEngine) {
        release()
        this.ocrService = ocrService
        this.supportScoreEngine = supportScoreEngine
    }

    fun release() {
        ocrService?.let {
            ocrService = null
            runCatching { it.close() }
                .onFailure { e -> Timber.w(e, "Failed to release the OCR service") }
        }
        supportScoreEngine?.let {
            supportScoreEngine = null
            runCatching { it.close() }
                .onFailure { e -> Timber.w(e, "Failed to release the support-score engine") }
        }
    }
}
