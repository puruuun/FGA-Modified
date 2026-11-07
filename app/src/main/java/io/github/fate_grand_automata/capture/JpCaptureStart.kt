package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs alongside VPN startup. A failed or delayed root operation cannot hold up KR capture. */
object JpCaptureStart {
    fun start(scope: CoroutineScope, featuresAvailable: Boolean, enabled: Boolean,
        installer: JpCaInstaller, notify: (Int) -> Unit): Job? {
        if (!featuresAvailable || !enabled) return null
        return scope.launch(Dispatchers.IO) {
            try {
                if (installer.isPresent()) return@launch
                installer.ensureCertificate()
                when (installer.inject()) {
                    RootCaResult.Injected -> if (installer.isGameRunning()) notify(R.string.capture_jp_restart)
                    RootCaResult.AlreadyPresent -> Unit
                    else -> notify(R.string.capture_jp_start_failed)
                }
            } catch (_: Exception) { notify(R.string.capture_jp_start_failed) }
        }
    }
}
