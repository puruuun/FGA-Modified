package io.github.fate_grand_automata.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer

/** Script-start fallback when service auto start did not bring up the VPN. */
object CaptureAutoStart {
    /** Called just before a script runs. Never blocks or fails the script. */
    fun onScriptStart(context: Context, prefs: PrefsCore, server: GameServer) {
        CaptureTracker.discardRun()
        CaptureTracker.init(context)
        CaptureTracker.outsideScriptStart()
        if (!CaptureController.isSupported || server != GameServer.Kr) return
        CaptureTracker.startRun()
        if (!prefs.captureTrackerEnabled.get() || !CaptureConsent.agreed(prefs.captureConsentVersion.get()) ||
            CaptureTracker.running.value) return
        runCatching {
            if (CaptureController.consentIntent(context) != null) {
                toast(context, R.string.capture_auto_no_consent)
            } else if (!CertStore.exists(context)) {
                toast(context, R.string.capture_auto_no_certificate)
            } else {
                CaptureTracker.starting = true
                CaptureController.start(context)
            }
        }.onFailure { CaptureTracker.starting = false; toast(context, R.string.capture_auto_failed) }
    }

    fun onScriptEnd() {
        CaptureTracker.endRunCollection()
    }

    private fun toast(context: Context, res: Int) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }
}
