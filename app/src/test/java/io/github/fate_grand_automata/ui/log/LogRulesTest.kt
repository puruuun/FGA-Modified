package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.scripts.capture.RunGains
import java.time.Instant
import java.util.TimeZone
import kotlin.test.*

class LogRulesTest {
    @Test fun friendPointsUseTheSameResourceListAndGainsFilterAsQp() {
        fun entry(id: String, change: Long) = CaptureLogItem(outside = outsideFixture(id).let { outside ->
            outside.copy(period = OutsidePeriod("fictionalA", 10)
                .add(ChangeSource.of("gacha/draw"), AccountDelta(friendPoint = change), 11))
        })
        val gain = entry("gain", 200)
        val loss = entry("loss", -200)
        assertEquals(setOf("2:4"), logResources(listOf(gain, loss)))
        assertEquals(listOf(gain), filterCaptureLogs(listOf(gain, loss), LogTimeFilter.All, "2:4", now, zone))
        assertEquals(listOf(LogResource(id = 4, count = -200)), outsideResources(AccountDelta(friendPoint = -200), emptyMap()))
    }

    private val zone = TimeZone.getTimeZone("Asia/Seoul")
    private val now = Instant.parse("2026-11-05T05:00:00Z").toEpochMilli()
    private fun battle(id: String, date: String, resource: Long = 6510, count: Long = 3) = CaptureLogItem(battle = battleLogFixture(id).copy(
        endedAt = Instant.parse(date).toEpochMilli(), gains = RunGains(items = listOf(GainedItem(2, resource, count)))))
    private val today = battle("today", "2026-11-04T15:00:00Z")
    private val week = battle("week", "2026-10-29T15:00:00Z", 6001)
    private val month = battle("month", "2026-10-06T15:00:00Z")
    private val old = battle("old", "2026-10-05T15:00:00Z")
    private val all = listOf(today, week, month, old)

    @Test fun todayUsesLocalMidnightInclusively() {
        assertEquals(listOf(today), filterCaptureLogs(all, LogTimeFilter.Today, null, now, zone))
        val prior = battle("prior", "2026-11-04T14:59:59Z")
        assertTrue(filterCaptureLogs(listOf(prior), LogTimeFilter.Today, null, now, zone).isEmpty())
    }
    @Test fun sevenAndThirtyDaysIncludeTodayAndTheirBoundaryDay() {
        assertEquals(listOf(today, week), filterCaptureLogs(all, LogTimeFilter.Week, null, now, zone))
        assertEquals(listOf(today, week, month), filterCaptureLogs(all, LogTimeFilter.Month, null, now, zone))
        assertEquals(all, filterCaptureLogs(all, LogTimeFilter.All, null, now, zone))
    }
    @Test fun todayExcludesTomorrowAndCalendarDayHandlesDaylightSaving() {
        val future = battle("future", "2026-11-05T15:00:00Z")
        assertTrue(filterCaptureLogs(listOf(future), LogTimeFilter.Today, null, now, zone).isEmpty())
        val beforeFallback = battle("beforeFallback", "2026-11-01T04:00:00Z")
        val duringFallback = battle("duringFallback", "2026-11-01T06:30:00Z")
        assertEquals(listOf(beforeFallback, duringFallback), filterCaptureLogs(listOf(beforeFallback, duringFallback), LogTimeFilter.Today, null,
            Instant.parse("2026-11-01T20:00:00Z").toEpochMilli(), TimeZone.getTimeZone("America/New_York")))
    }
    @Test fun fruitGainsCanBeFoundEvenWhenGrossUsageOffsetsThem() {
        val outside = outsideFixture()
        val period = outside.period.copy(total = AccountDelta(mapOf(100L to 0L)), fruitUsed = mapOf(100L to 2L), sources = emptyList())
        val entry = CaptureLogItem(outside = outside.copy(period = period))
        assertEquals(listOf(entry), filterCaptureLogs(listOf(entry), LogTimeFilter.All, "2:100", now, zone))
    }
    @Test fun gainsFilterMatchesBothKindsAndRetainsPositiveSourceWithNegativeNet() {
        val outside = CaptureLogItem(outside = outsideFixture()) // 6001 gained in gift box, used in enhancement.
        assertEquals(listOf(week, outside), filterCaptureLogs(all + outside, LogTimeFilter.All, "2:6001", now, zone))
        assertTrue(filterCaptureLogs(listOf(outside), LogTimeFilter.All, "2:100", now, zone).isEmpty())
    }
    @Test fun currencyFilterMatchesQpAndLossesButNotUntrackedRuns() {
        val qp = CaptureLogItem(battle = battleLogFixture().copy(gains = RunGains(qp = 10)))
        val untracked = CaptureLogItem(battle = battleLogFixture("untracked"))
        val loss = battle("loss", "2026-11-05T00:00:00Z", 6510, -3)
        assertEquals(listOf(qp), filterCaptureLogs(listOf(qp, untracked), LogTimeFilter.All, "2:1", now, zone))
        assertEquals(listOf(loss), filterCaptureLogs(listOf(loss), LogTimeFilter.All, "2:6510", now, zone))
    }
    @Test fun timeAndResourceCombineWithoutChangingOrder() {
        assertEquals(listOf(today), filterCaptureLogs(all, LogTimeFilter.Week, "2:6510", now, zone))
        assertTrue(filterCaptureLogs(all, LogTimeFilter.Today, "2:6001", now, zone).isEmpty())
    }

