package io.github.fate_grand_automata.scripts.weekly

/** Only exclusions persist; every newly delivered mission starts selected. */
object WeeklyMissionSelection {
    fun key(server: String, weekId: Int, missionId: Int) = "$server:$weekId:$missionId"

    fun manualIds(missions: List<RouteMission>, quests: List<RouteQuest>): Set<Int> {
        val candidates = quests.filter { it.repeatable(WeeklyRouteSolver.EXCLUDED_WARS) }
        return missions.filter { !it.meta && (it.kind == MissionKind.Unsupported || candidates.none { q -> it.contribution(q) > 0 }) }
            .map { it.id }.toSet()
    }

    fun selected(missions: List<RouteMission>, unselected: Set<String>, server: String, weekId: Int,
                 manualIds: Set<Int> = missions.filter { it.kind == MissionKind.Unsupported }.map { it.id }.toSet()) =
        missions.filter { it.id in manualIds || key(server, weekId, it.id) !in unselected }

    fun prune(unselected: Set<String>, server: String, weekId: Int): Set<String> =
        unselected.filterNot { it.startsWith("$server:") && !it.startsWith("$server:$weekId:") }.toSet()
}
