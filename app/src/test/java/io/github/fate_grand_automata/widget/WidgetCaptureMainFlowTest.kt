package io.github.fate_grand_automata.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.CaptureVpnService
import io.github.fate_grand_automata.capture.CertStore
import io.github.fate_grand_automata.capture.rememberCaptureMain
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowVpnService
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-hdpi")
class WidgetCaptureMainFlowTest {
    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val requests = WidgetLaunchRequests()
    private var settingsOpened = false
    private var finishes = 0
    private val results = mutableListOf<Boolean>()
    private lateinit var activity: Activity

    @After fun cleanup() {
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    private fun show(agreed: Boolean = true, certificate: Boolean = true) {
        CaptureTracker.setRunning(false)
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = CaptureVpnService.GAME_PACKAGE })
        if (certificate) {
            File(CertStore.directory(context), "leaf.p12").writeText("synthetic")
            File(CertStore.directory(context), "ca.cer").writeText("synthetic")
        }
        val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("widget_flow", Context.MODE_PRIVATE)), context).apply {
            captureTrackerEnabled.set(true)
            captureConsentVersion.set(if (agreed) CaptureConsent.VERSION else 0)
        }
        requests.consume(WidgetIntents.startCapture(context))
        var pending by mutableStateOf(false)
        compose.setContent {
            activity = LocalContext.current as Activity
            FGATheme {
                val capture = rememberCaptureMain(prefs, onStartFinished = { success ->
                    results += success
                    if (pending && success) finishes++
                    pending = false
                }) { settingsOpened = true }
                WidgetCaptureStartEffect(requests, capture.state.running) {
                    pending = true
                    capture.toggle()
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun successfulWidgetStartClosesOnlyAfterTheVpnIsActuallyRunningAndDoesNotReplay() {
        ShadowVpnService.setPrepareResult(null)
        show()
        assertEquals(0, finishes)
        assertFalse(requests.captureStartPending)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(CaptureVpnService::class.java.name, started.component!!.className)
        compose.runOnIdle { CaptureTracker.setRunning(true) }
        compose.waitForIdle()
        assertEquals(listOf(true), results)
        assertEquals(1, finishes)
        compose.runOnIdle { CaptureTracker.setRunning(false) }
        compose.waitForIdle()
        assertEquals(1, finishes)
    }

    @Test fun cancellingAgreementKeepsTheActivityOpenEvenIfCollectionStartsElsewhere() {
        show(agreed = false)
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertEquals(listOf(false), results)
        compose.runOnIdle { CaptureTracker.setRunning(true) }
        compose.waitForIdle()
        assertEquals(0, finishes)
    }

    @Test fun missingCertificateOpensTheGuideAndKeepsTheActivityOpen() {
        show(certificate = false)
        assertTrue(settingsOpened)
        assertEquals(listOf(false), results)
        assertEquals(0, finishes)
    }

    @Test fun cancellingVpnConsentKeepsTheActivityOpen() {
        val vpn = Intent("widget.TEST_VPN_CONSENT")
        ShadowVpnService.setPrepareResult(vpn)
        show()
        compose.runOnIdle { shadowOf(activity).receiveResult(vpn, Activity.RESULT_CANCELED, null) }
        compose.waitForIdle()
        assertEquals(listOf(false), results)
        assertEquals(0, finishes)
    }
}
