package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.CaptureAccounts
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
    private fun state(id: String, recoverAt: Long, updatedAt: Long = 7777) = AccountState(
        mapOf("userGame" to Json.parseToJsonElement("""[{"userId":"$id","name":"Master $id","actMax":100,"actRecoverAt":$recoverAt}]""").jsonArray),
        Json.parseToJsonElement("[]").jsonArray, null, 1, updatedAt,
    )

    private fun account(recoverAt: Long, now: Long = 1000, id: String = "1"): CaptureAccount {
        val s = state(id, recoverAt)
        return CaptureAccount(id, "Master $id", AccountSummary.from(s, now), s.lastUpdateTime, "KR")
    }

    @Test fun partialApUsesTheMainScreenCardValues() {
        val a = account(1301)
        val m = apWidgetModel(a, 1000)
        assertEquals(98, m.ap)
        assertEquals(100, m.apMax)
        assertEquals(a.summary.ap, m.ap)
        assertEquals(fullApAt(a.summary, 1000), m.fullAt)
        assertEquals(0.98f, m.fraction)
        assertEquals(7777, m.dataTime)
    }

    @Test fun zeroApHasFullTimeAtTheServerInstant() {
        val m = apWidgetModel(account(1000 + 100 * 300), 1000)
        assertEquals(0, m.ap)
        assertEquals(0f, m.fraction)
        assertEquals(31000, m.fullAt)
    }

    @Test fun fullAndPastRecoveryHaveNoFullTime() {
        for (recoverAt in listOf(1000L, 500L)) {
            val m = apWidgetModel(account(recoverAt), 1000)
            assertEquals(100, m.ap)
            assertEquals(1f, m.fraction)
            assertNull(m.fullAt)
        }
    }

    @Test fun apKeepsRecoveringBetweenCapturesWithoutNewData() {
        val m = apWidgetModel(account(1301, now = 1181), 1181)
        assertEquals(99, m.ap)
        assertEquals(1301, m.fullAt)
        assertEquals(7777, m.dataTime)
    }

    @Test fun nameAndIdComeFromTheAccount() {
        val m = apWidgetModel(account(1301, id = "42").copy(displayName = "주회 계정"), 1000)
        assertEquals("42", m.accountId)
        assertEquals("주회 계정", m.name)
    }

    @Test fun fractionHandlesZeroMaxAndOverCap() {
        val m = apWidgetModel(account(1301), 1000)
        assertEquals(0f, m.copy(apMax = 0).fraction)
        assertEquals(1f, m.copy(ap = 120).fraction)
    }

    @Test fun noAccountsOrUnavailableCollectionGivesNoData() {
        assertEquals(ApWidgetState.NoData, apWidgetState(true, 33, emptyList(), 1000))
        assertEquals(ApWidgetState.NoData, apWidgetState(false, 33, listOf(account(1301)), 1000))
        assertEquals(ApWidgetState.NoData, apWidgetState(true, 28, listOf(account(1301)), 1000))
    }

    @Test fun accountOrderIsTheTrackerOrderWithCurrentFirstThenNewest() {
        val store = CaptureAccounts()
        fun add(id: String, time: Long) {
            val s = state(id, 1301, time)
            store.apply("https://yfgo-game.netmarble.com/login/top", Json.parseToJsonElement("""{"cache":{"replaced":{"userGame":[{"userId":"$id"}]}}}""") as kotlinx.serialization.json.JsonObject, time, reduce = { s })
        }
        add("old", 10)
        add("new", 30)
        add("middle", 20)
        add("current", 5)
        val accounts = store.accounts(1000)
        assertEquals(listOf("current", "new", "middle", "old"), accounts.map { it.id })
        val result = assertIs<ApWidgetState.Data>(apWidgetState(true, 33, accounts, 1000))
        assertEquals(accounts.map { it.id }, result.models.map { it.accountId })
    }

    @Test fun stableItemIdsSurviveReorderRenameAndHashCodeCollisions() {
        val ids = listOf("7001", "7002", "Aa", "BB", "legacy_account-1")
        assertEquals(ids.size, ids.map(::apWidgetItemId).toSet().size)
        assertEquals(ids.associateWith(::apWidgetItemId), ids.reversed().associateWith(::apWidgetItemId))
        assertTrue(ids.map(::apWidgetItemId).all { it >= 0 })
        val a = account(1301)
        assertEquals(apWidgetItemId(a.id), apWidgetItemId(a.copy(displayName = "Renamed").id))
    }

    @Test fun typeSizesChangeAtTheThreeBreakpoints() {
        val small = ApWidgetFields(8, 7)
        val medium = ApWidgetFields(10, 8)
        val large = ApWidgetFields(14, 10)
        val xlarge = ApWidgetFields(18, 11)
        for (width in listOf(180f, 199f, AP_WIDGET_MEDIUM_TEXT_MIN_DP - 0.1f)) assertEquals(small, apWidgetFields(width))
        for (width in listOf(AP_WIDGET_MEDIUM_TEXT_MIN_DP, 210f, AP_WIDGET_LARGE_TEXT_MIN_DP - 0.1f)) assertEquals(medium, apWidgetFields(width))
        for (width in listOf(AP_WIDGET_LARGE_TEXT_MIN_DP, 230f, AP_WIDGET_XLARGE_TEXT_MIN_DP - 0.1f)) assertEquals(large, apWidgetFields(width))
        for (width in listOf(AP_WIDGET_XLARGE_TEXT_MIN_DP, 268f, 272f)) assertEquals(xlarge, apWidgetFields(width))
    }

    @Test fun dataAgeUsesTheLargestWholeUnit() {
        val t = 1_800_000_000L
        assertEquals(DataAge.Now, dataAge(t, t))
        assertEquals(DataAge.Now, dataAge(t - 59, t))
        assertEquals(DataAge.Minutes(1), dataAge(t - 60, t))
        assertEquals(DataAge.Minutes(59), dataAge(t - 3599, t))
        assertEquals(DataAge.Hours(1), dataAge(t - 3600, t))
        assertEquals(DataAge.Hours(23), dataAge(t - 86399, t))
        assertEquals(DataAge.Days(1), dataAge(t - 86400, t))
        assertEquals(DataAge.Days(3), dataAge(t - 3 * 86400 - 5, t))
        assertEquals(DataAge.Now, dataAge(t + 500, t))
    }

    @Test fun serviceWidgetFollowsTheServiceState() {
        assertEquals(ServiceWidgetAction.Start, serviceWidgetModel(false).action)
        assertEquals(ServiceWidgetAction.Stop, serviceWidgetModel(true).action)
        assertTrue(serviceWidgetModel(false).labelRes != serviceWidgetModel(true).labelRes)
    }
}