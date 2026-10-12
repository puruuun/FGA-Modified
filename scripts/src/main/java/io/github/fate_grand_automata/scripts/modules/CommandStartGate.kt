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
    private val maxWaitingTurns = config.commandStartMaxTurns.coerceAtLeast(0)

    private var start: CommandPosition? = null
    var waitingTurns = 0
        private set

    val isWaiting get() = mode == CommandStartMode.CardCount && start == null

    sealed class Result {
        data class Ready(val count: Int) : Result()
        data class Wait(val count: Int) : Result()
        data object RecognitionFailed : Result()
        data object TargetUnavailable : Result()
        data object WaitLimitReached : Result()
    }

    fun check(cards: List<ParsedCard>, deployed: Collection<TeamSlot>): Result {
        check(isWaiting)
        if (target !in deployed) return Result.TargetUnavailable

        // Stunned cards intentionally have an unknown type and are not usable.
        if (cards.map { it.card }.toSet() != CommandCard.Face.list.toSet() ||
            cards.size != CommandCard.Face.list.size ||
            cards.any { !it.isStunned && (it.servant == TeamSlot.Unknown || it.type == CardTypeEnum.Unknown) }
        ) return Result.RecognitionFailed

        val count = cards.count { it.servant == target && !it.isStunned }
        return when {
            count >= requiredCards -> Result.Ready(count)
            maxWaitingTurns > 0 && waitingTurns >= maxWaitingTurns -> Result.WaitLimitReached
            else -> Result.Wait(count)
        }
    }

    fun recordWaitingTurn() {
        check(isWaiting)
        ++waitingTurns
    }

    fun startAt(stage: Int, turn: Int) {
        check(isWaiting)
        require(stage >= 0 && turn >= 0)
        start = CommandPosition(stage, turn)
    }

    fun position(stage: Int, turn: Int): CommandPosition {
        if (mode == CommandStartMode.Basic) return CommandPosition(stage, turn)
        val origin = checkNotNull(start) { "Command has not started" }
        require(stage >= origin.stage)
        val commandTurn = if (stage == origin.stage) turn - origin.turn else turn
        require(commandTurn >= 0)
        return CommandPosition(stage - origin.stage, commandTurn)
    }

    fun nextRun() {
        start = null
        waitingTurns = 0
    }
}
