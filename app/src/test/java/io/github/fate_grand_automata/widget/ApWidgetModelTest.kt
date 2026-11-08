package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.fullApAt
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApWidgetModelTest {
    private fun account(recoverAt: Long, now: Long = 1000, max: Long = 100, id: String = "1", name: String = "Master", server: String = "KR"): CaptureAccount {
        val game = Json.parseToJsonElement(
            """[{"userId":"$id","name":"$name","lv":120,"actMax":$max,"actRecoverAt":$recoverAt}]"""
        ).jsonArray
        val state = AccountState(mapOf("userGame" to game), Json.parseToJsonElement("[]").jsonArray, null, 1, 7777)
        return CaptureAccount(id, name, AccountSummary.from(state, now), state.lastUpdateTime, server)
    }

    @Test fun partialApUsesTheMainScreenCardValues() {
        val a = account(1301)
        val m = apWidgetModel(a, 1000)
        assertEquals(98, m.ap)
        assertEquals(100, m.apMax)
        assertEquals(a.summary.ap, m.ap)
        assertEquals(fullApAt(a.summary, 1000), m.fullAt)
        assertEquals(1301L, m.fullAt)
        assertEquals(0.98f, m.fraction)
    }

    @Test fun timeLeftRoundsUpToWholeMinutes() {
        assertEquals(5L, apWidgetModel(account(1300), 1000).minutesLeft)
        assertEquals(6L, apWidgetModel(account(1301), 1000).minutesLeft)
        assertEquals(1L, apWidgetModel(account(1301, now = 1241), 1241).minutesLeft)
        assertEquals(1L, apWidgetModel(account(1301, now = 1300), 1300).minutesLeft)
        assertEquals(60L, apWidgetModel(account(4600, max = 100), 1000).minutesLeft)
    }

    @Test fun zeroApHasFullTimeAtTheServerInstant() {
        val m = apWidgetModel(account(1000 + 100 * 300), 1000)
        assertEquals(0, m.ap)
        assertEquals(0f, m.fraction)
        assertEquals(1000L + 100 * 300, m.fullAt)
        assertEquals(500L, m.minutesLeft)
    }

    @Test fun fullApHasNoTimeAndNoTimeLeft() {
        val m = apWidgetModel(account(1000), 1000)
        assertEquals(100, m.ap)
        assertEquals(1f, m.fraction)
        assertNull(m.fullAt)
        assertNull(m.minutesLeft)
    }

    @Test fun recoverAtInThePastMeansFull() {
        val m = apWidgetModel(account(500), 1000)
        assertEquals(100, m.ap)
        assertNull(m.fullAt)
    }

    @Test fun apKeepsRecoveringBetweenCapturesWithoutNewData() {
        val a = account(1301, now = 1000)
        // Recomputing the stored account at a later time moves AP and the remaining time, not the full-at instant.
        val later = a.copy(summary = AccountSummary.from(
            AccountState(mapOf("userGame" to Json.parseToJsonElement("""[{"userId":"1","actMax":100,"actRecoverAt":1301}]""").jsonArray),
                Json.parseToJsonElement("[]").jsonArray, null, 1, 7777), 1181))
        val m = apWidgetModel(later, 1181)
        assertEquals(99, m.ap)
        assertEquals(1301L, m.fullAt)
        assertEquals(2L, m.minutesLeft)
        assertEquals(7777L, m.dataTime)
    }

    @Test fun nameAndServerComeFromTheAccount() {
        val m = apWidgetModel(account(1301, name = "주회 계정", server = "JP", id = "42"), 1000)
        assertEquals("42", m.accountId)
        assertEquals("주회 계정", m.name)
        assertEquals("JP", m.server)
    }

    @Test fun zeroMaxDoesNotDivideByZero() {
        assertEquals(0f, apWidgetModel(account(5000, max = 0), 1000).fraction)
    }

    @Test fun stateIsDataForABoundAccountWhenFeaturesAreAvailable() {
        val state = apWidgetState("1", true, 33, listOf(account(1301)), 1000)
        assertIs<ApWidgetState.Data>(state)
        assertEquals("1", state.model.accountId)
    }

    @Test fun stateIsNoDataWhenTheAccountWasDeleted() {
        assertEquals(ApWidgetState.NoData, apWidgetState("2", true, 33, listOf(account(1301)), 1000))
        assertEquals(ApWidgetState.NoData, apWidgetState("1", true, 33, emptyList(), 1000))
    }

    @Test fun stateIsNoDataWhenLogCollectionIsOffOrAndroidIsBelow10() {
        assertEquals(ApWidgetState.NoData, apWidgetState("1", false, 33, listOf(account(1301)), 1000))
        assertEquals(ApWidgetState.NoData, apWidgetState("1", true, 28, listOf(account(1301)), 1000))
        assertEquals(ApWidgetState.NoData, apWidgetState(null, true, 33, listOf(account(1301)), 1000))
    }

    @Test fun layoutIsCompactAtAbout2x1AndFullAtAbout3x2() {
        assertEquals(ApWidgetLayout.Compact, apWidgetLayout(110f, 40f))
        assertEquals(ApWidgetLayout.Compact, apWidgetLayout(180f, 70f))
        assertEquals(ApWidgetLayout.Compact, apWidgetLayout(140f, 110f))
        assertEquals(ApWidgetLayout.Full, apWidgetLayout(180f, 110f))
        assertEquals(ApWidgetLayout.Full, apWidgetLayout(250f, 180f))
    }

    @Test fun choicesAreUnavailableWithoutLogCollectionOrAccounts() {
        val accounts = listOf(account(1301))
        assertEquals(ApWidgetChoices.Unavailable, apWidgetChoices(false, 33, accounts))
        assertEquals(ApWidgetChoices.Unavailable, apWidgetChoices(true, 28, accounts))
        assertEquals(ApWidgetChoices.Unavailable, apWidgetChoices(true, 33, emptyList()))
        assertTrue(apWidgetChoices(true, 33, accounts) is ApWidgetChoices.Accounts)
    }

    @Test fun serviceWidgetFollowsTheServiceState() {
        assertEquals(ServiceWidgetAction.Start, serviceWidgetModel(false).action)
        assertEquals(ServiceWidgetAction.Stop, serviceWidgetModel(true).action)
        assertTrue(serviceWidgetModel(false).labelRes != serviceWidgetModel(true).labelRes)
    }
}
