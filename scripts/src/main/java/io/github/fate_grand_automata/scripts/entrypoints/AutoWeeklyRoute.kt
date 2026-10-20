package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.modules.PanelVerdict
import io.github.fate_grand_automata.scripts.modules.QuestPanelMatch
import io.github.fate_grand_automata.scripts.modules.QuestPanelReader
import io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteTarget
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Weekly mission route, step 1: the user has opened the quest panel of the first route quest. The panel must show that
 * quest (free quest emblem, AP, name width); only then the ordinary battle script runs with the confirmed repeat count.
 * Nothing is tapped before the check, so a wrong panel can never start a quest.
 */
@ScriptScope
class AutoWeeklyRoute @Inject constructor(
    exitManager: ExitManager,
    api: IFgoAutomataApi,
    private val session: IWeeklyRouteSession,
    private val reader: QuestPanelReader,
    private val battle: AutoBattle
) : EntryPoint(exitManager), IFgoAutomataApi by api {
    sealed class ExitReason {
        data object NoTarget : ExitReason()
        data object NotOnPanel : ExitReason()
        data object NotFreeQuest : ExitReason()
        class ApUnreadable(val expected: Int) : ExitReason()
        class ApMismatch(val read: Int, val expected: Int) : ExitReason()
        class NameMismatch(val cells: Double, val expected: Double) : ExitReason()
    }

    class ExitException(val reason: ExitReason, val target: WeeklyRouteTarget?) : Exception()

    private fun log(message: String) = messages.log(ScriptLog.WeeklyRoute(message))

    override fun script(): Nothing {
        val target = session.target ?: throw ExitException(ExitReason.NoTarget, null)
        log("start quest=${target.questId} name=${target.name} ap=${target.ap} count=${target.count}")

        val server = prefs.gameServer
        val panel = reader.read(server)
        val verdict = QuestPanelMatch.verdict(panel, target.ap, target.name, server)
        log("panel free=${panel.free} ap=${panel.ap} nameWidthPx=${panel.nameWidthPx} nameCells=${panel.nameCells} verdict=$verdict")

        val reason = when (verdict) {
            PanelVerdict.Match -> null
            PanelVerdict.NoPanel -> ExitReason.NotOnPanel
            PanelVerdict.NotFree -> ExitReason.NotFreeQuest
            is PanelVerdict.ApUnreadable -> ExitReason.ApUnreadable(verdict.expected)
            is PanelVerdict.ApMismatch -> ExitReason.ApMismatch(verdict.read, verdict.expected)
            is PanelVerdict.NameMismatch -> ExitReason.NameMismatch(verdict.cells, verdict.expected)
        }
        if (reason != null) {
            log("stop reason=${reason::class.simpleName}")
            throw ExitException(reason, target)
        }

        log("battle start runs=${target.count}")
        battle.script()
    }

    override fun pausedStatus(): Exception? = battle.pausedStatus()
}
