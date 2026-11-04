package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaptureConsentUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private fun prefs(): PrefsCore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("capture-consent-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    @Test fun cancelLeavesSwitchOffAndNoAgreement() {
        val prefs = prefs()
        compose.setContent {
            FGATheme {
                val gate = rememberCaptureAgreement(prefs)
                Button(onClick = { gate(false) { prefs.captureTrackerEnabled.set(true) } }) { Text("Enable") }
            }
        }
        compose.onNodeWithText("Enable").performClick()
        assertFalse(prefs.captureTrackerEnabled.get())
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(prefs.captureTrackerEnabled.get())
        assertEquals(0, prefs.captureConsentVersion.get())
    }

    @Test fun agreeEnablesAndLaterStartsSkipDialog() {
        val prefs = prefs()
        var starts = 0
        compose.setContent {
            FGATheme {
                val gate = rememberCaptureAgreement(prefs)
                Button(onClick = { gate(false) { prefs.captureTrackerEnabled.set(true); starts++ } }) { Text("Enable") }
            }
        }
        compose.onNodeWithText("Enable").performClick()
        compose.onNodeWithText("Agree and use").performClick()
        assertTrue(prefs.captureTrackerEnabled.get())
        assertEquals(CaptureConsent.VERSION, prefs.captureConsentVersion.get())
        compose.onNodeWithText("Enable").performClick()
        assertEquals(2, starts)
        compose.onNodeWithText("Before using log collection").assertDoesNotExist()
    }

    @Test fun reviewCanShowAgainWithoutEnabling() {
        val prefs = prefs().apply { captureConsentVersion.set(CaptureConsent.VERSION) }
        compose.setContent {
            FGATheme {
                val gate = rememberCaptureAgreement(prefs)
                Button(onClick = { gate(true) {} }) { Text("Review") }
            }
        }
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Agree and use").performClick()
        assertFalse(prefs.captureTrackerEnabled.get())
    }

    @Test fun buttonTextChangesWhenVpnStateChanges() {
        val running = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { FGATheme { CaptureMainButton(running.value) {} } }
        compose.onNodeWithText("Stop log collection").assertExists()
        compose.runOnIdle { running.value = false }
        compose.onNodeWithText("Start log collection").assertExists()
        compose.onNodeWithText("Stop log collection").assertDoesNotExist()
    }

    @Test fun buttonColourPairsMeetTextContrastInEitherTheme() {
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float =
            (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
        assertTrue(contrast(captureStartBackground, captureStartForeground) >= 4.5f)
        assertTrue(contrast(captureStopBackground, captureStopForeground) >= 4.5f)
    }
}
