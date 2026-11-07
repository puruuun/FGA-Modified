package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureUiRulesTest {
    @Test fun firstEnableRequiresAgreement() {
        assertEquals(CaptureEnableAction.RequestConsent, CaptureConsent.enableAction(true, 0))
        assertFalse(CaptureConsent.agreed(0))
    }

    @Test fun onlyCurrentAgreementAllowsEnable() {
        assertEquals(CaptureEnableAction.Enable, CaptureConsent.enableAction(true, CaptureConsent.VERSION))
        assertTrue(CaptureConsent.agreed(CaptureConsent.VERSION))
    }

    @Test fun olderAgreementMustBeAskedAgain() {
        assertEquals(CaptureEnableAction.RequestConsent, CaptureConsent.enableAction(true, CaptureConsent.VERSION - 1))
    }

    @Test fun disableNeverRequiresAgreement() {
        for (version in listOf(0, CaptureConsent.VERSION)) {
            assertEquals(CaptureEnableAction.Disable, CaptureConsent.enableAction(false, version))
        }
    }

    @Test fun buttonRequiresAllThreeConditions() {
        for (enabled in listOf(false, true)) for (api in listOf(28, 29, 36)) {
            for (installed in listOf(false, true)) {
                assertEquals(enabled && api >= 29 && installed, captureButtonVisible(enabled, api, installed))
            }
        }
    }

    @Test fun buttonAlwaysUsesActualRunningState() {
        assertEquals(CaptureButtonState.Start, captureButtonState(false))
        assertEquals(CaptureButtonState.Stop, captureButtonState(true))
        assertEquals(CaptureButtonState.Start, captureButtonState(false))
    }

    private fun summary(recoverAt: Long, now: Long = 1000, max: Long = 100): AccountSummary {
        val game = Json.parseToJsonElement(
            """[{"userId":"1","name":"Master","lv":120,"actMax":$max,"actRecoverAt":$recoverAt}]"""
        ).jsonArray
        return AccountSummary.from(
            AccountState(mapOf("userGame" to game), Json.parseToJsonElement("[]").jsonArray, null, 1, 1), now
        )
    }

    @Test fun fullTimeKeepsServerInstantIncludingPartialAp() {
        val s = summary(1301)
        assertEquals(98, s.ap)
        assertEquals(1301, fullApAt(s, 1000))
    }

    @Test fun alreadyFullHasNoFutureTime() {
        assertNull(fullApAt(summary(1000), 1000))
        assertNull(fullApAt(summary(500), 1000))
    }

    @Test fun fullTimeAndApUpdateAcrossMinuteTicks() {
        assertEquals(99, summary(1301, now = 1060).ap)
        assertEquals(1301, fullApAt(summary(1301, now = 1060), 1060))
        assertEquals(100, summary(1301, now = 1301).ap)
        assertNull(fullApAt(summary(1301, now = 1301), 1301))
    }

    @Test fun missingRecoveryAndZeroMaxShowFull() {
        assertNull(fullApAt(summary(0), 1000))
        assertNull(fullApAt(summary(5000, max = 0), 1000))
    }

    @Test fun fruitOrderMatchesAccountCard() {
        assertEquals(listOf(100L, 101L, 104L, 102L), captureFruitOrder)
    }
}

class CaptureFeaturesAvailableTest {
    @Test fun requiresTheSwitchAndAndroid10() {
        assertFalse(captureFeaturesAvailable(false, 28))
        assertFalse(captureFeaturesAvailable(false, 29))
        assertFalse(captureFeaturesAvailable(true, 28))
        assertTrue(captureFeaturesAvailable(true, 29))
        assertTrue(captureFeaturesAvailable(true, 36))
    }
}
