package io.github.fate_grand_automata.scripts.weekly

/** The one quest step 1 runs: the first quest of the confirmed route, with the count the user confirmed. */
data class WeeklyRouteTarget(
    val questId: Int,
    val chapter: String,
    val spot: String,
    val name: String,
    val ap: Int,
    val count: Int,
)

/** Hands the confirmed route quest from the confirm dialog to the script run, which has its own Hilt component. */
interface IWeeklyRouteSession {
    var target: WeeklyRouteTarget?
}
