package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession
import io.github.fate_grand_automata.scripts.weekly.RouteScreen
import io.github.fate_grand_automata.scripts.weekly.StopResult
import io.github.fate_grand_automata.scripts.weekly.StopStatus
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteMachine
import io.github.fate_grand_automata.scripts.weekly.WeeklyVisitOrder
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Weekly mission route, step 2 (design 0009): from the main screen, visit each chapter of the confirmed route, find
 * each quest's spot on the zoomed-out map and run the quest with the ordinary battle flow. Quests whose chapter, map
 * or panel cannot be found are skipped and listed in the summary. A battle that stops for any reason other than its
 * run limit (AP, defeat, user stop) ends the route.
 */
@ScriptScope
class AutoWeeklyRoute @Inject constructor(
    exitManager: ExitManager,
    api: IFgoAutomataApi,
    private val session: IWeeklyRouteSession,
    private val driver: WeeklyRouteDriver,
    private val battle: AutoBattle
) : EntryPoint(exitManager), IFgoAutomataApi by api {
    sealed class ExitReason {
        data object NoRoute : ExitReason()
        data object NotOnMainScreen : ExitReason()
        /** The route ended: [results] has every quest, [stop] is the battle exit when a battle ended it. */
        class Finished(val results: List<StopResult>, val manual: List<String>, val stop: AutoBattle.ExitException?, val userStopped: Boolean) : ExitReason()
    }

    class ExitException(val reason: ExitReason) : Exception()

    private fun log(message: String) = messages.log(ScriptLog.WeeklyRoute(message))

    override fun script(): Nothing {
        val run = session.run ?: throw ExitException(ExitReason.NoRoute)
        log("start server=${run.server} quests=${run.items.size} apples=${prefs.selectedServerConfigPref.currentAppleCount}")

        val screen = driver.screen()
        log("start screen=$screen")
        if (screen != RouteScreen.Home) {
            driver.close()
            throw ExitException(ExitReason.NotOnMainScreen)
        }

        val visits = WeeklyVisitOrder.order(run.server, run.items)
        log("visits " + visits.joinToString(" ") { "${it.warId}:${it.list}[${it.stops.size}]" })
        val machine = WeeklyRouteMachine(driver, run.server)
        var userStopped = false
        val limits = prefs.selectedServerConfigPref
        val oldLimit = limits.limitRuns
        val oldShouldLimit = limits.shouldLimitRuns
        try {
            machine.run(visits)
        } catch (_: ScriptAbortException) {
            userStopped = true
            machine.interrupt("user stopped")
        } catch (e: Exception) {
            log("stop reason=navigationError detail=${e.message}")
            machine.interrupt("navigation error")
        } finally {
            try { battle.finishRun(updateRunLimit = false) } finally {
                limits.limitRuns = oldLimit
                limits.shouldLimitRuns = oldShouldLimit
                driver.close()
                session.run = null
            }
        }

        val results = completed(run.items, machine.results)
        results.forEach { log("result quest=${it.item.quest.id} planned=${it.item.count} done=${it.done} status=${it.status}${if (it.note.isNotEmpty()) " note=${it.note}" else ""}") }
        val stop = driver.battleStop?.takeIf { it.reason !is AutoBattle.ExitReason.Abort }
        throw ExitException(ExitReason.Finished(results, run.manual, stop, userStopped || driver.battleStop?.reason is AutoBattle.ExitReason.Abort))
    }

    /** Every confirmed quest has exactly one result, in route order; quests the machine did not reach count as not run. */
    private fun completed(items: List<io.github.fate_grand_automata.scripts.weekly.RouteItem>, results: List<StopResult>): List<StopResult> =
        items.map { item -> results.lastOrNull { it.item.quest.id == item.quest.id } ?: StopResult(item, 0, StopStatus.NotRun, "not reached") }

    override fun pausedStatus(): Exception? = battle.pausedStatus()
}
