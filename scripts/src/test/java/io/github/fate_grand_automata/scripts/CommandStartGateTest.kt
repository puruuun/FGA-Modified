package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode
import io.github.fate_grand_automata.scripts.models.CardColourCounts
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
        secondRequired: Int = 2,
        colours: CardColourCounts = CardColourCounts(),
        combine: CommandStartCombineMode = CommandStartCombineMode.And,
        secondColours: CardColourCounts = CardColourCounts(),
        secondCombine: CommandStartCombineMode = CommandStartCombineMode.And
    ): CommandStartGate {
        val config = mockk<IBattleConfig>()
        every { config.commandStartMode } returns mode
        every { config.commandStartServant } returns target
        every { config.commandStartCardCount } returns required
        every { config.commandStartBuster } returns colours.buster
        every { config.commandStartArts } returns colours.arts
        every { config.commandStartQuick } returns colours.quick
        every { config.commandStartCombineMode } returns combine
        every { config.secondaryStartBuster } returns secondColours.buster
        every { config.secondaryStartArts } returns secondColours.arts
        every { config.secondaryStartQuick } returns secondColours.quick
        every { config.secondaryStartCombineMode } returns secondCombine
        every { config.commandStartMaxTurns } returns limit
        every { config.secondaryStartEnabled } returns (second != null)
        every { config.secondaryStartServant } returns (second ?: 2)
        every { config.secondaryStartCardCount } returns secondRequired
        return CommandStartGate(config)
    }

    private fun readyResult(count: Int, profile: Int = 0) =
        CommandStartGate.Result.Ready(count, profile, CardColourCounts(buster = count))

    private fun waitResult(count: Int, second: Int? = null) =
        CommandStartGate.Result.Wait(count, second, CardColourCounts(buster = count), second?.let { CardColourCounts(buster = it) })

    private fun hand(count: Int) = CommandCard.Face.list.mapIndexed { index, card ->
        ParsedCard(card, if (index < count) TeamSlot.A else TeamSlot.B, null, CardTypeEnum.Buster)
    }

    @Test
    fun `threshold accepts at least the requested number`() {
        val gate = gate()
        for (count in 0..5) {
            val expected = if (count < 2) waitResult(count) else readyResult(count)
            assertThat(gate.check(hand(count), deployed)).isEqualTo(expected)
        }
    }

    @Test
    fun `counts the selected party slot instead of a card position`() {
        val gate = gate(target = 2, required = 3)
        assertThat(gate.check(hand(2), deployed)).isEqualTo(readyResult(3))
    }

    @Test
    fun `stunned target cards cannot satisfy the condition`() {
        val cards = hand(2).toMutableList()
        cards[0] = cards[0].copy(isStunned = true, type = CardTypeEnum.Unknown)
        assertThat(gate().check(cards, deployed)).isEqualTo(waitResult(1))
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
            assertThat(gate.check(hand(1), deployed)).isEqualTo(waitResult(1))
            gate.recordWaitingTurn()
        }
        assertThat(gate.check(hand(1), deployed)).isEqualTo(CommandStartGate.Result.WaitLimitReached)
        assertThat(gate.check(hand(2), deployed)).isEqualTo(readyResult(2))
    }

    @Test
    fun `unlimited waiting does not latch and can cross waves`() {
        val gate = gate()
        repeat(100) {
            assertThat(gate.check(hand(0), deployed)).isEqualTo(waitResult(0))
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
    @Test
    fun `zero colour minimums leave count as the only rule in both modes`() {
        for (mode in CommandStartCombineMode.entries) {
            for (count in 0..5) {
                val expected = if (count < 2) waitResult(count) else readyResult(count)
                assertThat(gate(combine = mode).check(hand(count), deployed)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `AND requires the count and every active colour`() {
        val gate = gate(required = 3, colours = CardColourCounts(buster = 1, arts = 1))
        assertThat(gate.check(hand(3), deployed)).isEqualTo(waitResult(3))
        val mixed = hand(3).mapIndexed { index, card -> if (index == 0) card.copy(type = CardTypeEnum.Arts) else card }
        assertThat(gate.check(mixed, deployed)).isEqualTo(CommandStartGate.Result.Ready(3, colours = CardColourCounts(2, 1)))
        val tooFew = mixed.mapIndexed { index, card -> if (index == 2) card.copy(servant = TeamSlot.B) else card }
        assertThat(gate.check(tooFew, deployed)).isEqualTo(CommandStartGate.Result.Wait(2, colours = CardColourCounts(1, 1)))
    }

    @Test
    fun `OR accepts count alone or any active colour alone`() {
        val gate = gate(required = 3, colours = CardColourCounts(buster = 2, arts = 1, quick = 1), combine = CommandStartCombineMode.Or)
        assertThat(gate.check(hand(3), deployed)).isEqualTo(readyResult(3))
        assertThat(gate.check(hand(1), deployed)).isEqualTo(waitResult(1))
        for (type in listOf(CardTypeEnum.Arts, CardTypeEnum.Quick)) {
            val cards = hand(1).mapIndexed { index, card -> if (index == 0) card.copy(type = type) else card }
            val result = gate.check(cards, deployed) as CommandStartGate.Result.Ready
            assertThat(result.count).isEqualTo(1)
        }
        val countOnly = hand(3).map { it.copy(type = CardTypeEnum.Quick) }
        assertThat((gate(required = 3, colours = CardColourCounts(buster = 1), combine = CommandStartCombineMode.Or)
            .check(countOnly, deployed) as CommandStartGate.Result.Ready).count).isEqualTo(3)
    }

    @Test
    fun `only target colours count and stunned target colours are excluded`() {
        val gate = gate(required = 5, colours = CardColourCounts(arts = 1), combine = CommandStartCombineMode.Or)
        val otherArts = hand(1).map { if (it.servant == TeamSlot.B) it.copy(type = CardTypeEnum.Arts) else it }
        assertThat(gate.check(otherArts, deployed)).isEqualTo(waitResult(1))
        val stunned = otherArts.mapIndexed { index, card ->
            if (index == 0) card.copy(type = CardTypeEnum.Arts, isStunned = true) else card
        }
        assertThat(gate.check(stunned, deployed)).isEqualTo(waitResult(0))
    }

    @Test
    fun `count one AND Buster one is equivalent to having a usable target Buster`() {
        for (count in 0..5) {
            for (buster in 0..count) {
                val cards = hand(count).mapIndexed { index, card ->
                    if (index in buster until count) card.copy(type = CardTypeEnum.Arts) else card
                }
                val result = gate(required = 1, colours = CardColourCounts(buster = 1)).check(cards, deployed)
                assertThat(result is CommandStartGate.Result.Ready).isEqualTo(buster > 0)
            }
        }
    }

    @Test
    fun `secondary colours and combine mode are independent and primary wins a tie`() {
        val gate = gate(required = 2, colours = CardColourCounts(arts = 1), second = 2, secondRequired = 5,
            secondColours = CardColourCounts(quick = 1), secondCombine = CommandStartCombineMode.Or)
        val cards = dualHand(2, 1).map { if (it.servant == TeamSlot.B) it.copy(type = CardTypeEnum.Quick) else it }
        assertThat(gate.check(cards, deployed)).isEqualTo(CommandStartGate.Result.Ready(1, 1, CardColourCounts(quick = 1)))
        val both = cards.mapIndexed { index, card -> if (index == 0) card.copy(type = CardTypeEnum.Arts) else card }
        assertThat(gate.check(both, deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 0, CardColourCounts(1, 1)))
    }

    @Test
    fun `secondary AND requires all colours and reports both targets while waiting`() {
        val gate = gate(required = 5, second = 2, secondRequired = 2, secondColours = CardColourCounts(arts = 1, quick = 1))
        val cards = dualHand(1, 2).mapIndexed { index, card -> if (index == 1) card.copy(type = CardTypeEnum.Arts) else card }
        assertThat(gate.check(cards, deployed)).isEqualTo(
            CommandStartGate.Result.Wait(1, 2, CardColourCounts(1), CardColourCounts(1, 1))
        )
        val ready = cards.mapIndexed { index, card -> if (index == 2) card.copy(type = CardTypeEnum.Quick) else card }
        assertThat(gate.check(ready, deployed)).isEqualTo(CommandStartGate.Result.Ready(2, 1, CardColourCounts(0, 1, 1)))
    }

    @Test
    fun `imported colour minimums are bounded to zero through five`() {
        val cards = hand(5).map { it.copy(type = CardTypeEnum.Arts) }
        assertThat(gate(colours = CardColourCounts(-1, 99, -4)).check(cards, deployed))
            .isEqualTo(CommandStartGate.Result.Ready(5, colours = CardColourCounts(arts = 5)))
        assertThat(gate(second = 2, required = 5, secondColours = CardColourCounts(99, -1, -4))
            .check(dualHand(0, 5), deployed)).isEqualTo(readyResult(5, 1))
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
        assertThat(gate.check(dualHand(2, 1), deployed)).isEqualTo(readyResult(2, 0))
        assertThat(gate.check(dualHand(1, 2), deployed)).isEqualTo(readyResult(2, 1))
        assertThat(gate.check(dualHand(2, 2), deployed)).isEqualTo(readyResult(2, 0))
        assertThat(gate.check(dualHand(1, 1), deployed)).isEqualTo(waitResult(1, 1))
    }

    @Test
    fun `first condition wins even if second has more cards`() {
        assertThat(gate(second = 2).check(dualHand(2, 3), deployed))
            .isEqualTo(readyResult(2, 0))
    }

    @Test
    fun `thresholds are independent`() {
        val gate = gate(required = 3, second = 2, secondRequired = 2)
        assertThat(gate.check(dualHand(2, 2), deployed)).isEqualTo(readyResult(2, 1))
        assertThat(gate.check(dualHand(3, 2), deployed)).isEqualTo(readyResult(3, 0))
    }

    @Test
    fun `surviving second target can start after the first dies`() {
        assertThat(gate(second = 2).check(dualHand(0, 2), listOf(TeamSlot.B, TeamSlot.C)))
            .isEqualTo(readyResult(2, 1))
    }

    @Test
    fun `surviving first target can start after the second dies`() {
        assertThat(gate(second = 2).check(dualHand(2, 0), listOf(TeamSlot.A, TeamSlot.C)))
            .isEqualTo(readyResult(2, 0))
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
