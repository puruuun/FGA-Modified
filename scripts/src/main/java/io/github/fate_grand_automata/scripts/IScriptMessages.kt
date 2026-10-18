package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.TeamSlot
import kotlin.time.Duration

sealed class ScriptNotify {
    data object CEGet : ScriptNotify()
    data object CEDropped : ScriptNotify()
    class WaitForAPRegen(val minutes: Int = 1) : ScriptNotify()
    class FailedToDetermineCards(val cards: List<CommandCard.Face>,
                                 val unknownCardTypes: Boolean, val unknownServants: Boolean) : ScriptNotify()
    class SupportListUpdatingIn(val time: Duration) : ScriptNotify()
    data object GrandScoreNotSupported : ScriptNotify()
    class BetweenRuns(val refills: Int, val runs: Int, val ceDrops: Int) : ScriptNotify()
    data object BondLevelUp : ScriptNotify()
    class CommandStartWaiting(val servant: Int, val count: Int, val required: Int) : ScriptNotify()
    class CommandStarted(val stage: Int, val turn: Int, val profileIndex: Int = 0) : ScriptNotify()
    class CommandStartWaitingBoth(
        val firstServant: Int, val firstCount: Int, val firstRequired: Int,
        val secondServant: Int, val secondCount: Int, val secondRequired: Int
    ) : ScriptNotify()
    class CommandStartSkipped(val waitingTurns: Int) : ScriptNotify()
}

sealed class ScriptLog {
    class FpSellSelection(val summary: io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Summary) : ScriptLog()
    object DefaultSupportBounds : ScriptLog()
    object DefaultMasterOffset : ScriptLog()
    class CurrentParty(val party: Int?) : ScriptLog()
    class MaxSkills(
        val needMaxedSkills: List<Boolean>,
        val isSkillMaxed: List<Boolean>
    ) : ScriptLog()
    class MaxAppendSkills(val required: List<Boolean>, val detected: List<Boolean>) : ScriptLog()
    class SupportScore62(val matched: Boolean) : ScriptLog()
    class SupportScoreConditions(
        val classScore: Int?, val grandScore: Int?, val checkClass: Boolean,
        val checkGrand: Boolean, val display: String, val matched: Boolean
    ) : ScriptLog()
    class SupportNpLevel(val required: Int, val detected: Int?, val matched: Boolean) : ScriptLog()
    class SupportDiagnosticsLimit(val limit: Int) : ScriptLog()
    class GrandScoreIgnored(val server: String) : ScriptLog()
    class SupportNoblePhantasm(val matched: Boolean, val bestScore: Double?) : ScriptLog()

    class ClickingNPs(val nps: Iterable<CommandCard.NP>) : ScriptLog()
    class ClickingCards(val cards: Iterable<CommandCard.Face>) : ScriptLog()
    class NPsGroupedByFaceCards(val groups: Map<CommandCard.NP, List<CommandCard.Face>>) : ScriptLog()
    class SupportFaceCardGroup(val group: List<CommandCard.Face>) : ScriptLog()
    class FaceCardGroups(val groups: List<List<CommandCard.Face>>) : ScriptLog()
    class ServantEnteredSlot(
        val servant: TeamSlot,
        val slot: FieldSlot
    ) : ScriptLog()

    class CardsBelongToServant(
        val cards: Iterable<CommandCard.Face>,
        val servant: TeamSlot,
        val isSupport: Boolean = false
    ) : ScriptLog()
}

interface IScriptMessages {
    fun notify(action: ScriptNotify)

    fun log(item: ScriptLog)
}
