package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode
import io.github.fate_grand_automata.scripts.models.CardColourCounts
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** The gate latches once per quest; only actual battle counters advance while waiting. */
@ScriptScope
class CommandStartGate @Inject constructor(config: IBattleConfig) {
    val mode = config.commandStartMode
    val target = TeamSlot.list[config.commandStartServant.coerceIn(1..3) - 1]
    val secondaryEnabled = mode == CommandStartMode.CardCount && config.secondaryStartEnabled
    val secondaryTarget = if (secondaryEnabled) TeamSlot.list[config.secondaryStartServant.coerceIn(1..3) - 1] else null
    val hasDuplicateTargets get() = secondaryEnabled && target == secondaryTarget
    private val primaryColours = CardColourCounts(
        config.commandStartBuster.coerceIn(0..5), config.commandStartArts.coerceIn(0..5), config.commandStartQuick.coerceIn(0..5)
    )
    /** Count 0 means the Any row is unused; with no colour either, the gate falls back to Any 1. */
    val requiredCards = effectiveCount(config.commandStartCardCount, primaryColours)
    private val primaryCombineMode = config.commandStartCombineMode
    private val secondaryColours = if (secondaryEnabled) CardColourCounts(
        config.secondaryStartBuster.coerceIn(0..5), config.secondaryStartArts.coerceIn(0..5), config.secondaryStartQuick.coerceIn(0..5)
    ) else CardColourCounts()
    val secondaryRequired = if (secondaryEnabled) effectiveCount(config.secondaryStartCardCount, secondaryColours) else 0
    private val secondaryCombineMode = if (secondaryEnabled) config.secondaryStartCombineMode else CommandStartCombineMode.And
    private val maxWaitingTurns = config.commandStartMaxTurns.coerceAtLeast(0)

    private var start: CommandPosition? = null
    private var startTotalTurns = 0
    var waitingTurns = 0
        private set
    var selectedProfile: Int? = null
        private set

    val isWaiting get() = mode == CommandStartMode.CardCount && start == null

    sealed class Result {
        data class Ready(val count: Int, val profileIndex: Int = 0, val colours: CardColourCounts = CardColourCounts()) : Result()
        data class Wait(
            val count: Int, val secondaryCount: Int? = null,
            val colours: CardColourCounts = CardColourCounts(), val secondaryColours: CardColourCounts? = null
        ) : Result()
        data object RecognitionFailed : Result()
        data object TargetUnavailable : Result()
        data object WaitLimitReached : Result()
        data object DuplicateTargets : Result()
    }

    fun check(cards: List<ParsedCard>, deployed: Collection<TeamSlot>): Result {
        check(isWaiting)
        if (hasDuplicateTargets) return Result.DuplicateTargets
        if (!hasAvailableTarget(deployed)) return Result.TargetUnavailable

        // Stunned cards intentionally have an unknown type and are not usable.
        if (cards.map { it.card }.toSet() != CommandCard.Face.list.toSet() ||
            cards.size != CommandCard.Face.list.size ||
            cards.any { !it.isStunned && (it.servant == TeamSlot.Unknown || it.type == CardTypeEnum.Unknown) }
        ) return Result.RecognitionFailed

        fun usableCards(slot: TeamSlot?) = cards.filter { slot in deployed && it.servant == slot && !it.isStunned }
        val primary = usableCards(target)
        val secondary = usableCards(secondaryTarget)
        val colours = countColours(primary)
        val secondColours = countColours(secondary)
        return when {
            meets(primary.size, colours, requiredCards, primaryColours, primaryCombineMode) -> Result.Ready(primary.size, 0, colours)
            secondaryEnabled && meets(secondary.size, secondColours, secondaryRequired, secondaryColours, secondaryCombineMode) ->
                Result.Ready(secondary.size, 1, secondColours)
            maxWaitingTurns > 0 && waitingTurns >= maxWaitingTurns -> Result.WaitLimitReached
            else -> Result.Wait(primary.size, secondary.size.takeIf { secondaryEnabled }, colours, secondColours.takeIf { secondaryEnabled })
        }
    }

    private fun effectiveCount(raw: Int, colours: CardColourCounts): Int {
        val count = raw.coerceIn(0..5)
        return if (count == 0 && colours.buster == 0 && colours.arts == 0 && colours.quick == 0) 1 else count
    }

    private fun countColours(cards: List<ParsedCard>) = CardColourCounts(
        cards.count { it.type == CardTypeEnum.Buster },
        cards.count { it.type == CardTypeEnum.Arts },
        cards.count { it.type == CardTypeEnum.Quick }
    )

    private fun meets(count: Int, colours: CardColourCounts, required: Int, minimums: CardColourCounts, mode: CommandStartCombineMode): Boolean {
        val rules = listOfNotNull(
            (count >= required).takeIf { required > 0 },
            (colours.buster >= minimums.buster).takeIf { minimums.buster > 0 },
            (colours.arts >= minimums.arts).takeIf { minimums.arts > 0 },
            (colours.quick >= minimums.quick).takeIf { minimums.quick > 0 }
        )
        if (rules.isEmpty()) return count >= 1
        return when (mode) {
            CommandStartCombineMode.And -> rules.all { it }
            CommandStartCombineMode.Or -> rules.any { it }
        }
    }

    fun hasAvailableTarget(deployed: Collection<TeamSlot>) =
        target in deployed || (secondaryEnabled && secondaryTarget in deployed)

    fun recordWaitingTurn() {
        check(isWaiting)
        ++waitingTurns
    }

    fun startAt(stage: Int, turn: Int, profileIndex: Int = 0, totalTurns: Int = 0) {
        check(isWaiting)
        require(stage >= 0 && turn >= 0)
        require(profileIndex == 0 || (profileIndex == 1 && secondaryEnabled))
        selectedProfile = profileIndex
        startTotalTurns = totalTurns
        start = CommandPosition(stage, turn)
    }

    /** [totalTurns] is the cumulative turn counter of the run, including the current turn. */
    fun position(stage: Int, turn: Int, totalTurns: Int = 0): CommandPosition {
        if (mode == CommandStartMode.Basic) return CommandPosition(stage, turn, (totalTurns - 1).coerceAtLeast(0))
        val origin = checkNotNull(start) { "Command has not started" }
        require(stage >= origin.stage)
        val commandTurn = if (stage == origin.stage) turn - origin.turn else turn
        require(commandTurn >= 0)
        return CommandPosition(stage - origin.stage, commandTurn, (totalTurns - startTotalTurns).coerceAtLeast(0))
    }

    fun nextRun() {
        start = null
        startTotalTurns = 0
        waitingTurns = 0
        selectedProfile = null
    }
}
