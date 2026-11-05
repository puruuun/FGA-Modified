package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlin.test.*

class OutsidePeriodTest {
    @Test fun sumsRepeatedSourcesAndKeepsOtherPathsSeparate() {
        val gift = ChangeSource.of("present/receive")
        val period = OutsidePeriod("fictional", 10)
            .add(gift, AccountDelta(mapOf(1L to 8L), qp = 1000), 11)
            .add(ChangeSource.of("card/combine"), AccountDelta(mapOf(1L to -3L), qp = -200), 12)
            .add(gift, AccountDelta(mapOf(1L to 2L), freeStone = 1), 13)
            .add(ChangeSource.of("unknown/a?discard=yes"), AccountDelta(mana = 1), 14)
            .add(ChangeSource.of("unknown/b"), AccountDelta(rarePri = 1), 15)
        assertEquals(AccountDelta(mapOf(1L to 7L), qp = 800, mana = 1, rarePri = 1, freeStone = 1), period.total)
        assertEquals(AccountDelta(mapOf(1L to 10L), qp = 1000, freeStone = 1), period.sources.single { it.source == gift }.delta)
        assertEquals(4, period.sources.size)
        assertEquals(10, period.startedAt)
        assertEquals(15, period.endedAt)
    }

    @Test fun fruitUseCountsDecreasesOfAllFourFruitsWithoutErasingLaterClaims() {
        val period = OutsidePeriod("fictional", 10)
            .add(ChangeSource.of("item/recover"), AccountDelta(mapOf(100L to -2L, 101L to -3L, 102L to -4L, 104L to -5L, 103L to -6L)), 11)
            .add(ChangeSource.of("present/receive"), AccountDelta(mapOf(100L to 20L)), 12)
        assertEquals(mapOf(100L to 2L, 101L to 3L, 102L to 4L, 104L to 5L), period.fruitUsed)
        assertEquals(18, period.total.items[100])
    }

    @Test fun emptyDeltaDoesNotOpenOrMovePeriodAndCancellingItemsAreRemoved() {
        val empty = OutsidePeriod("fictional", 10)
        assertTrue(empty.isEmpty)
        assertSame(empty, empty.add(ChangeSource.of("home/top"), AccountDelta(), 99))
        val gift = ChangeSource.of("present/receive")
        val cancelled = empty.add(gift, AccountDelta(mapOf(1L to 2L)), 11)
            .add(gift, AccountDelta(mapOf(1L to -2L)), 12)
        assertTrue(cancelled.total.items.isEmpty())
        assertTrue(cancelled.isEmpty)
    }

    @Test fun serializationRoundTripIncludesSourcesTotalsAndFruit() {
        val period = OutsidePeriod("fictional", 10)
            .add(ChangeSource.of("unknown/path?discard=yes"), AccountDelta(mapOf(100L to -2L), chargeStone = -30), 20)
        val encoded = Json.encodeToString(period)
        assertEquals(period, Json.decodeFromString<OutsidePeriod>(encoded))
        assertFalse(encoded.contains("discard"))
    }

    @Test fun replenishingFruitWithinSameSourceKeepsUsageInThatSource() {
        val source = ChangeSource.of("battle/result")
        val period = OutsidePeriod("fictional", 10)
            .add(source, AccountDelta(mapOf(100L to -2L)), 11)
            .add(source, AccountDelta(mapOf(100L to 5L)), 12)
        assertEquals(3, period.sources.single().delta.items[100])
        assertEquals(2, period.sources.single().fruitUsed[100])
        assertEquals(2, period.fruitUsed[100])
    }
}
