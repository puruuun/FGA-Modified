package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyRouteTest {
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/weekly/$name")).readText()
    private fun quest(id: Int, ap: Int, traits: Set<Int>, war: Int = 100, after: String = "repeatLast") =
        RouteQuest(id,war,war,"chapter","spot","quest-$id",ap,"free",after,
            enemies=listOf(RouteEnemy(10,1,traits)))

    @Test fun `integer optimum combines quests rather than rounding a fractional or greedy solution`() {
        val missions=listOf(RouteMission(1,"A",3,MissionKind.EnemyTraitAny,setOf(1)),
            RouteMission(2,"B",3,MissionKind.EnemyTraitAny,setOf(2)))
        val quests=listOf(quest(1,3,setOf(1)),quest(2,3,setOf(2)),quest(3,5,setOf(1,2)))
        val plan=WeeklyRouteSolver.solve(missions,quests,emptySet())
        assertEquals(15L,plan.totalAp)
        assertEquals(listOf(3 to 3),plan.items.map { it.quest.id to it.count })
    }

    @Test fun `official Atlas fixture rejects Fuyuki danger zone afterClear close`() {
        val quests=AtlasWeekly.quests(fixture("fuyuki.json"),1792400000)
        assertEquals(listOf(93000005),quests.map { it.id })
        val q=AtlasWeekly.phase(quests.single(),fixture("x-e-phase-3.json"))
        assertEquals(7,q.enemies.size)
        assertTrue(q.enemyDataAvailable)
    }

    @Test fun `unsupported and unachievable missions are manual and Mictlan exclusion is caller supplied`() {
        val missions=listOf(RouteMission(1,"drop",2,MissionKind.Unsupported),
            RouteMission(2,"trait",1,MissionKind.EnemyTraitAny,setOf(1)))
        val plan=WeeklyRouteSolver.solve(missions,listOf(quest(1,1,setOf(1),307),quest(2,1,setOf(1),311)),setOf(307,311))
        assertTrue(plan.items.isEmpty())
        assertEquals(listOf(1,2),plan.manual.map { it.id })
    }

    @Test fun `official current week uses type and time and leaves drops and meta mission manual`() {
        val week=AtlasWeekly.activeWeek(fixture("jp-week-100535.json"),1792400000)
        assertEquals(100535,week.id)
        assertEquals(listOf(2003724,2003729,2003730),week.missions.filter { it.kind==MissionKind.Unsupported }.map { it.id })
        assertEquals(listOf(2003724),week.missions.filter { it.meta }.map { it.id })
        assertFailsWith<IllegalArgumentException> { AtlasWeekly.activeWeek(fixture("jp-week-100535.json"),0) }
    }

    @Test fun `summoned enemies do not contribute and servant class requires servant trait`() {
        val q=quest(1,1,setOf(1000)).copy(enemies=listOf(RouteEnemy(7,1,setOf(1000)),RouteEnemy(8,1,setOf(1000),true),RouteEnemy(9,1,emptySet())))
        assertEquals(1,RouteMission(1,"servant",1,MissionKind.ServantClass,setOf(1)).contribution(q))
        assertEquals(3,RouteMission(1,"class",1,MissionKind.EnemyClass,setOf(1),excludeSummoned=false).contribution(q))
        assertEquals(2,RouteMission(1,"class",1,MissionKind.EnemyClass,setOf(1)).contribution(q))
        assertEquals(1,RouteMission(1,"specific",1,MissionKind.EnemyId,setOf(7)).contribution(q))
    }

    @Test fun `quest traits and quest clear support exact target filters`() {
        val q=quest(1,1,setOf(1)).copy(traits=setOf(22,33))
        assertEquals(1,RouteMission(1,"field",1,MissionKind.QuestTrait,setOf(33)).contribution(q))
        assertEquals(1,RouteMission(1,"clear",1,MissionKind.QuestClear,setOf(1),questTraits=setOf(22)).contribution(q))
        assertEquals(0,RouteMission(1,"clear",1,MissionKind.QuestClear,setOf(2)).contribution(q))
    }

    @Test fun `order and ties are independent of input order`() {
        val missions=listOf(RouteMission(1,"A",1,MissionKind.EnemyTraitAny,setOf(1)),RouteMission(2,"B",1,MissionKind.EnemyTraitAny,setOf(2)))
        val quests=listOf(quest(9,1,setOf(1),201),quest(5,1,setOf(2),100),quest(10,1,setOf(1),201))
        val a=WeeklyRouteSolver.solve(missions,quests,emptySet())
        assertEquals(a,WeeklyRouteSolver.solve(missions.reversed(),quests.reversed(),emptySet()))
        assertEquals(listOf(5,9),a.items.map { it.quest.id })
    }

    @Test fun `resource exhaustion never returns a claimed optimal route`() {
        assertFailsWith<IllegalStateException> {
            WeeklyRouteSolver.solve(listOf(RouteMission(1,"A",5,MissionKind.EnemyTraitAny,setOf(1))),listOf(quest(1,1,setOf(1))),emptySet(),0)
        }
    }

    @Test fun `branch and bound matches brute force on random small instances`() {
        val rnd = java.util.Random(7)
        repeat(200) {
            val nm = 1 + rnd.nextInt(3)
            val missions = (0 until nm).map { RouteMission(it, "m$it", 1 + rnd.nextInt(6), MissionKind.EnemyTraitAny, setOf(it + 1)) }
            val quests = (0 until 5).map { q ->
                RouteQuest(q + 1, 100, 100, "c", "s", "q$q", 1 + rnd.nextInt(9), "free", "repeatLast",
                    enemies = (0 until nm).flatMap { m -> List(rnd.nextInt(4)) { RouteEnemy(1, 1, setOf(m + 1)) } })
            }
            val plan = runCatching { WeeklyRouteSolver.solve(missions, quests, emptySet()) }
            var best = Long.MAX_VALUE
            val usable = missions.filter { m -> quests.any { m.contribution(it) > 0 } }
            fun rec(i: Int, counts: IntArray) {
                if (i == quests.size) {
                    if (usable.all { m -> quests.indices.sumOf { counts[it] * m.contribution(quests[it]) } >= m.target })
                        best = minOf(best, quests.indices.sumOf { counts[it].toLong() * quests[it].ap })
                    return
                }
                for (c in 0..7) { counts[i] = c; rec(i + 1, counts) }
                counts[i] = 0
            }
            if (usable.isEmpty()) assertEquals(0L, plan.getOrThrow().totalAp) else { rec(0, IntArray(quests.size)); assertEquals(best, plan.getOrThrow().totalAp) }
        }
    }

    @Test fun `dataset never offers Mictlan war 311 and drops KR quests missing or with different AP in JP`() {
        fun war(id: Int, quests: String) = """{"id":$id,"eventId":0,"priority":$id,"longName":"w$id","spots":[{"name":"s","quests":[$quests]}]}"""
        fun q(id: Int, ap: Int) = """{"id":$id,"name":"q$id","type":"free","consumeType":"ap","consume":$ap,"afterClear":"repeatLast","phases":[1],"openedAt":0,"closedAt":4102444800}"""
        val jpWar = "[" + war(100, q(1, 5) + "," + q(2, 5) + "," + q(3, 5)) + "," + war(311, q(31101, 20)) + "]"
        val krWar = "[" + war(100, q(1, 5) + "," + q(2, 7) + "," + q(4, 5)) + "," + war(311, q(31101, 20)) + "]"
        fun phase(id: Int, war: Int) = """{"id":$id,"warId":$war,"individuality":[],"phaseIndividuality":[],"stages":[{"enemies":[{"deck":"enemy","svt":{"id":1,"classId":1},"traits":[{"id":5}]}]}]}"""
        val mission = """[{"id":1,"startedAt":0,"endedAt":4102444800,"missions":[{"id":9,"name":"m","type":"weekly","conds":[{"missionProgressType":"clear","condType":"missionConditionDetail","targetNum":1,"targetIds":[],"details":[{"missionCondType":2,"logicType":1,"targetIds":[5],"addTargetIds":[],"conditionLinkType":"masterMissionStart","targetEventIds":[],"targetQuestIndividualities":[]}]}]}]}]"""
        val dir = kotlin.io.path.createTempDirectory("weekly").toFile()
        val log = java.util.Collections.synchronizedList(mutableListOf<String>()) // the loader logs from its worker threads
        val repo = AtlasRouteRepository(dir, fetch = { path, target ->
            target.writeText(when {
                path == "export/KR/nice_master_mission.json" -> mission
                path == "export/KR/nice_war.json" -> krWar
                path == "export/JP/nice_war.json" -> jpWar
                path.startsWith("nice/JP/quest/1/") -> phase(1, 100)
                else -> error("unexpected $path")
            })
        }, log = { log += it })
        val data = repo.load("KR", 1000)
        assertEquals(listOf(1), data.quests.map { it.id })
        assertTrue(data.quests.none { it.warId == 311 })
        assertEquals(listOf(2), data.mismatched)
        assertEquals(listOf(4), data.missing)
        assertTrue(log.any { "quest=2" in it && "apMismatch" in it })
        // The compact dataset is cached per week: a second load needs no network and the raw files are gone.
        val again = AtlasRouteRepository(dir, fetch = { path, _ -> error("unexpected fetch $path") }).load("KR", 1000)
        assertEquals(data.quests, again.quests)
        assertEquals(listOf(2), again.mismatched)
        assertTrue(dir.listFiles().orEmpty().none { "raw" in it.name })
        dir.deleteRecursively()
    }

    @Test fun `current JP and KR weekly conditions map to the expected kinds`() {
        val jp = AtlasWeekly.activeWeek(fixture("jp-week-100535.json"), 1792400000).missions.associateBy { it.id }
        assertEquals(MissionKind.EnemyTraitAll, jp.getValue(2003725).kind)
        assertEquals(setOf(302, 1000), jp.getValue(2003725).ids)
        assertEquals(MissionKind.EnemyTraitAny, jp.getValue(2003728).kind)
        assertEquals(40, jp.getValue(2003728).target)
        val kr = AtlasWeekly.activeWeek(fixture("kr-week-100472.json"), 1792400000).missions.associateBy { it.id }
        assertEquals(100472, AtlasWeekly.activeWeek(fixture("kr-week-100472.json"), 1792400000).id)
        assertEquals(MissionKind.Unsupported, kr.getValue(2003066).kind)
        assertEquals(MissionKind.EnemyTraitAny, kr.getValue(2003069).kind)
        assertEquals(setOf(200), kr.getValue(2003069).ids)
        assertEquals(MissionKind.EnemyTraitAny, kr.getValue(2003070).kind)
        assertEquals(setOf(2113), kr.getValue(2003070).ids)
        assertEquals(MissionKind.QuestClear, kr.getValue(2003071).kind)
        assertEquals(5, kr.getValue(2003071).target)
        assertEquals(10, kr.getValue(2003072).target)
    }
}