    @Test fun customPickerDatesIncludeBothLocalDaysAndCombineWithResource() {
        val start = Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli()
        val tomorrow = battle("tomorrow", "2026-11-05T15:00:00Z")
        assertEquals(listOf(today, week), filterCaptureLogs(all + tomorrow, LogTimeFilter.Custom, null, now, zone, start, end))
        assertEquals(listOf(week), filterCaptureLogs(all, LogTimeFilter.Custom, "2:6001", now, zone, start, end))
        assertEquals(listOf(today), filterCaptureLogs(all, LogTimeFilter.Custom, null, now, zone, end, end))
    }

    @Test fun customRangeUsesCalendarDaysAcrossDstInsteadOfFixed24Hours() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val day = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        val first = battle("first", "2026-11-01T04:00:00Z")
        val last = battle("last", "2026-11-02T04:59:59Z")
        val next = battle("next", "2026-11-02T05:00:00Z")
        assertEquals(listOf(first, last), filterCaptureLogs(listOf(first, last, next), LogTimeFilter.Custom, null, now, zone, day, day))
    }
    @Test fun resourcesComeOnlyFromRecordedChangesAndKeepDropTypesDistinct() {
        val ce = CaptureLogItem(battle = battleLogFixture("ce").copy(gains = RunGains(items = listOf(GainedItem(3, 6510, 1)))))
        assertEquals(setOf("2:6510", "3:6510"), logResources(listOf(today, ce)))
        assertTrue(filterCaptureLogs(listOf(ce), LogTimeFilter.All, "2:6510", now, zone).isEmpty())
    }
    @Test fun usedStormPodIsInFilterAndFruitIsShownOnce() {
        val gains = RunGains(fruitUsed = mapOf(100L to 2L), itemsUsed = mapOf(49L to 20L, 100L to 2L))
        val entry = CaptureLogItem(battle = battleLogFixture("used").copy(gains = gains))
        assertTrue("2:49" in logResources(listOf(entry)))
        assertEquals(listOf(-2L, -20L), battleResources(gains).map { it.count })
        assertEquals(1, battleResources(gains).count { it.id == 100L })
        assertEquals(listOf(entry), filterCaptureLogs(listOf(entry), LogTimeFilter.All, "2:49", now, zone))
        assertEquals(listOf(entry), filterCaptureLogs(listOf(entry), LogTimeFilter.All, "2:100", now, zone))
    }
    @Test fun zeroRowsAndUnusedFruitAreAbsent() {
        assertTrue(outsideResources(AccountDelta(items = mapOf(100L to 0L, 6510L to 0L)), mapOf(100L to 0L)).isEmpty())
        assertEquals(listOf(LogResource(id = 1, count = 3)), outsideResources(AccountDelta(qp = 3), emptyMap()))
    }
    @Test fun fruitGainsAndGrossUsesRemainSeparateAndOrdered() {
        val resources = outsideResources(AccountDelta(mapOf(100L to 1L)), mapOf(100L to 2L, 101L to 0L))
        assertEquals(listOf(3L, -2L), resources.map { it.count })
    }
    @Test fun quartzKeepsFreePaidSplitEvenWhenTotalIsZero() {
        assertEquals(LogResource(id = 2, count = 0, split = 3L to -3L), outsideResources(AccountDelta(freeStone = 3, chargeStone = -3), emptyMap()).single())
    }
    @Test fun liveEntryIsFirstAndAStoredCopyNeverDuplicatesIt() {
        val open = outsideFixture(end = 1)
        val list = mixedCaptureLogs(listOf(today.battle!!), listOf(open), listOf(open))
        assertEquals(2, list.size)
        assertEquals("outside:${open.id}", list.first().id)
        assertTrue(list.first().inProgress)
        assertEquals(list.first().id, mixedCaptureLogs(emptyList(), listOf(open)).single().id)
    }
    @Test fun deleteSelectionHandlesBothKindsAllAndCancelWithoutMutation() {
        val mixed = listOf(today, CaptureLogItem(outside = outsideFixture(), inProgress = true))
        var selection = LogDeleteSelection().enter().toggle(today.id).toggle(mixed.last().id)
        assertEquals(mixed.map { it.id }, selection.targets(mixed))
        selection = selection.toggle(today.id)
        assertEquals(listOf(mixed.last().id), selection.targets(mixed))
        assertEquals(mixed.map { it.id }, selection.targets(mixed, all = true))
        assertTrue(selection.cancel().targets(mixed, all = true).isEmpty())
        assertEquals(LogDeleteSelection(), selection.cancel())
    }
    @Test fun deletionIgnoresStaleIdsAndCannotSelectOutsideDeleteMode() {
        assertTrue(LogDeleteSelection().toggle(today.id).selected.isEmpty())
        assertTrue(LogDeleteSelection(true, setOf("gone")).targets(all).isEmpty())
    }
}
