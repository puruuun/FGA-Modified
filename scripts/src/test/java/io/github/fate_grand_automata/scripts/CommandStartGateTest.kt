package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.CommandStartGate
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandStartGateTest {
    private val deployed = listOf(TeamSlot.A, TeamSlot.B, TeamSlot.C)

    private fun gate(
        mode: CommandStartMode = CommandStartMode.CardCount,
        target: Int = 1,
        required: Int = 2,
        limit: Int = 0,
        second: Int? = null,
        secondRequired: Int = 2
    ): CommandStartGate {
        val config = mockk<IBattleConfig>()
        every { config.commandStartMode } returns mode
        every { config.commandStartServant } returns target
        every { config.commandStartCardCount } returns required
        every { config.commandStartMaxTurns } returns limit
        every { config.secondaryStartEnabled } returns (second != null)
        every { config.secondaryStartServant } returns (second ?: 2)
        every { config.secondaryStartCardCount } returns secondRequired
        return CommandStartGate(config)
    }

    private fun hand(count: Int) = CommandCard.Face.list.mapIndexed { index, card ->
        ParsedCard(card, if (index < count) TeamSlot.A else TeamSlot.B, null, CardTypeEnum.Buster)
    }

    @Test
    fun `threshold accepts at least the requested number`() {
        val gate = gate()
        for (count in 0..5) {
            val expected = if (count < 2) CommandStartGate.Result.Wait(count) else CommandStartGate.Result.Ready(count)
            assertThat(gate.check(hand(count), deployed)).isEqualTo(expected)
        }
    }

    @Test
    fun `counts the selected party slot instead of a card position`() {
        val gate = gate(target = 2, required = 3)
        assertThat(gate.check(hand(2), deployed)).isEqualTo(CommandStartGate.Result.Ready(3))
    }

    @Test
    fun `stunned target cards cannot satisfy the condition`() {
        val cards = hand(2).toMutableList()
        cards[0] = cards[0].copy(isStunned = true, type = CardTypeEnum.Unknown)
        assertThat(gate().check(cards, deployed)).isEqualTo(CommandStartGate.Result.Wait(1))
    }

    @Test
    fun `unknown ownership and type require a retry instead of a waiting attack`() {
        for (card in listOf(
            hand(2)[0].copy(servant = TeamSlot.Unknown),
            hand(2)[0].copy(type = CardTypeEnum.Unknown)
        )) {
            val cards = hand(2).toMutableList().also { it[0] = card }
            assertThat(gate().check(cards, deployed)).isEqualTo(CommandStartGate.Result.RecognitionFailed)
        }
    }

    @Test
    fun `incomplete or duplicate hands cannot be counted`() {
        assertThat(gate().check(hand(2).take(4), deployed)).isEqualTo(CommandStartGate.Result.RecognitionFailed)
        assertThat(gate().check(List(5) { hand(2)[0] }, deployed)).isEqualTo(CommandStartGate.Result.RecognitionFailed)
    }

    @Test
    fun `dead or swapped out target stops waiting`() {
        assertThat(gate().check(hand(2), listOf(TeamSlot.D, TeamSlot.B, TeamSlot.C)))
            .isEqualTo(CommandStartGate.Result.TargetUnavailable)
    }

    @Test
    fun `limit counts skipped attacks and still allows a ready hand after the last skip`() {
        val gate = gate(limit = 2)
        repeat(2) {
            assertThat(gate.check(hand(1), deployed)).isEqualTo(CommandStartGate.Result.Wait(1))
            gate.recordWaitingTurn()
        }
        assertThat(gate.check(hand(1), deployed)).isEqualTo(CommandStartGate.Result.WaitLimitReached)
        assertThat(gate.check(hand(2), deployed)).isEqualTo(CommandStartGate.Result.Ready(2))
    }

    @Test
    fun `unlimited waiting does not latch and can cross waves`() {
        val gate = gate()
        repeat(100) {
            assertThat(gate.check(hand(0), deployed)).isEqualTo(CommandStartGate.Result.Wait(0))
            gate.recordWaitingTurn()
        }
        assertTrue(gate.isWaiting)
        gate.startAt(2, 4)
        assertFalse(gate.isWaiting)
        assertThat(gate.position(2, 4)).isEqualTo(CommandPosition(0, 0))
    }

    @Test
    fun `delayed command resets the turn offset on subsequent waves`() {
        val gate = gate()
        gate.startAt(1, 2) // actual wave 2 turn 3
        assertThat(gate.position(1, 2)).isEqualTo(CommandPosition(0, 0))
        assertThat(gate.position(1, 3)).isEqualTo(CommandPosition(0, 1))
        assertThat(gate.position(2, 0)).isEqualTo(CommandPosition(1, 0))
        assertThat(gate.position(2, 1)).isEqualTo(CommandPosition(1, 1))
        assertFalse(gate.isWaiting)
    }

    @Test
    fun `basic mode has no gate or offsets`() {
        val gate = gate(mode = CommandStartMode.Basic)
        assertFalse(gate.isWaiting)
        assertThat(gate.position(2, 4)).isEqualTo(CommandPosition(2, 4))
    }

    @Test
    fun `new quest resets the latch and skipped turns`() {
        val gate = gate()
        gate.recordWaitingTurn()
        gate.startAt(1, 2)
        gate.nextRun()
        assertTrue(gate.isWaiting)
        assertThat(gate.waitingTurns).isEqualTo(0)
        gate.startAt(0, 0)
        assertThat(gate.position(0, 0)).isEqualTo(CommandPosition(0, 0))
    }

    @Test
    fun `actual turn statistics stay independent of command turns`() {
        val state = BattleState()
        state.nextStage()
        repeat(3) { state.nextTurn() }
        state.commandPosition = CommandPosition(0, 0)
        assertThat(state.turn).isEqualTo(2)
        assertThat(state.commandTurn).isEqualTo(0)
        state.nextRun()
        assertThat(state.minTurnsPerRun).isEqualTo(3)
        assertThat(state.commandPosition).isEqualTo(null)
    }

    @Test
    fun `malformed imported numeric settings are bounded`() {
        val gate = gate(target = 99, required = -1, limit = -10)
        assertThat(gate.target).isEqualTo(TeamSlot.C)
        assertThat(gate.requiredCards).isEqualTo(1)
    }
    private fun dualHand(a: Int, b: Int) = CommandCard.Face.list.mapIndexed { index, card ->
        ParsedCard(card, when {
            index < a -> TeamSlot.A
            index < a + b -> TeamSlot.B
            else -> TeamSlot.C
        }, null, CardTypeEnum.Buster)
    }

    @Test
    fun `two conditions choose first second or wait from the same hand`() {
        val gate = gate(second = 2)
        assertThat(gate.check(dualHand(2, 1), deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 0))
        assertThat(gate.check(dualHand(1, 2), deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 1))
        assertThat(gate.check(dualHand(2, 2), deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 0))
        assertThat(gate.check(dualHand(1, 1), deployed)).isEqualTo(CommandStartGate.Result.Wait(1, 1))
    }

    @Test
    fun `first condition wins even if second has more cards`() {
        assertThat(gate(second = 2).check(dualHand(2, 3), deployed))
            .isEqualTo(CommandStartGate.Result.Ready(2, 0))
    }

    @Test
    fun `thresholds are independent`() {
        val gate = gate(required = 3, second = 2, secondRequired = 2)
        assertThat(gate.check(dualHand(2, 2), deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 1))
        assertThat(gate.check(dualHand(3, 2), deployed)).isEqualTo(CommandStartGate.Result.Ready(3, 0))
    }

    @Test
    fun `surviving second target can start after the first dies`() {
        assertThat(gate(second = 2).check(dualHand(0, 2), listOf(TeamSlot.B, TeamSlot.C)))
            .isEqualTo(CommandStartGate.Result.Ready(2, 1))
    }

    @Test
    fun `surviving first target can start after the second dies`() {
        assertThat(gate(second = 2).check(dualHand(2, 0), listOf(TeamSlot.A, TeamSlot.C)))
            .isEqualTo(CommandStartGate.Result.Ready(2, 0))
    }

    @Test
    fun `both missing targets stop instead of waiting forever`() {
        assertThat(gate(second = 2).check(dualHand(0, 0), listOf(TeamSlot.C)))
            .isEqualTo(CommandStartGate.Result.TargetUnavailable)
    }

    @Test
    fun `duplicate target settings are rejected`() {
        assertThat(gate(second = 1).check(dualHand(2, 2), deployed))
            .isEqualTo(CommandStartGate.Result.DuplicateTargets)
    }

    @Test
    fun `second selection latches and resets on the next quest`() {
        val gate = gate(second = 2)
        gate.startAt(1, 2, 1)
        assertThat(gate.selectedProfile).isEqualTo(1)
        assertFalse(gate.isWaiting)
        assertThat(gate.position(1, 3)).isEqualTo(CommandPosition(0, 1))
        gate.nextRun()
        assertTrue(gate.isWaiting)
        assertThat(gate.selectedProfile).isEqualTo(null)
    }

}
