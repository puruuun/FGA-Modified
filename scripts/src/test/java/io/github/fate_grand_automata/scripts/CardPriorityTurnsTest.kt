package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.CommandStartGate
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

class CardPriorityTurnsTest {
    // The hand sorts to A, E, B (entry 0), B, E, C (entry 1) and C, D, A (entry 2).
    private val entry0 = "WB, WA, WQ, B, A, Q, RB, RA, RQ"
    private val entry1 = "Q, WQ, RQ, A, WA, RA, B, WB, RB"
    private val entry2 = "RA, WA, A, B, WB, RB, Q, WQ, RQ"

    private val face0 = listOf(CommandCard.Face.A, CommandCard.Face.E, CommandCard.Face.B)
    private val face1 = listOf(CommandCard.Face.B, CommandCard.Face.E, CommandCard.Face.C)
    private val face2 = listOf(CommandCard.Face.C, CommandCard.Face.D, CommandCard.Face.A)

    private class Fixture(
        priority: String,
        split: CardPrioritySplit,
        rearrange: List<Boolean> = emptyList(),
        braveChains: List<BraveChainEnum> = emptyList()
    ) {
        val taps = mutableListOf<CommandCard>()
        val state = BattleState().also { it.nextStage() }
        val card: Card

        init {
            val caster = mockk<Caster>(relaxed = true)
            every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
            val tracker = mockk<ServantTracker>()
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
            val config = mockk<IBattleConfig>(relaxed = true)
            every { config.cardPriority } returns CardPriorityPerWave.of(priority)
            every { config.cardPrioritySplit } returns split
            every { config.rearrangeCards } returns rearrange
            every { config.braveChains } returns braveChains
            every { config.useServantPriority } returns false
            every { config.spam } returns emptyList<ServantSpamConfig>()
            val execution = BattleExecution(config)
            card = Card(mockk(relaxed = true), tracker, state, caster, mockk<CardParser>(), ApplyBraveChains(), execution)
        }

        fun click(stage: Int, turn: Int, totalTurn: Int): List<CommandCard> {
            taps.clear()
            state.commandPosition = CommandPosition(stage, turn, totalTurn)
            card.clickCommandCards(FaceCardPriorityTest.lineup1, NPUsage.none, allowNpSpam = false)
            return taps.toList()
        }
    }

    private val three = listOf(entry0, entry1, entry2).joinToString("\n")

    @Test
    fun `ten entries parse and are stored unchanged`() {
        val text = (0 until 10)
            .map { index -> if (index % 2 == 0) entry0 else entry1 }
            .mapIndexed { index, line -> if (index == 9) entry2 else line }
            .joinToString("\n")

        val parsed = CardPriorityPerWave.of(text)

        assertThat(parsed.size).isEqualTo(10)
        assertThat(CardPriorityPerWave.of(parsed.toString()).toString()).isEqualTo(parsed.toString())
        assertThat(parsed.toString().lines().size).isEqualTo(10)
        assertThat(parsed.atWave(9).take(9).toString()).isEqualTo(CardPriorityPerWave.of(entry2).atWave(0).take(9).toString())
    }

    @Test
    fun `the last entry repeats past the end of the list`() {
        val parsed = CardPriorityPerWave.of(three)

        assertThat(parsed.atWave(2)).isEqualTo(parsed.atWave(3))
        assertThat(parsed.atWave(2)).isEqualTo(parsed.atWave(50))
    }

    @Test
    fun `wave mode follows the wave and ignores the turn count`() {
        val f = Fixture(three, CardPrioritySplit.Wave)

        assertThat(f.click(stage = 0, turn = 0, totalTurn = 0)).containsExactly(*face0.toTypedArray())
        assertThat(f.click(stage = 0, turn = 1, totalTurn = 1)).containsExactly(*face0.toTypedArray())
        assertThat(f.click(stage = 1, turn = 0, totalTurn = 2)).containsExactly(*face1.toTypedArray())
        assertThat(f.click(stage = 2, turn = 0, totalTurn = 3)).containsExactly(*face2.toTypedArray())
        assertThat(f.click(stage = 9, turn = 0, totalTurn = 9)).containsExactly(*face2.toTypedArray())
    }

