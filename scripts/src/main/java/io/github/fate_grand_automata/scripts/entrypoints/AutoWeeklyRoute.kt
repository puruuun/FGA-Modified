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
        /** D-25: the navigation probe ended. [results] has every selected quest (unreached ones as not run). */
        class Probed(val server: String, val results: List<io.github.fate_grand_automata.scripts.weekly.ProbeResult>, val userStopped: Boolean) : ExitReason()
        class Finished(val results: List<StopResult>, val manual: List<String>, val stop: AutoBattle.ExitException?, val userStopped: Boolean) : ExitReason()
    }

    class ExitException(val reason: ExitReason) : Exception()

    private var probeMode = false
    private fun log(message: String) = messages.log(ScriptLog.WeeklyRoute(if (probeMode) "mode=probe $message" else message))

    override fun script(): Nothing {
        val run = session.run ?: throw ExitException(ExitReason.NoRoute)
        if (run.probe) probe(run)
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

    /** D-25: the same navigation as the route up to the panel verdict. No battle, no AP, no panel tap. */
    private fun probe(run: io.github.fate_grand_automata.scripts.weekly.WeeklyRouteRun): Nothing {
        probeMode = true
        log("start server=${run.server} quests=${run.items.size}")
        if (driver.screen() != RouteScreen.Home) {
            driver.close()
            session.run = null
            throw ExitException(ExitReason.NotOnMainScreen)
        }
        val visits = WeeklyVisitOrder.order(run.server, run.items)
        log("visits " + visits.joinToString(" ") { "${it.warId}:${it.list}[${it.stops.size}]" })
        val machine = WeeklyRouteMachine(driver, run.server, probe = true)
        var userStopped = false
        try {
            machine.run(visits)
        } catch (_: ScriptAbortException) {
            userStopped = true
            machine.interrupt("user stopped")
        } catch (e: Exception) {
            log("stop reason=navigationError detail=${e.message}")
            machine.interrupt("navigation error")
        } finally {
            driver.close()
            session.run = null
        }
        // Every selected quest has one result, in visit order; quests never reached count as not run.
        val ordered = visits.flatMap { it.stops }.map { item ->
            machine.probeResults.lastOrNull { it.quest.id == item.quest.id }
                ?: io.github.fate_grand_automata.scripts.weekly.ProbeResult(item.quest, io.github.fate_grand_automata.scripts.weekly.ProbeStatus.NotRun, "not reached", 0)
        }
        ordered.forEach { log("result quest=${it.quest.id} status=${it.status.csv} ms=${it.elapsedMillis}${if (it.note.isNotEmpty()) " note=${it.note}" else ""}") }
        throw ExitException(ExitReason.Probed(run.server, ordered, userStopped))
    }

    /** Every confirmed quest has exactly one result, in route order; quests the machine did not reach count as not run. */
    private fun completed(items: List<io.github.fate_grand_automata.scripts.weekly.RouteItem>, results: List<StopResult>): List<StopResult> =
        items.map { item -> results.lastOrNull { it.item.quest.id == item.quest.id } ?: StopResult(item, 0, StopStatus.NotRun, "not reached") }

    override fun pausedStatus(): Exception? = battle.pausedStatus()
}
