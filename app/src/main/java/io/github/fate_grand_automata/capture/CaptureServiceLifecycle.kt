package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore

/** Service lifecycle only; the gains run-session hooks remain separate. */
object CaptureServiceLifecycle {
    fun shouldStop(stopWithService: Boolean): Boolean = stopWithService

    fun onStart(context: Context, prefs: PrefsCore, requestConsent: (Intent) -> Unit) {
        if (!CaptureController.isSupported || !prefs.captureTrackerEnabled.get() || CaptureTracker.running.value) return
        runCatching {
            context.packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        }.getOrNull() ?: return
        runCatching {
            if (!CertStore.exists(context)) {
                toast(context, R.string.capture_service_no_certificate)
            } else {
                val intent = CaptureController.consentIntent(context)
                if (intent == null) CaptureController.start(context) else requestConsent(intent)
            }
        }.onFailure { toast(context, R.string.capture_service_failed) }
    }

    fun onStop(context: Context, prefs: PrefsCore) {
        if (CaptureController.isSupported && shouldStop(prefs.captureStopWithService.get())) {
            CaptureController.stop(CaptureStopReason.Service)
            // Also cancel a queued start whose service instance has not been created yet.
            context.stopService(Intent(context, CaptureVpnService::class.java))
        }
    }

    private fun toast(context: Context, message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
