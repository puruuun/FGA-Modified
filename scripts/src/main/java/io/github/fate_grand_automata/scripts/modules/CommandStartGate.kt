package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
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
    val requiredCards = config.commandStartCardCount.coerceIn(1..5)
    val secondaryEnabled = mode == CommandStartMode.CardCount && config.secondaryStartEnabled
    val secondaryTarget = if (secondaryEnabled) TeamSlot.list[config.secondaryStartServant.coerceIn(1..3) - 1] else null
    val secondaryRequired = if (secondaryEnabled) config.secondaryStartCardCount.coerceIn(1..5) else 0
    val hasDuplicateTargets get() = secondaryEnabled && target == secondaryTarget
    private val maxWaitingTurns = config.commandStartMaxTurns.coerceAtLeast(0)

    private var start: CommandPosition? = null
    private var startTotalTurns = 0
    var waitingTurns = 0
        private set
    var selectedProfile: Int? = null
        private set

    val isWaiting get() = mode == CommandStartMode.CardCount && start == null

    sealed class Result {
        data class Ready(val count: Int, val profileIndex: Int = 0) : Result()
        data class Wait(val count: Int, val secondaryCount: Int? = null) : Result()
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

        val count = if (target in deployed) cards.count { it.servant == target && !it.isStunned } else 0
        val secondCount = if (secondaryTarget in deployed) cards.count { it.servant == secondaryTarget && !it.isStunned } else 0
        return when {
            count >= requiredCards -> Result.Ready(count)
            secondaryEnabled && secondCount >= secondaryRequired -> Result.Ready(secondCount, 1)
            maxWaitingTurns > 0 && waitingTurns >= maxWaitingTurns -> Result.WaitLimitReached
            else -> Result.Wait(count, secondCount.takeIf { secondaryEnabled })
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
