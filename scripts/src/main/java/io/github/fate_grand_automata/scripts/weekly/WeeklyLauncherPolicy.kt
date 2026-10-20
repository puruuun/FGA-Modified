package io.github.fate_grand_automata.scripts.weekly

/** D-14: down the content means finger UP. Shared by home and every chapter list. */
enum class ListSearchDirection { Down, Up;
    fun fingerY(start: Int, end: Int): Pair<Int, Int> =
        if (this == Down) maxOf(start, end) to minOf(start, end) else minOf(start, end) to maxOf(start, end)
}

/** Only explicit exclusions are stored. New chapters and quests therefore default to enabled. */
object WeeklyCandidateSettings {
    fun chapterKey(server: String, war: Int) = "$server:war:$war"
    fun questKey(server: String, quest: Int) = "$server:quest:$quest"
    fun candidates(server: String, quests: List<RouteQuest>, disabled: Set<String>) = quests.filter {
        it.warId != WeeklyRouteSolver.MICTLAN_WAR &&
            chapterKey(server, it.warId) !in disabled && questKey(server, it.id) !in disabled
    }
}

object WeeklyLauncherPolicy {
    fun weekly(panelOpen: Boolean, positiveRouteCounts: List<Int>, probe: Boolean = false): Boolean =
        panelOpen && (probe || positiveRouteCounts.any { it > 0 })
}
