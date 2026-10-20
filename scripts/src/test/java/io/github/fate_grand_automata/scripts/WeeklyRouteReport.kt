package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import java.io.File

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(args.getOrNull(1) ?: "build/weekly-report-utf8.txt"), true, "UTF-8"))
    val repository = AtlasRouteRepository(File(args[0]), log={ println("WeeklyRoute $it") })
    for (server in listOf("JP","KR")) {
        val data = repository.load(server)
        val start = System.nanoTime()
        val route = WeeklyRouteSolver.solve(data.week.missions,data.quests,setOf(311))
        println("REPORT server=$server week=${data.week.id} downloadMs=${data.downloadMillis} solveMs=${(System.nanoTime()-start)/1_000_000} totalAP=${route.totalAp}")
        data.week.missions.forEach { println("MISSION ${it.id} ${it.name}") }
        route.items.forEach { println("ROUTE ${it.quest.id} ${it.quest.chapter.replace('\n',' ')} / ${it.quest.spot} / ${it.quest.name} ap=${it.quest.ap} count=${it.count}") }
        route.manual.forEach { println("MANUAL ${it.id} ${it.name}") }
        data.week.missions.filter { !it.meta && it !in route.manual }.forEach { m ->
            println("COVER ${m.id} target=${m.target} got=${route.items.sumOf { it.count * m.contribution(it.quest) }} ${m.name}")
        }
        println("DROPPED mismatched=${data.mismatched} missing=${data.missing}")
    }
}

/** Worst-case style synthetic week (many overlapping enemy-count missions) to check solver headroom. */
object WeeklyRouteStress {
    @JvmStatic fun main(args: Array<String>) {
        val data = AtlasRouteRepository(File(args[0])).load("JP")
        val m = listOf(
            RouteMission(1,"sq",15,MissionKind.EnemyClass,setOf(1,4)),
            RouteMission(2,"ac",15,MissionKind.EnemyClass,setOf(2,5)),
            RouteMission(3,"lab",15,MissionKind.EnemyClass,setOf(3,6,7)),
            RouteMission(4,"earth",15,MissionKind.EnemyTraitAny,setOf(201)),
            RouteMission(5,"demon",15,MissionKind.EnemyTraitAny,setOf(2019)),
            RouteMission(6,"n",3,MissionKind.EnemyTraitAll,setOf(302,1000)),
            RouteMission(7,"g",3,MissionKind.EnemyTraitAll,setOf(303,1000)),
            RouteMission(8,"e20",20,MissionKind.EnemyTraitAny,setOf(5010,1000)),
            RouteMission(9,"e40",40,MissionKind.EnemyTraitAny,setOf(5010,1000)),
            RouteMission(10,"clear",10,MissionKind.QuestClear,setOf(0)))
        val t = System.nanoTime()
        val r = runCatching { WeeklyRouteSolver.solve(m,data.quests,setOf(311)) }
        println("STRESS ms=${(System.nanoTime()-t)/1_000_000} result=${r.map { it.totalAp }}")
        r.getOrNull()?.items?.forEach { println("  ${it.quest.id} ap=${it.quest.ap} x${it.count}") }
    }
}
