package io.github.fate_grand_automata.scripts.weekly

/** Only exclusions persist; every newly delivered mission starts selected. */
object WeeklyMissionSelection {
    fun key(server: String, weekId: Int, missionId: Int) = "$server:$weekId:$missionId"

    fun selected(missions: List<RouteMission>, unselected: Set<String>, server: String, weekId: Int) =
        missions.filter { key(server, weekId, it.id) !in unselected }

    fun prune(unselected: Set<String>, server: String, weekId: Int): Set<String> =
        unselected.filterNot { it.startsWith("$server:") && !it.startsWith("$server:$weekId:") }.toSet()
}
