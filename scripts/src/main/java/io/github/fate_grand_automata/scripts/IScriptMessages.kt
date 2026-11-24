package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.ChainTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.CardColourCounts
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ParsedCard
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
    class CommandStartWaiting(val servant: Int, val count: Int, val required: Int, val colours: CardColourCounts) : ScriptNotify()
    class CommandStarted(val stage: Int, val turn: Int, val profileIndex: Int = 0, val colours: CardColourCounts) : ScriptNotify()
    class CommandStartWaitingBoth(
        val firstServant: Int, val firstCount: Int, val firstRequired: Int,
        val secondServant: Int, val secondCount: Int, val secondRequired: Int,
        val firstColours: CardColourCounts, val secondColours: CardColourCounts
    ) : ScriptNotify()
    /** Waiting status for three start targets, in check order. */
    class CommandStartWaitingMany(val targets: List<Target>) : ScriptNotify() {
        class Target(val servant: Int, val count: Int, val required: Int, val colours: CardColourCounts)
    }
    class CommandStartSkipped(val waitingTurns: Int) : ScriptNotify()
}

sealed class ScriptLog {
    class CaptureMode(val enabled: Boolean) : ScriptLog()
    class ShuffleUntil(val message: String) : ScriptLog()
    /** One line at the start of a run: whether servant face cards are read, and why. */
    class FaceCardCheck(val message: String) : ScriptLog()
    /** One Grand CE decision; extra template scans are enabled only for beta/deviceTest. */
    class SupportCe(val message: String, val np: io.github.lib_automata.Match?,
                    val default: io.github.lib_automata.Match?, val matched: Boolean) : ScriptLog()
    class FriendRequest(val sent: Boolean, val fallbackReject: Boolean) : ScriptLog()
    /** Beta-only per-frame bond evidence and final agreement. */
    class Bond(val message: String) : ScriptLog()
    /** A line for `adb logcat -s FpSell`: one per evaluated sell tile, plus the stop step and reason. */
    class FpSell(val message: String) : ScriptLog()
    /** A line for `adb logcat -s SkillLevel`: the start, one per slot decision and the stop. */
    class SkillLevel(val message: String) : ScriptLog()
    /** A line for `adb logcat -s WeeklyRoute`: the route calculation, the panel check and the stop reason. */
    class WeeklyRoute(val message: String) : ScriptLog()
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
    class DirectCardsFallback(val requested: String, val cards: List<CommandCard>) : ScriptLog()
    class ClickingCards(val cards: Iterable<CommandCard.Face>) : ScriptLog()
    class NPsGroupedByFaceCards(val groups: Map<CommandCard.NP, List<CommandCard.Face>>) : ScriptLog()
    class SupportFaceCardGroup(val group: List<CommandCard.Face>) : ScriptLog()
    class FaceCardGroups(val groups: List<List<CommandCard.Face>>) : ScriptLog()
    class ServantEnteredSlot(
        val servant: TeamSlot,
        val slot: FieldSlot
    ) : ScriptLog()

    /** Whether the support face is used to assign cards whose SUPPORT tag was not found. */
    class SupportFaceDecision(
        val support: TeamSlot,
        val usable: Boolean,
        val sameFaceServant: TeamSlot?,
        val bestOwnedScore: Double?,
        val ownedScores: List<Pair<TeamSlot, Double>> = emptyList()
    ) : ScriptLog()

    /** One command card as read for the servant grouping: tag score, face scores and the result. */
    class FaceCardReading(
        val card: CommandCard.Face,
        val tagScore: Double?,
        val faceScores: Map<TeamSlot, Double>,
        val servant: TeamSlot?
    )

    /** Why these command cards were picked this turn: the priority entry, the read cards, the sort and the pick. */
    class CardDecision(
        val stage: Int,
        val turn: Int,
        val commandStage: Int,
        val commandTurn: Int,
        val profile: Int,
        val entry: Int,
        val style: String,
        val servantPriority: Boolean,
        val cards: List<ParsedCard>,
        val sorted: List<CommandCard.Face>,
        val picked: List<CommandCard>,
        val direct: String?,
        /** Only on an entry with Chain Priority on: the chain the attack makes and the order that was tried. */
        val chain: ChainDecision? = null
    ) : ScriptLog()

    class ChainDecision(
        /** Chain formed by the three cards played, or null when they form none or a type is unknown. */
        val formed: ChainTypeEnum?,
        val order: List<ChainTypeEnum>,
        /** NP card types used for planning. */
        val npTypes: Map<FieldSlot, CardTypeEnum>
    )

    /**
     * The NP card type read in a Servant's details dialog.
     *
     * @param type The best type at or above [minSimilarity], or null when none is.
     * @param scores Best match score per type.
     * @param turnsText OCR text of the banner's remaining-turns digit, read only when no type matched after a skill.
     */
    class NpCardTypeReading(
        val servant: TeamSlot,
        val type: CardTypeEnum?,
        val scores: Map<CardTypeEnum, Double>,
        val minSimilarity: Double,
        val reason: String,
        val turnsText: String? = null,
        val turns: Int? = null
    ) : ScriptLog()

    class FaceCardReadings(
        val readings: List<FaceCardReading>,
        val support: TeamSlot?
    ) : ScriptLog()

    class CardsBelongToServant(
        val cards: Iterable<CommandCard.Face>,
        val servant: TeamSlot,
        val isSupport: Boolean = false
    ) : ScriptLog()
}

interface IScriptMessages {
    val supportCeDiagnosticsEnabled: Boolean get() = false

    fun notify(action: ScriptNotify)

    fun log(item: ScriptLog)
}