    @Test
    fun `turn mode follows the cumulative turn across a wave change`() {
        val f = Fixture(three, CardPrioritySplit.Turn)

        assertThat(f.click(stage = 0, turn = 0, totalTurn = 0)).containsExactly(*face0.toTypedArray())
        assertThat(f.click(stage = 0, turn = 1, totalTurn = 1)).containsExactly(*face1.toTypedArray())
        // New wave: the wave turn counter restarts, the cumulative one keeps going.
        assertThat(f.click(stage = 1, turn = 0, totalTurn = 2)).containsExactly(*face2.toTypedArray())
    }

    @Test
    fun `turn mode repeats the last entry`() {
        val f = Fixture(three, CardPrioritySplit.Turn)

        assertThat(f.click(stage = 2, turn = 4, totalTurn = 7)).containsExactly(*face2.toTypedArray())
    }

    @Test
    fun `rearrange and brave chains follow the turn entry`() {
        val f = Fixture(
            three,
            CardPrioritySplit.Turn,
            rearrange = listOf(false, false, true)
        )

        assertThat(f.click(stage = 0, turn = 0, totalTurn = 0)).containsExactly(*face0.toTypedArray())
        // Entry 2 sorts to C, D, A and the rearrange swaps the last two.
        assertThat(f.click(stage = 0, turn = 2, totalTurn = 2))
            .containsExactly(CommandCard.Face.C, CommandCard.Face.A, CommandCard.Face.D)
    }

    @Test
    fun `rearrange stays on the wave entry in wave mode`() {
        val f = Fixture(three, CardPrioritySplit.Wave, rearrange = listOf(false, false, true))

        // Total turn 2 would select the rearranging entry, but wave 0 does not.
        assertThat(f.click(stage = 0, turn = 2, totalTurn = 2)).containsExactly(*face0.toTypedArray())
    }

    // ---- command position ----

    private fun gate(mode: CommandStartMode): CommandStartGate {
        val config = mockk<IBattleConfig>()
        every { config.commandStartMode } returns mode
        every { config.commandStartServant } returns 1
        every { config.commandStartCardCount } returns 2
        every { config.commandStartBuster } returns 0
        every { config.commandStartArts } returns 0
        every { config.commandStartQuick } returns 0
        every { config.commandStartCombineMode } returns io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode.And
        every { config.commandStartMaxTurns } returns 0
        every { config.secondaryStartEnabled } returns false
        every { config.secondaryStartServant } returns 2
        every { config.secondaryStartCardCount } returns 2
        return CommandStartGate(config)
    }

    @Test
    fun `run turn counter keeps counting across waves`() {
        val state = BattleState()
        val seen = mutableListOf<Int>()

        state.nextStage(); state.nextTurn(); seen += state.runTotalTurns
        state.nextTurn(); seen += state.runTotalTurns
        state.nextStage(); state.nextTurn(); seen += state.runTotalTurns

        assertThat(seen).containsExactly(1, 2, 3)
        assertThat(state.turn).isEqualTo(0)
    }

    @Test
    fun `basic mode starts at entry zero on the first turn`() {
        val gate = gate(CommandStartMode.Basic)

        assertThat(gate.position(0, 0, 1).totalTurn).isEqualTo(0)
        assertThat(gate.position(0, 1, 2).totalTurn).isEqualTo(1)
        assertThat(gate.position(1, 0, 3).totalTurn).isEqualTo(2)
    }

    @Test
    fun `card count start counts from the turn the command started`() {
        val gate = gate(CommandStartMode.CardCount)
        // Two waiting turns, the command starts on the third turn of the run.
        gate.recordWaitingTurn()
        gate.recordWaitingTurn()
        gate.startAt(stage = 0, turn = 2, profileIndex = 0, totalTurns = 3)

        assertThat(gate.position(0, 2, 3).totalTurn).isEqualTo(0)
        assertThat(gate.position(0, 3, 4).totalTurn).isEqualTo(1)
        assertThat(gate.position(1, 0, 5).totalTurn).isEqualTo(2)
        assertThat(gate.position(1, 0, 5).stage).isEqualTo(1)

        gate.nextRun()
        gate.startAt(stage = 1, turn = 0, totalTurns = 4)
        assertThat(gate.position(1, 0, 4).totalTurn).isEqualTo(0)
    }

    @Test
    fun `turns waiting for the card count start use the first entry`() {
        val state = BattleState().also { it.nextStage(); it.nextTurn(); it.nextTurn() }

        assertThat(state.commandTotalTurn).isEqualTo(0)
    }
}
