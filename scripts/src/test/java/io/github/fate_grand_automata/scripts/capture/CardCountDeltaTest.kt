package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

class CardCountDeltaTest {
    private fun state(vararg tables: Pair<String, String>) = AccountState(
        tables.associate { (table, rows) -> table to Json.parseToJsonElement(rows).jsonArray },
        JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1,
    )

    private fun cards(id: Long, count: Int, field: String = "svtId") =
        (1..count).joinToString(prefix = "[", postfix = "]") { """{"id":$it,"$field":$id}""" }

    @Test fun countsAcrossBothTablesAndMovingCardsIsNetZero() {
        val before = state("userSvt" to cards(10, 3), "userSvtStorage" to cards(10, 2))
        val moved = state("userSvt" to cards(10, 1), "userSvtStorage" to cards(10, 4))
        assertTrue(AccountDelta.between(before, moved).isEmpty)
        assertEquals(mapOf(10L to 2L), AccountDelta.between(before,
            state("userSvt" to cards(10, 3), "userSvtStorage" to cards(10, 4))).svts)
    }

    @Test fun sellingThreeCesCountsRemovedRowsWithoutDeletedCache() {
        val before = state("userSvt" to cards(9400010, 4))
        val after = state("userSvt" to cards(9400010, 1))
        assertEquals(mapOf(9400010L to -3L), AccountDelta.between(before, after).svts)
    }

    @Test fun spendingTwelveEmbersCountsRowsInBothStores() {
        val before = state("userSvt" to cards(9700100, 10), "userSvtStorage" to cards(9700100, 4))
        val after = state("userSvtStorage" to cards(9700100, 2))
        assertEquals(mapOf(9700100L to -12L), AccountDelta.between(before, after).svts)
    }

    @Test fun levelAscensionAndLockChangesProduceNothing() {
        val before = state("userSvt" to """[{"id":1,"svtId":10,"lv":1,"limitCount":0,"isLock":false}]""")
        val after = state("userSvt" to """[{"id":1,"svtId":10,"lv":90,"limitCount":4,"isLock":true}]""")
        assertTrue(AccountDelta.between(before, after).isEmpty)
    }

    @Test fun commandCodesAndFouHaveIndependentSignedCounts() {
        val before = state("userCommandCode" to cards(10, 3, "commandCodeId"), "userSvt" to cards(9900100, 2))
        val after = state("userCommandCode" to cards(10, 1, "commandCodeId"), "userSvtStorage" to cards(9900100, 3))
        val delta = AccountDelta.between(before, after)
        assertEquals(mapOf(10L to -2L), delta.commandCodes)
        assertEquals(mapOf(9900100L to 1L), delta.svts)
    }

    @Test fun malformedRowsAreSkippedAndWholeTableRemovalIsCounted() {
        val held = state("userSvt" to """[null,5,{}, {"svtId":[]},{"svtId":"bad"},{"svtId":10}]""",
            "userCommandCode" to cards(20, 2, "commandCodeId"))
        assertEquals(AccountDelta(svts = mapOf(10L to -1L), commandCodes = mapOf(20L to -2L)),
            AccountDelta.between(held, state()))
    }

    @Test fun plusCombinesCardKindsRemovesZeroAndIsEmptyIncludesBoth() {
        val first = AccountDelta(svts = mapOf(10L to -3L), commandCodes = mapOf(10L to 1L))
        assertFalse(first.isEmpty)
        assertFalse(AccountDelta(commandCodes = mapOf(10L to 1L)).isEmpty)
        assertFalse(AccountDelta(svts = mapOf(10L to 1L)).isEmpty)
        val combined = first + AccountDelta(svts = mapOf(10L to 3L, 20L to 2L), commandCodes = mapOf(10L to -1L))
        assertEquals(AccountDelta(svts = mapOf(20L to 2L)), combined)
        assertTrue((first + AccountDelta(svts = mapOf(10L to 3L), commandCodes = mapOf(10L to -1L))).isEmpty)
        assertTrue(AccountDelta(svts = mapOf(10L to 0L), commandCodes = mapOf(10L to 0L)).isEmpty)
    }

    @Test fun oldJsonDefaultsAndNewFieldsRoundTrip() {
        val legacy = Json.decodeFromString<AccountDelta>("""{"items":{"1":2},"qp":3}""")
        assertEquals(AccountDelta(items = mapOf(1L to 2L), qp = 3), legacy)
        val delta = legacy.copy(svts = mapOf(10L to -12L), commandCodes = mapOf(20L to 1L))
        assertEquals(delta, Json.decodeFromString<AccountDelta>(Json.encodeToString(delta)))
        val period = Json.decodeFromString<OutsidePeriod>("""{"accountId":"synthetic","startedAt":1,"sources":[{"source":{"group":"Shop"},"delta":{"qp":3}}],"total":{"qp":3}}""")
        assertEquals(AccountDelta(qp = 3), period.total)
        assertTrue(period.sources.single().delta.svts.isEmpty())
    }

    @Test fun enhancementAndShopKeepCardOnlyChangesInSourceAndPeriodTotals() {
        val enhancement = AccountDelta(svts = mapOf(9700100L to -12L))
        val shop = AccountDelta(svts = mapOf(9400010L to -3L), commandCodes = mapOf(10L to -1L))
        val period = OutsidePeriod("synthetic", 1)
            .add(ChangeSource.of("svtCombine/execute"), enhancement, 2)
            .add(ChangeSource.of("shop/sell"), shop, 3)
        assertFalse(period.isEmpty)
        assertEquals(enhancement + shop, period.total)
        assertEquals(enhancement, period.sources.single { it.source.group == ChangeSource.Group.Enhancement }.delta)
        assertEquals(shop, period.sources.single { it.source.group == ChangeSource.Group.Shop }.delta)
    }
}
