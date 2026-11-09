package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import java.time.Instant
import java.util.TimeZone
import kotlin.test.*

class LogProfileTest {
    private val now = Instant.parse("2026-11-09T05:00:00Z").toEpochMilli()
    private val zone = TimeZone.getTimeZone("Asia/Seoul")
    private val battle = CaptureLogItem(battle = battleLogFixture("a").copy(
        accountId = "a", accountName = "프로필 A", endedAt = now,
        gains = RunGains(items = listOf(GainedItem(2, 6510, 1)))))
    private val outside = CaptureLogItem(outside = outsideFixture("outside", now).copy(accountId = "a", accountName = "프로필 A"))
    private val other = CaptureLogItem(battle = battleLogFixture("b").copy(accountId = "b", accountName = "프로필 B", endedAt = now))
    private val legacy = CaptureLogItem(battle = battleLogFixture("legacy").copy(endedAt = now))
    private val entries = listOf(battle, outside, other, legacy)

    @Test fun profileMatchesBothKindsAndOnlyAllIncludesUnidentifiedHistory() {
        assertEquals(listOf(battle, outside), filterCaptureLogs(entries, LogTimeFilter.All, null, now, zone, profile = "a"))
        assertEquals(listOf(other), filterCaptureLogs(entries, LogTimeFilter.All, null, now, zone, profile = "b"))
        assertEquals(entries, filterCaptureLogs(entries, LogTimeFilter.All, null, now, zone))
        assertTrue(filterCaptureLogs(listOf(legacy), LogTimeFilter.All, null, now, zone, profile = "a").isEmpty())
    }

    @Test fun profileTimeAndResourceCombineAndResourceChoicesUseTheSubset() {
        val old = CaptureLogItem(outside = outside.outside!!.copy(id = "old", endedAt = now - 10 * 86_400_000L))
        val subset = filterCaptureLogs(entries + old, LogTimeFilter.Today, null, now, zone, profile = "a")
        assertEquals(listOf(battle, outside), subset)
        assertEquals(listOf(battle, outside), filterCaptureLogs(entries + old, LogTimeFilter.Today, "2:6510", now, zone, profile = "a"))
        assertTrue(logResources(filterCaptureLogs(entries, LogTimeFilter.Today, null, now, zone, profile = "b")).isEmpty())
        assertTrue("2:6510" in logResources(subset))
    }

    @Test fun profilesUseNewestRecordedNameDeduplicateBothKindsAndFallBackToId() {
        val renamed = CaptureLogItem(battle = battle.battle!!.copy(id = "renamed", accountName = "새 이름", endedAt = now + 1))
        val unnamed = CaptureLogItem(battle = other.battle!!.copy(accountName = " "))
        assertEquals(mapOf("a" to "새 이름", "b" to "#b"), logProfiles(entries.filterNot { it == other } + renamed + unnamed))
    }

    @Test fun missingProfileFallsBackToAllButEmptyTimeResultsDoNotDropTheProfile() {
        val profiles = logProfiles(entries)
        assertEquals("a", availableLogProfile("a", profiles))
        assertNull(availableLogProfile("gone", profiles))
        assertNull(availableLogProfile("a", logProfiles(listOf(other, legacy))))
        assertNull(availableLogProfile(null, profiles))
    }
}
