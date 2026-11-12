package io.github.fate_grand_automata.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer

import java.util.concurrent.CancellationException

/** Script-start fallback when service auto start did not bring up the VPN. */
object CaptureAutoStart {
    internal var onStartStepHook: (() -> Unit)? = null

    /**
     * Called just before a script runs. Never blocks or fails the script.
     *
     * [battle] is true for battle scripts only. Other scripts (summon, lottery, gift box, enhancement ...) leave the
     * outside-change period open and start no run session; their recorded sources are marked as script-made.
     */
    fun onScriptStart(context: Context, prefs: PrefsCore, server: GameServer, battle: Boolean = true) {
        captureSafely("onScriptStart") {
            try {
                onStartStepHook?.invoke()
                val serverCode = when (server) {
                    GameServer.Kr -> "KR"
                    GameServer.Jp.Original -> "JP"
                    else -> null
                }
                CaptureTracker.discardRun()
                CaptureTracker.init(context)
                if (serverCode != null) CaptureTracker.outsideScriptStart(battle, serverCode)
                if (!CaptureController.isSupported) return@captureSafely
                val readiness = captureReadiness(context, prefs)
                if (server != GameServer.Kr && (server != GameServer.Jp.Original || !readiness.jpReady)) return@captureSafely
                if (battle && serverCode != null) CaptureTracker.startRun(serverCode)
                if (!prefs.captureTrackerEnabled.get() || !CaptureConsent.agreed(prefs.captureConsentVersion.get()) ||
                    CaptureTracker.running.value) return@captureSafely
                try {
                    if (CaptureController.consentIntent(context) != null) {
                        toast(context, R.string.capture_auto_no_consent)
                    } else if (!readiness.ready) {
                        toast(context, R.string.capture_auto_no_certificate)
                    } else {
                        CaptureTracker.starting = true
                        CaptureController.start(context)
                    }
                } catch (t: Throwable) {
                    CaptureTracker.starting = false
                    toast(context, R.string.capture_auto_failed)
                    throw t
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                CaptureTracker.starting = false
                captureSafely("discardRunAfterStartFailure") {
                    CaptureTracker.discardRun()
                }
                throw t
            }
        }
    }

    fun onScriptEnd() {
        captureSafely("onScriptEnd") {
            CaptureTracker.endRunCollection()
        }
    }

    private fun toast(context: Context, res: Int) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }
}
