package io.github.fate_grand_automata.scripts.weekly

import java.io.File

/** The route the user confirmed: quests with their counts (all above zero), the manual missions and the chapter data. */
data class WeeklyRouteRun(
    val server: String,
    val items: List<RouteItem>,
    val manual: List<String>,
    val wars: Map<Int, WarInfo>,
    /** Where downloaded chapter art is cached. */
    val cacheDir: File,
    /** All regional repeatable main-story quest names, including disabled/excluded chapters, never just the chosen route. */
    val questNames: Set<String> = emptySet(),
    /** D-25: only look for each quest's panel (no AP is spent); the panel is never tapped. */
    val probe: Boolean = false,
)

/** Hands the confirmed route from the confirm dialog to the script run, which has its own Hilt component. */
interface IWeeklyRouteSession {
    var run: WeeklyRouteRun?
}
