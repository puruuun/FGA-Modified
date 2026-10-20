package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyProbeTest {
    private fun quest(id: Int, war: Int = 100, map: Int = 1, type: String = "free", ap: Int = 5) =
        RouteQuest(id, war, war, "chapter $war", "spot", "name $id", ap, type, "repeatLast", spotId = id, mapId = map)
    private fun item(id: Int, war: Int = 100) = RouteItem(quest(id, war), 1)

    /** Fails the test on every path that would tap a quest panel, start a battle or deal with a repeat dialog. */
    private class Fake : RouteDriver {
        val actions = mutableListOf<String>()
        var problems = mapOf<Int, String>()
        var clock = 0L
        override fun log(message: String) {}
        override fun screen() = RouteScreen.Home
        override fun openList(list: ChapterList) = true.also { actions += "list:$list" }
        override fun openChapter(warId: Int) = true.also { actions += "chapter:$warId" }
        override fun closeList() = true.also { actions += "home" }
        override fun terminal() = true.also { actions += "terminal" }
        override fun closePanel() = true.also { actions += "closePanel" }
        override fun prepareMap(warId: Int) = true.also { actions += "zoom:$warId" }
        override fun openSpot(quest: RouteQuest): SpotOutcome = fail("probe mode must never take the panel-tap path")
        override fun battle(quest: RouteQuest, count: Int): BattleOutcome = fail("probe mode must never start a battle or a repeat dialog")
        override fun probeSpot(quest: RouteQuest): SpotOutcome {
            actions += "probe:${quest.id}"
            clock += 1000
            return problems[quest.id]?.let { SpotOutcome.NotFound(it) } ?: SpotOutcome.Started
        }
    }

    private fun probe(f: Fake, items: List<RouteItem>): WeeklyRouteMachine {
        val machine = WeeklyRouteMachine(f, "JP", probe = true, now = { f.clock })
        machine.run(WeeklyVisitOrder.order("JP", items))
        return machine
    }

    @Test fun `probe never taps the panel or battles and closes each panel`() {
        val f = Fake()
        val machine = probe(f, listOf(item(2), item(1), item(3, 101)))
        assertEquals(listOf(1, 2, 3), machine.probeResults.map { it.quest.id })
        assertTrue(machine.probeResults.all { it.status == ProbeStatus.Success && it.elapsedMillis == 1000L })
        assertEquals(3, f.actions.count { it == "closePanel" })
    }

    @Test fun `failures are classified and the next quest still runs`() {
        val f = Fake().apply { problems = mapOf(1 to "no panel at the spot", 2 to "no free panel with the AP and name", 3 to "map not recognized") }
        val machine = probe(f, listOf(item(1), item(2), item(3), item(4)))
        assertEquals(
            listOf(ProbeStatus.NoPanel, ProbeStatus.Mismatch, ProbeStatus.MapFitFailed, ProbeStatus.Success),
            machine.probeResults.map { it.status }
        )
        assertEquals(1, f.actions.count { it == "closePanel" }) // only after the success; failed probes leave the map clean
    }

    @Test fun `missing chapter marks its quests as chapter not found`() {
        val f = object : RouteDriver by Fake() {
            override fun openChapter(warId: Int) = warId != 100
            override fun probeSpot(quest: RouteQuest): SpotOutcome = SpotOutcome.Started
            override fun closePanel() = true
        }
        val machine = WeeklyRouteMachine(f, "JP", probe = true)
        machine.run(WeeklyVisitOrder.order("JP", listOf(item(1), item(2), item(3, 101))))
        assertEquals(listOf(ProbeStatus.ChapterNotFound, ProbeStatus.ChapterNotFound, ProbeStatus.Success), machine.probeResults.map { it.status })
    }

    @Test fun `zoom failure counts as a map fit failure`() {
        val f = object : RouteDriver by Fake() { override fun prepareMap(warId: Int) = false }
        val machine = WeeklyRouteMachine(f, "JP", probe = true)
        machine.run(WeeklyVisitOrder.order("JP", listOf(item(1))))
        assertEquals(ProbeStatus.MapFitFailed, machine.probeResults.single().status)
    }

    @Test fun `interrupt keeps the finished results and marks the active quest not run`() {
        val f = object : RouteDriver by Fake() {
            override fun probeSpot(quest: RouteQuest): SpotOutcome =
                if (quest.id == 2) throw IllegalStateException("stop") else SpotOutcome.Started
        }
        val machine = WeeklyRouteMachine(f, "JP", probe = true)
        runCatching { machine.run(WeeklyVisitOrder.order("JP", listOf(item(1), item(2), item(3)))) }
        machine.interrupt("user stopped")
        assertEquals(listOf(ProbeStatus.Success, ProbeStatus.NotRun), machine.probeResults.map { it.status })
    }

    @Test fun `selection excludes Mictlan and non free quests and follows the scope`() {
        val quests = listOf(quest(1, 100), quest(2, 101), quest(3, 311), quest(4, 100, type = "main"), quest(5, 100, ap = 0))
        fun ids(scope: ProbeScope, chapters: Set<Int> = emptySet(), disabled: Set<String> = emptySet()) =
            WeeklyProbe.select("JP", quests, scope, chapters, disabled).map { it.quest.id }
        assertEquals(listOf(1, 2), ids(ProbeScope.All))
        assertEquals(listOf(2), ids(ProbeScope.Chapters, setOf(101)))
        assertEquals(listOf(2), ids(ProbeScope.Enabled, disabled = setOf(WeeklyCandidateSettings.questKey("JP", 1))))
        assertEquals(listOf(1), ids(ProbeScope.Enabled, disabled = setOf(WeeklyCandidateSettings.chapterKey("JP", 101))))
    }

    @Test fun `csv escapes text fields`() {
        val results = listOf(ProbeResult(quest(1).copy(name = "a,\"b\""), ProbeStatus.NoPanel, "note", 1500))
        val lines = WeeklyProbe.csv(results).trim().lines()
        assertEquals(WeeklyProbe.CSV_HEADER, lines[0])
        assertEquals("1,100,chapter 100,spot,\"a,\"\"b\"\"\",5,no_panel,1500,note", lines[1])
    }
}
