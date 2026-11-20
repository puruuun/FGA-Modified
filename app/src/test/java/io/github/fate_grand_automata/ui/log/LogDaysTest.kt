package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.capture.OutsideLogEntry
import io.github.fate_grand_automata.capture.mixedCaptureLogs
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.GameDay
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.scripts.capture.RunGains
import java.time.OffsetDateTime
import java.util.TimeZone
import kotlin.test.*

class LogDaysTest {
    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun battle(
        id: String, ended: String, totalMs: Long = 0, account: String? = "A", qp: Long? = null, items: List<GainedItem> = emptyList(),
    ) = CaptureLogItem(battle = battleLogFixture(id).copy(
        endedAt = at(ended), totalTime = totalMs, accountId = account, accountName = account?.let { "name$it" },
        gains = if (qp != null || items.isNotEmpty()) RunGains(items = items, qp = qp) else null))

    private fun outside(id: String, started: String, ended: String = started, account: String = "A", qp: Long = 100): OutsideLogEntry {
        val period = OutsidePeriod(account, at(started) / 1000)
            .add(ChangeSource.of("present/receive"), AccountDelta(qp = qp), at(started) / 1000 + 1)
            .add(ChangeSource.of("shop/purchase"), AccountDelta(qp = qp), at(started) / 1000 + 2)
        return OutsideLogEntry(id, account, "name$account", at(started), at(ended), period)
    }

    private fun item(entry: OutsideLogEntry, inProgress: Boolean = false) = CaptureLogItem(outside = entry, inProgress = inProgress)
    private fun dayOf(item: CaptureLogItem) = groupLogDays(listOf(item)).single().day

    @Test fun theDayChangesAtFourInTheMorningKoreaTime() {
        assertEquals(GameDay(2026, 11, 10), dayOf(battle("a", "2026-11-11T01:00:00+09:00")))
        assertEquals(GameDay(2026, 11, 11), dayOf(battle("b", "2026-11-11T04:00:00.000+09:00")))
        assertEquals(GameDay(2026, 11, 10), dayOf(battle("c", "2026-11-11T03:59:59.999+09:00")))
    }

    @Test fun theGroupingIgnoresTheDeviceTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            listOf("UTC", "America/New_York").forEach { id ->
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                assertEquals(GameDay(2026, 11, 10), dayOf(battle("a", "2026-11-11T03:59:59.999+09:00")), id)
                assertEquals(GameDay(2026, 11, 11), dayOf(battle("b", "2026-11-11T04:00:00+09:00")), id)
            }
        } finally { TimeZone.setDefault(saved) }
    }

    @Test fun yearEndRollsOverAtFour() {
        assertEquals(GameDay(2026, 12, 31), dayOf(battle("a", "2027-01-01T03:59:59.999+09:00")))
        assertEquals(GameDay(2027, 1, 1), dayOf(battle("b", "2027-01-01T04:00:00+09:00")))
    }

    @Test fun aRunThatCrossesFourStaysOnItsStartDay() {
        val run = battle("run", "2026-11-11T04:20:00+09:00", totalMs = 30 * 60_000L)
        assertEquals(GameDay(2026, 11, 10), dayOf(run))
    }

    @Test fun anUnusableTotalTimeFallsBackToTheEndTime() {
        assertEquals(GameDay(2026, 11, 11), dayOf(battle("zero", "2026-11-11T04:10:00+09:00", totalMs = 0)))
        assertEquals(GameDay(2026, 11, 11), dayOf(battle("negative", "2026-11-11T04:10:00+09:00", totalMs = -5_000)))
    }

    @Test fun anOutsideRecordIsGroupedByItsStartNotItsCloseTime() {
        val spanning = item(outside("old", "2026-11-10T23:00:00+09:00", ended = "2026-11-12T10:00:00+09:00"))
        assertEquals(GameDay(2026, 11, 10), dayOf(spanning))
    }

    @Test fun twoAccountsOnOneDayStaySeparate() {
        val day = groupLogDays(listOf(
            battle("a", "2026-11-11T10:00:00+09:00", account = "A", qp = 10), battle("b", "2026-11-11T11:00:00+09:00", account = "B", qp = 20),
        )).single()
        assertEquals(listOf("A", "B"), day.accounts.map { it.accountId })
        assertEquals(listOf(mapOf("2:1" to 10L), mapOf("2:1" to 20L)), day.accounts.map { it.totals })
    }

    @Test fun battleGainsAreAddedToTheAccountTotal() {
        val day = groupLogDays(listOf(
            item(outside("o", "2026-11-11T09:00:00+09:00", qp = 100)),
            battle("b", "2026-11-11T10:00:00+09:00", qp = 50, items = listOf(GainedItem(2, 6510, 3))),
        )).single()
        // The outside record lists its change twice (two sources), once in the period total.
        val account = day.accounts.single()
        assertEquals(mapOf("2:1" to 250L, "2:6510" to 3L), account.totals)
        assertEquals(1, account.battles.size)
        assertEquals(1, account.outside.size)
    }

    @Test fun recordsWithoutAnAccountGoUnderNoProfile() {
        val day = groupLogDays(listOf(battle("n", "2026-11-11T09:00:00+09:00", account = null, qp = 5),
            battle("a", "2026-11-11T09:30:00+09:00", account = "A", qp = 7))).single()
        assertEquals(listOf("A", null), day.accounts.map { it.accountId })
        assertNull(day.accounts.last().name)
        assertEquals(mapOf("2:1" to 5L), day.accounts.last().totals)
    }

    @Test fun aScriptOnlyDayAndAChangeOnlyDayEachLackOneSection() {
        val days = groupLogDays(listOf(battle("s", "2026-11-12T09:00:00+09:00"), item(outside("o", "2026-11-11T09:00:00+09:00"))))
        assertEquals(listOf(GameDay(2026, 11, 12), GameDay(2026, 11, 11)), days.map { it.day })
        assertEquals(listOf(1 to 0, 0 to 1), days.map { it.battles.size to it.outside.size })
        assertTrue(days[0].accounts.single().totals.isEmpty())
    }

    @Test fun filtersRunBeforeGrouping() {
        val items = listOf(battle("a", "2026-11-12T09:00:00+09:00", account = "A", qp = 1), battle("b", "2026-11-11T09:00:00+09:00", account = "B", qp = 1))
        val filtered = filterCaptureLogs(items, LogTimeFilter.All, emptySet(), 0L, profile = "B")
        assertEquals(listOf(GameDay(2026, 11, 11)), groupLogDays(filtered).map { it.day })
    }

    @Test fun anInProgressRecordIsNotCountedTwice() {
        val entry = outside("o", "2026-11-11T09:00:00+09:00")
        val mixed = mixedCaptureLogs(emptyList(), listOf(entry), listOf(entry))
        val day = groupLogDays(mixed).single()
        assertEquals(1, day.outside.size)
        assertTrue(day.inProgress)
        assertEquals(200L, day.accounts.single().totals["2:1"])
    }

    @Test fun duplicateIdsAreDroppedEvenWhenTheCallerDidNotMerge() {
        val entry = outside("o", "2026-11-11T09:00:00+09:00")
        val day = groupLogDays(listOf(item(entry, inProgress = true), item(entry))).single()
        assertEquals(1, day.outside.size)
        assertEquals(200L, day.accounts.single().totals["2:1"])
    }

    private fun rowsOf(vararg items: CaptureLogItem) = logDayRows(groupLogDays(items.toList()))

    @Test fun eachGameDayAndAccountIsOneRowNewestDayFirstNoProfileLast() {
        val rows = rowsOf(
            battle("a1", "2026-11-11T10:00:00+09:00", account = "A", qp = 1), battle("b1", "2026-11-11T11:00:00+09:00", account = "B", qp = 2),
            battle("n1", "2026-11-11T12:00:00+09:00", account = null), battle("a2", "2026-11-12T10:00:00+09:00", account = "A", qp = 3),
        )
        assertEquals(listOf("2026-11-12" to "A", "2026-11-11" to "A", "2026-11-11" to "B", "2026-11-11" to null),
            rows.map { it.day.key to it.account.accountId })
        assertEquals(4, rows.map { it.key }.distinct().size)
    }

    @Test fun aRowOnlyCarriesItsOwnAccountsBattlesAndTotals() {
        val rows = rowsOf(
            battle("a1", "2026-11-11T10:00:00+09:00", account = "A", qp = 10), battle("b1", "2026-11-11T11:00:00+09:00", account = "B", qp = 20),
            item(outside("oa", "2026-11-11T09:00:00+09:00", account = "A", qp = 100)),
        )
        val a = rows.single { it.account.accountId == "A" }.account
        val b = rows.single { it.account.accountId == "B" }.account
        assertEquals(listOf("a1"), a.battles.map { it.id })
        assertEquals(listOf("b1"), b.battles.map { it.id })
        assertEquals(mapOf("2:1" to 210L), a.totals)
        assertEquals(mapOf("2:1" to 20L), b.totals)
        assertTrue(b.outside.isEmpty())
    }

    @Test fun afterTheProfileFilterOnlyThatAccountsDaysRemain() {
        val items = listOf(
            battle("a1", "2026-11-11T10:00:00+09:00", account = "A", qp = 1), battle("b1", "2026-11-11T11:00:00+09:00", account = "B", qp = 1),
            battle("a2", "2026-11-12T10:00:00+09:00", account = "A", qp = 1), battle("b2", "2026-11-13T10:00:00+09:00", account = "B", qp = 1),
        )
        val rows = logDayRows(groupLogDays(filterCaptureLogs(items, LogTimeFilter.All, emptySet(), 0L, profile = "A")))
        assertEquals(listOf("2026-11-12", "2026-11-11"), rows.map { it.day.key })
        assertTrue(rows.all { it.account.accountId == "A" })
    }

    @Test fun theNoProfileRowKeyNeverCollidesWithAnAccountIdNamedNone() {
        assertNotEquals(logDayRowKey("2026-11-11", null), logDayRowKey("2026-11-11", "none"))
    }

    @Test fun inProgressIsPerAccount() {
        val rows = rowsOf(item(outside("oa", "2026-11-11T09:00:00+09:00", account = "A"), inProgress = true),
            battle("b1", "2026-11-11T11:00:00+09:00", account = "B"))
        assertEquals(listOf(true, false), rows.map { it.account.inProgress })
    }

    @Test fun theDeviceZoneNoteAppearsOnlyOutsideNinePlusHours() {
        val now = at("2026-11-11T12:00:00+09:00")
        assertFalse(deviceZoneDiffers(now, TimeZone.getTimeZone("Asia/Seoul")))
        assertFalse(deviceZoneDiffers(now, TimeZone.getTimeZone("Asia/Tokyo")))
        assertTrue(deviceZoneDiffers(now, TimeZone.getTimeZone("UTC")))
    }

    private fun outsideOf(id: String, started: String, vararg sources: ChangeSource): CaptureLogItem {
        val start = at(started) / 1000
        val period = sources.foldIndexed(OutsidePeriod("A", start)) { i, p, source -> p.add(source, AccountDelta(qp = 10), start + 1 + i) }
        return item(OutsideLogEntry(id, "A", "nameA", at(started), at(started), period))
    }

    private fun sourcesOf(vararg records: CaptureLogItem) = daySources(groupLogDays(records.toList()).single().accounts.single())

    private val battleEntry = DaySource.Battle
    private fun other(source: LogSourceFilter) = DaySource.Other(source)

    @Test fun aScriptBattleAloneGivesOneBattleEntry() {
        assertEquals(listOf(battleEntry), sourcesOf(battle("b1", "2026-11-11T10:00:00+09:00"), battle("b2", "2026-11-11T11:00:00+09:00")))
    }

    @Test fun aHandPlayedQuestAloneGivesOneBattleEntryAndNoManualQuestEntry() {
        val sources = sourcesOf(outsideOf("m", "2026-11-11T10:00:00+09:00", ChangeSource.of("battle/result")))
        assertEquals(listOf(battleEntry), sources)
        assertFalse(sources.contains(other(LogSourceFilter.ManualQuest)))
    }

    @Test fun aScriptBattleAndAHandPlayedQuestShareOneBattleEntry() {
        assertEquals(listOf(battleEntry), sourcesOf(
            battle("b", "2026-11-11T10:00:00+09:00"), outsideOf("m", "2026-11-11T12:00:00+09:00", ChangeSource.of("battle/result"))))
    }

    @Test fun sourcesFollowTheirFirstAppearanceInTimeAndRepeatsAreDropped() {
        val sources = sourcesOf(
            // Given out of order on purpose: the list is by start time, not by input order.
            outsideOf("late", "2026-11-11T15:00:00+09:00", ChangeSource.of("present/receive"), ChangeSource.of("gacha/draw")),
            battle("b", "2026-11-11T12:00:00+09:00"),
            outsideOf("early", "2026-11-11T09:00:00+09:00", ChangeSource.of("card/x"), ChangeSource.of("present/receive")),
        )
        assertEquals(listOf(other(LogSourceFilter.Enhancement), other(LogSourceFilter.GiftBox), battleEntry, other(LogSourceFilter.Summon)), sources)
    }

    @Test fun aPeriodKeepsItsStoredSourceOrderAtItsStart() {
        assertEquals(listOf(other(LogSourceFilter.Shop), other(LogSourceFilter.GiftBox)), sourcesOf(
            outsideOf("p", "2026-11-11T09:00:00+09:00", ChangeSource.of("shop/purchase"), ChangeSource.of("present/receive"))))
    }

    @Test fun scriptFlaggedAndOtherSourcesGetTheFilterGroups() {
        assertEquals(listOf(other(LogSourceFilter.Script), other(LogSourceFilter.Other)), sourcesOf(
            outsideOf("p", "2026-11-11T09:00:00+09:00", ChangeSource(ChangeSource.Group.GiftBox, script = true), ChangeSource.of("zzz/unknown"))))
    }

    @Test fun aDayWithoutSourcesGivesAnEmptyList() {
        val account = LogDayAccount("A", "nameA", "JP", emptyMap(), emptyList(), emptyList())
        assertEquals(emptyList(), daySources(account))
    }
}
