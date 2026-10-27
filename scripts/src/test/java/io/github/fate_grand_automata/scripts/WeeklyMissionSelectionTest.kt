package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyMissionSelectionTest {
    private fun mission(id: Int, kind: MissionKind = MissionKind.QuestClear) =
        RouteMission(id, "mission $id", 1, kind, setOf(id))
    private fun quest(id: Int) = RouteQuest(id, 100, 1, "chapter", "spot", "quest $id", 3, "free", "repeatLast")

    @Test fun `key round trip preserves server week and mission identity`() {
        val key = WeeklyMissionSelection.key("JP", 100473, 123)
        assertEquals("JP:100473:123", key)
        val (server, week, mission) = key.split(':')
        assertEquals(key, WeeklyMissionSelection.key(server, week.toInt(), mission.toInt()))
    }

    @Test fun `only this server and week exclusions filter missions and new missions default on`() {
        val missions = listOf(mission(1), mission(2), mission(3))
        val excluded = setOf("JP:100473:1", "KR:100473:2", "JP:100472:3")
        assertEquals(listOf(2, 3), WeeklyMissionSelection.selected(missions, excluded, "JP", 100473).map { it.id })
        assertEquals(missions, WeeklyMissionSelection.selected(missions, emptySet(), "JP", 100473))
    }

    @Test fun `prune keeps this week and all other servers`() {
        val current = "JP:100473:1"
        val other = "KR:100472:2"
        assertEquals(setOf(current, other), WeeklyMissionSelection.prune(setOf(current, other, "JP:100472:2"), "JP", 100473))
    }

    @Test fun `unchecked mission only useful quest disappears from plan`() {
        val missions = listOf(mission(1), mission(2))
        val selected = WeeklyMissionSelection.selected(missions, setOf("JP:1:2"), "JP", 1)
        val all = WeeklyRouteSolver.solve(missions, listOf(quest(1), quest(2)), emptySet())
        assertEquals(setOf(1, 2), all.items.map { it.quest.id }.toSet())
        val plan = WeeklyRouteSolver.solve(selected, listOf(quest(1), quest(2)), emptySet())
        assertEquals(listOf(1), plan.items.map { it.quest.id })
    }

    @Test fun `unchecked manual mission leaves manual summary and all unchecked gives empty route`() {
        val missions = listOf(mission(1), mission(2, MissionKind.Unsupported))
        val selected = WeeklyMissionSelection.selected(missions, setOf("KR:1:2"), "KR", 1)
        assertTrue(WeeklyRouteSolver.solve(selected, listOf(quest(1)), emptySet()).manual.isEmpty())
        val empty = WeeklyMissionSelection.selected(missions, setOf("KR:1:1", "KR:1:2"), "KR", 1)
        val plan = WeeklyRouteSolver.solve(empty, listOf(quest(1)), emptySet())
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.manual.isEmpty())
        assertFalse(WeeklyLauncherPolicy.weekly(true, plan.items.map { it.count }))
    }
}
