package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.*
import kotlin.test.*

class LogSourceFilterTest {
    private val battle = CaptureLogItem(battle = battleLogFixture())
    private fun outside(vararg sources: ChangeSource) = CaptureLogItem(outside = outsideFixture().copy(
        period = sources.fold(OutsidePeriod("fictionalA", 1)) { period, source ->
            period.add(source, AccountDelta(qp = 1), 2)
        }))
    private fun filter(entries: List<CaptureLogItem>, source: LogSourceFilter?) =
        filterCaptureLogs(entries, LogTimeFilter.All, null, System.currentTimeMillis(), source = source)

    @Test fun everyBattleMatchesOnlyScriptEvenWithoutCollection() {
        LogSourceFilter.entries.forEach { source ->
            assertEquals(if (source == LogSourceFilter.Script) listOf(battle) else emptyList(), filter(listOf(battle), source))
        }
    }

    @Test fun allGroupsMapAndScriptFlagOverridesEveryGroup() {
        ChangeSource.Group.entries.forEach { group ->
            val manual = ChangeSource(group)
            assertEquals(group.name, manual.logSource().name)
            val entry = outside(manual)
            LogSourceFilter.entries.forEach { source ->
                assertEquals(if (source == manual.logSource()) listOf(entry) else emptyList(), filter(listOf(entry), source))
            }
            val scripted = outside(manual.copy(script = true))
            assertEquals(listOf(scripted), filter(listOf(scripted), LogSourceFilter.Script))
            assertTrue(filter(listOf(scripted), manual.logSource()).isEmpty())
        }
    }

    @Test fun anySourceMatchesAndAllPreservesEveryEntryAndOrder() {
        val mixed = outside(ChangeSource(ChangeSource.Group.Shop), ChangeSource(ChangeSource.Group.Summon, script = true),
            ChangeSource(ChangeSource.Group.Missions))
        val all = listOf(battle, mixed, outside(ChangeSource(ChangeSource.Group.Lottery)))
        assertEquals(all, filter(all, null))
        assertEquals(listOf(battle, mixed), filter(all, LogSourceFilter.Script))
        assertEquals(listOf(mixed), filter(all, LogSourceFilter.Missions))
        assertEquals(listOf(mixed), filter(all, LogSourceFilter.Shop))
        assertTrue(filter(all, LogSourceFilter.Summon).isEmpty())
    }

    @Test fun matchingSourcesLeadStablyWithoutRemovingOtherDetails() {
        val entry = outside(ChangeSource(ChangeSource.Group.Shop), ChangeSource(ChangeSource.Group.Summon, script = true),
            ChangeSource(ChangeSource.Group.Lottery, script = true), ChangeSource(ChangeSource.Group.Missions))
        val sources = entry.outside!!.period.sources
        assertEquals(listOf(sources[1], sources[2], sources[0], sources[3]), orderedLogSources(sources, LogSourceFilter.Script))
        assertEquals(listOf(sources[3], sources[0], sources[1], sources[2]), orderedLogSources(sources, LogSourceFilter.Missions))
        assertEquals(sources, orderedLogSources(sources, null))
    }

    @Test fun sourceCombinesWithDateProfileAndResource() {
        val entry = outside(ChangeSource(ChangeSource.Group.Shop)).let { it.copy(outside = it.outside!!.copy(endedAt = 1000)) }
        assertTrue(filterCaptureLogs(listOf(entry), LogTimeFilter.Today, null, System.currentTimeMillis(), source = LogSourceFilter.Shop).isEmpty())
        assertTrue(filterCaptureLogs(listOf(entry), LogTimeFilter.All, null, 1000, profile = "other", source = LogSourceFilter.Shop).isEmpty())
        assertTrue(filterCaptureLogs(listOf(entry), LogTimeFilter.All, "2:4", 1000, source = LogSourceFilter.Shop).isEmpty())
        assertEquals(listOf(entry), filterCaptureLogs(listOf(entry), LogTimeFilter.All, "2:1", 1000, source = LogSourceFilter.Shop))
    }
}
