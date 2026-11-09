package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureController
import io.github.fate_grand_automata.capture.CaptureStopReason
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.captureReadiness
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import timber.log.Timber

/** AP includes the info area, navigation, symmetric insets and both 40 dp tiles. */
const val AP_LOG_TILE_MIN_WIDTH_DP = 250
/** Two compact launcher cells. One-cell layouts keep their original single tile. */
const val SERVICE_LOG_TILE_MIN_WIDTH_DP = 120

fun widgetLogButtonAvailable(show: Boolean, enabled: Boolean, api: Int): Boolean =
    show && enabled && api >= Build.VERSION_CODES.Q

fun widgetLogTileVisible(available: Boolean, widthDp: Int, minWidthDp: Int): Boolean =
    available && widthDp >= minWidthDp

fun widgetLogButtonAvailable(context: Context, settings: WidgetSettings): Boolean =
    widgetLogButtonAvailable(settings.showLogButton, captureEnabled(context), Build.VERSION.SDK_INT)

enum class WidgetCaptureAction { Stop, Start, OpenApp }

fun widgetCaptureAction(running: Boolean, consent: Boolean, ready: Boolean, vpnGranted: Boolean): WidgetCaptureAction = when {
    running -> WidgetCaptureAction.Stop
    consent && ready && vpnGranted -> WidgetCaptureAction.Start
    else -> WidgetCaptureAction.OpenApp
}

internal interface WidgetCaptureControl {
    fun running(): Boolean
    fun canStart(context: Context): Boolean
    fun start(context: Context)
    fun stop()
}

private object AndroidWidgetCaptureControl : WidgetCaptureControl {
    override fun running() = CaptureTracker.running.value

    override fun canStart(context: Context): Boolean {
        val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)), context)
        return CaptureController.isSupported && prefs.captureTrackerEnabled.get() &&
            widgetCaptureAction(
                running = false,
                consent = CaptureConsent.agreed(prefs.captureConsentVersion.get()),
                ready = captureReadiness(context, prefs).ready,
                vpnGranted = CaptureController.consentIntent(context) == null,
            ) == WidgetCaptureAction.Start
    }

    override fun start(context: Context) = CaptureController.start(context)
    override fun stop() = CaptureController.stop(CaptureStopReason.User)
}

/** Handles the user interaction synchronously so the widget's foreground-start exemption is retained. */
class ToggleCaptureReceiver internal constructor(private val control: WidgetCaptureControl) : BroadcastReceiver() {
    constructor() : this(AndroidWidgetCaptureControl)

    override fun onReceive(context: Context, intent: Intent) {
        if (control.running()) {
            control.stop()
            return
        }
        val started = runCatching {
            if (!control.canStart(context)) false
            else {
                control.start(context)
                true
            }
        }.onFailure { Timber.w(it, "Widget capture start failed") }.getOrDefault(false)
        if (!started) context.startActivity(WidgetIntents.startCapture(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun capturePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context, 0, Intent(context, ToggleCaptureReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
