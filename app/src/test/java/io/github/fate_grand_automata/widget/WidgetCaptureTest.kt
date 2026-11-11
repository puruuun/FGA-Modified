package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 34, 36])
class WidgetCaptureTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun visibilityRequiresAllThreeGatesAndEnoughWidth() {
        for (show in listOf(false, true)) for (enabled in listOf(false, true)) for (api in listOf(28, 29, 34, 36)) {
            val available = widgetLogButtonAvailable(show, enabled, api)
            assertEquals(show && enabled && api >= 29, available)
            for (width in listOf(180, 200, 249, 250, 272, 378)) {
                assertEquals(available && width >= 250, widgetLogTileVisible(available, width, AP_LOG_TILE_MIN_WIDTH_DP))
            }
            assertFalse(widgetLogTileVisible(available, 72, SERVICE_LOG_TILE_MIN_WIDTH_DP))
            assertEquals(available, widgetLogTileVisible(available, 140, SERVICE_LOG_TILE_MIN_WIDTH_DP))
        }
    }

    @Test fun everyReadinessCombinationRoutesToStopDirectStartOrTheApp() {
        for (running in listOf(false, true)) for (consent in listOf(false, true))
            for (ready in listOf(false, true)) for (vpn in listOf(false, true)) {
                val expected = when {
                    running -> WidgetCaptureAction.Stop
                    consent && ready && vpn -> WidgetCaptureAction.Start
                    else -> WidgetCaptureAction.OpenApp
                }
                assertEquals(expected, widgetCaptureAction(running, consent, ready, vpn))
            }
    }

    private class Control(val collecting: Boolean = false, val ready: Boolean = true, val fail: Boolean = false) : WidgetCaptureControl {
        var starts = 0
        var stops = 0
        var readinessChecks = 0
        override fun running() = collecting
        override fun canStart(context: Context): Boolean { readinessChecks++; return ready }
        override fun start(context: Context) { starts++; if (fail) error("start rejected") }
        override fun stop() { stops++ }
    }

    @Test fun collectingStopsImmediatelyWithoutReadingReadinessOrOpeningAnActivity() {
        val control = Control(collecting = true)
        ToggleCaptureReceiver(control).onReceive(context, Intent())
        assertEquals(1, control.stops)
        assertEquals(0, control.starts)
        assertEquals(0, control.readinessChecks)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun readyStartsDirectlyWithoutOpeningAnActivity() {
        val control = Control()
        ToggleCaptureReceiver(control).onReceive(context, Intent())
        assertEquals(1, control.starts)
        assertEquals(0, control.stops)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun missingReadinessOpensTheOneShotFlow() = checkOpen(Control(ready = false))
    @Test fun rejectedDirectStartFallsBackToTheSameFlow() = checkOpen(Control(fail = true))

    private fun checkOpen(control: Control) {
        ToggleCaptureReceiver(control).onReceive(context, Intent())
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(intent.filterEquals(WidgetIntents.startCapture(context)))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val requests = WidgetLaunchRequests()
        requests.consume(intent)
        assertTrue(requests.takeCaptureStart())
        assertFalse(requests.takeCaptureStart())
        assertFalse(intent.hasExtra(WidgetIntents.EXTRA_WIDGET_CAPTURE_START))
        requests.consume(intent)
        assertFalse(requests.takeCaptureStart())
        requests.consume(WidgetIntents.startCapture(context), restored = true)
        assertFalse(requests.takeCaptureStart())
        assertFalse(requests.takeStart())
    }

    @Test fun tapIntentIsAnImmutableExplicitForegroundBroadcastDistinctFromServiceActions() {
        val pending = shadowOf(capturePendingIntent(context))
        assertTrue(pending.isBroadcastIntent)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(pending.savedIntent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0)
        assertEquals(ToggleCaptureReceiver::class.java.name, pending.savedIntent.component!!.className)
        assertFalse(pending.savedIntent.filterEquals(shadowOf(tilePendingIntent(context, ServiceWidgetAction.Stop)).savedIntent))
        assertFalse(WidgetIntents.startCapture(context).filterEquals(WidgetIntents.startService(context)))
    }

    @Test fun realReceiverStartsTheDeclaredSpecialUseForegroundServiceWhenReady() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("capture_tracker_enabled", true)
            .putInt("capture_consent_version", io.github.fate_grand_automata.capture.CaptureConsent.VERSION).commit()
        shadowOf(context.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = io.github.fate_grand_automata.capture.CaptureVpnService.GAME_PACKAGE
        })
        io.github.fate_grand_automata.capture.CertStore.apply { commit(context, prepare()) }
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        ToggleCaptureReceiver().onReceive(context, Intent())
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(io.github.fate_grand_automata.capture.CaptureVpnService::class.java.name, started.component!!.className)
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val info = context.packageManager.getServiceInfo(started.component!!, 0)
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.packageManager.checkPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE", context.packageName))
        }
    }
}
