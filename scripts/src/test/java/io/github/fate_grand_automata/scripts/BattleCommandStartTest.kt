package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.AutoChooseTarget
import io.github.fate_grand_automata.scripts.modules.AutoSkill
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.CommandStartGate
import io.github.fate_grand_automata.scripts.modules.ShuffleChecker
import io.github.fate_grand_automata.scripts.modules.SkillSpam
import io.github.fate_grand_automata.scripts.modules.StageTracker
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BattleCommandStartTest {
    private fun hand(count: Int, secondCount: Int = 5 - count) = CommandCard.Face.list.mapIndexed { index, card ->
        ParsedCard(
            card,
            if (index < count) TeamSlot.A else if (index < count + secondCount) TeamSlot.B else TeamSlot.C,
            if (index < count) FieldSlot.A else if (index < count + secondCount) FieldSlot.B else FieldSlot.C,
            CardTypeEnum.Buster
        )
    }

    private class Fixture(
        mode: CommandStartMode = CommandStartMode.CardCount,
        dual: Boolean = false,
        shuffleMode: ShuffleCardsEnum = ShuffleCardsEnum.None,
        shuffleWave: Int = 1
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val config = mockk<IBattleConfig>(relaxed = true)
        val state = BattleState()
        val tracker = mockk<ServantTracker>(relaxed = true)
        val autoSkill = mockk<AutoSkill>(relaxed = true)
        val caster = mockk<Caster>(relaxed = true)
        val card = mockk<Card>(relaxed = true)
        val spam = mockk<SkillSpam>(relaxed = true)
        val shuffle = mockk<ShuffleChecker>(relaxed = true)
        val stage = mockk<StageTracker>(relaxed = true)
        val target = mockk<AutoChooseTarget>(relaxed = true)
        val screen = Region(0, 0, 10, 10)
        val back = Location(20, 20)
        val attack = Location(30, 30)
        val execution: BattleExecution
        val gate: CommandStartGate
        val battle: Battle

        init {
            every { config.commandStartMode } returns mode
            every { config.commandStartServant } returns 1
            every { config.commandStartCardCount } returns 2
            every { config.commandStartMaxTurns } returns 0
            every { config.secondaryStartEnabled } returns dual
            every { config.secondaryStartServant } returns 2
            every { config.secondaryStartCardCount } returns 2
            every { config.secondaryExecution } returns TestExecutionProfile(skillCommand = "5")
            every { config.skillCommand } returns "4"
            every { config.cardPriority } returns io.github.fate_grand_automata.scripts.models.CardPriorityPerWave.default
            every { config.servantPriority } returns io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave.default
            every { config.useServantPriority } returns false
            every { config.braveChains } returns emptyList()
            every { config.rearrangeCards } returns emptyList()
            every { config.spam } returns emptyList()
            every { config.shuffleCards } returns shuffleMode
            every { config.shuffleCardsWave } returns shuffleWave
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B)
            every { api.locations.battle.screenCheckRegion } returns screen
            every { api.locations.attack.backClick } returns back
            every { api.locations.battle.attackClick } returns attack
            every { api.useSameSnapIn<Unit>(any()) } answers { firstArg<() -> Unit>().invoke() }
            every { with(api) { screen.exists(any(), any(), any()) } } returns true
            every { stage.checkCurrentStage() } answers {
                if (state.stage == -1) state.nextStage()
            }
            every { autoSkill.execute(any(), any()) } returns NPUsage.none
            execution = BattleExecution(config)
            gate = CommandStartGate(config)
            battle = Battle(api, tracker, state, config, autoSkill, caster, card, spam, shuffle, stage, target, gate, execution)
        }
    }

    @Test
    fun `waiting uses only face cards and never runs skills spam or shuffle`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returns hand(1)
        f.battle.performBattle()

        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 0) { f.spam.spamSkills() }
        verify(exactly = 0) { f.shuffle.shouldShuffle(any(), any(), any()) }
        verify(exactly = 0) { f.caster.castMasterSkill(any(), any()) }
        verify(exactly = 1) { f.card.clickCommandCards(any(), NPUsage.none, false) }
        verify(exactly = 0) { with(f.api) { f.back.click(any()) } }
        assertThat(f.gate.waitingTurns).isEqualTo(1)
        assertThat(f.state.turn).isEqualTo(0)
    }

    @Test
    fun `ready hand starts in the same turn and later poor hands are not gated`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returnsMany listOf(hand(1), hand(2), hand(2), hand(0))
        repeat(3) { f.battle.performBattle() }

        verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        verify(exactly = 1) { f.autoSkill.execute(0, 1) }
        verify(exactly = 2) { f.spam.spamSkills() }
        verify(exactly = 4) { f.card.readCommandCards() }
        verify(exactly = 1) { with(f.api) { f.back.click(any()) } }
        verifyOrder {
            f.card.readCommandCards()
            f.card.clickCommandCards(any(), NPUsage.none, false)
            f.card.readCommandCards()
            with(f.api) { f.back.click(any()) }
            f.autoSkill.execute(0, 0)
            f.spam.spamSkills()
            f.card.readCommandCards()
            f.card.clickCommandCards(any(), any(), true)
            f.autoSkill.execute(0, 1)
        }
    }

    @Test
    fun `crossing a wave while waiting shifts the command wave and resets later turn offsets`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returns hand(1)
        f.battle.performBattle() // actual wave 1 turn 1
        f.state.nextStage()
        f.battle.performBattle() // actual wave 2 turn 1
        every { f.card.readCommandCards() } returns hand(3)
        f.battle.performBattle() // actual wave 2 turn 2 -> command wave 1 turn 1
        f.battle.performBattle() // actual wave 2 turn 3 -> command wave 1 turn 2
        f.state.nextStage()
        f.battle.performBattle() // actual wave 3 turn 1 -> command wave 2 turn 1

        verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        verify(exactly = 1) { f.autoSkill.execute(0, 1) }
        verify(exactly = 1) { f.autoSkill.execute(1, 0) }
        assertThat(f.state.stage).isEqualTo(2)
        assertThat(f.state.commandStage).isEqualTo(1)
        assertThat(f.state.turn).isEqualTo(0)
    }

    @Test
    fun `recognition retries on the same turn and stops without using any cards`() {
        val f = Fixture()
        val unknown = hand(2).map { it.copy(servant = TeamSlot.Unknown) }
        every { f.card.readCommandCards() } returns unknown

        val error = assertFailsWith<AutoBattle.BattleExitException> { f.battle.performBattle() }
        assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.CommandStartRecognitionFailed)
        verify(exactly = 3) { f.card.readCommandCards() }
        verify(exactly = 1) { with(f.api) { f.attack.click(any()) } }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any(), any()) }
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        assertThat(f.gate.waitingTurns).isEqualTo(0)
    }

    @Test
    fun `a transient recognition failure can recover without consuming a waiting turn`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returnsMany listOf(
            hand(2).map { it.copy(servant = TeamSlot.Unknown) }, hand(2), hand(2)
        )
        f.battle.performBattle()
        verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        assertThat(f.gate.waitingTurns).isEqualTo(0)
    }

    @Test
    fun `failure to return to the skill screen cannot run or latch the command`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returns hand(2)
        every { with(f.api) { f.screen.exists(any(), any(), any()) } } returns false
        val error = assertFailsWith<AutoBattle.BattleExitException> { f.battle.performBattle() }
        assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.CommandStartReturnFailed)
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        assertTrue(f.gate.isWaiting)
    }

    @Test
    fun `basic mode keeps skills before the single attack screen read`() {
        val f = Fixture(CommandStartMode.Basic)
        every { f.card.readCommandCards() } returns hand(0)
        f.battle.performBattle()
        verifyOrder {
            f.autoSkill.execute(0, 0)
            f.spam.spamSkills()
            with(f.api) { f.attack.click(any()) }
            f.card.readCommandCards()
            f.card.clickCommandCards(any(), any(), true)
        }
        verify(exactly = 1) { f.card.readCommandCards() }
        verify(exactly = 0) { with(f.api) { f.back.click(any()) } }
    }

    @Test
    fun `completed quest resets command position and waits again`() {
        val f = Fixture()
        every { f.card.readCommandCards() } returns hand(2)
        f.battle.performBattle()
        f.battle.resetState()
        every { f.card.readCommandCards() } returns hand(1)
        f.battle.performBattle()
        assertTrue(f.gate.isWaiting)
        assertThat(f.state.runs).isEqualTo(1)
        assertThat(f.state.commandPosition).isEqualTo(null)
        verify(exactly = 1) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 1) { f.card.clickCommandCards(any(), NPUsage.none, false) }
    }
    @Test
    fun `second ready hand selects second settings and never switches back`() {
        val f = Fixture(dual = true)
        every { f.card.readCommandCards() } returns hand(1) // B owns four cards
        f.battle.performBattle()
        assertThat(f.execution.selectedIndex).isEqualTo(1)
        assertThat(f.gate.selectedProfile).isEqualTo(1)
        every { f.card.readCommandCards() } returns hand(4) // A is ready next turn
        f.battle.performBattle()
        assertThat(f.execution.selectedIndex).isEqualTo(1)
        verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        verify(exactly = 1) { f.autoSkill.execute(0, 1) }
        verify(exactly = 3) { f.card.readCommandCards() }
        f.battle.resetState()
        assertThat(f.execution.selectedIndex).isEqualTo(null)
        f.battle.performBattle()
        assertThat(f.execution.selectedIndex).isEqualTo(0)
    }

    @Test
    fun `both ready chooses only the first settings`() {
        val f = Fixture(dual = true)
        every { f.card.readCommandCards() } returns hand(2) // A two, B three
        f.battle.performBattle()
        assertThat(f.execution.selectedIndex).isEqualTo(0)
        verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        verify(exactly = 1) { f.spam.spamSkills() }
    }

    @Test
    fun `first turn insufficient hand shuffles and starts the command without spending a turn`() {
        val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition, shuffleWave = 3)
        every { f.card.readCommandCards() } returnsMany listOf(hand(1), hand(2), hand(2), hand(0))
        f.battle.performBattle()
        assertThat(f.gate.waitingTurns).isEqualTo(0)
        assertThat(f.state.turn).isEqualTo(0)
        assertThat(f.state.shuffled).isEqualTo(true)
        verifyOrder {
            f.card.readCommandCards()
            with(f.api) { f.back.click(any()) }
            f.caster.castMasterSkill(Skill.Master.C, null)
            f.card.readCommandCards()
            with(f.api) { f.back.click(any()) }
            f.autoSkill.execute(0, 0)
            f.spam.spamSkills()
            f.card.readCommandCards()
            f.card.clickCommandCards(any(), any(), true)
        }
        f.battle.performBattle()
        verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.C, null) }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any(), false) }
        verify(exactly = 1) { f.autoSkill.execute(0, 1) }
    }

    @Test
    fun `post shuffle both conditions are checked and first still wins a tie`() {
        for ((cards, selected) in listOf(hand(2, 2) to 0, hand(1, 2) to 1)) {
            val f = Fixture(dual = true, shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
            every { f.card.readCommandCards() } returnsMany listOf(hand(1, 1), cards, cards)
            f.battle.performBattle()
            assertThat(f.execution.selectedIndex).isEqualTo(selected)
            assertThat(f.gate.selectedProfile).isEqualTo(selected)
            assertThat(f.gate.waitingTurns).isEqualTo(0)
            verify(exactly = 1) { f.autoSkill.execute(0, 0) }
            verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.C, null) }
        }
    }

    @Test
    fun `either initial qualifying condition bypasses shuffle`() {
        for ((cards, selected) in listOf(hand(2, 2) to 0, hand(1, 2) to 1)) {
            val f = Fixture(dual = true, shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
            every { f.card.readCommandCards() } returns cards
            f.battle.performBattle()
            assertThat(f.execution.selectedIndex).isEqualTo(selected)
            verify(exactly = 0) { f.caster.castMasterSkill(any(), any()) }
            verify(exactly = 1) { f.autoSkill.execute(0, 0) }
        }
    }

    @Test
    fun `post shuffle insufficient hand attacks with the new cards and does not reshuffle later turns`() {
        val f = Fixture(dual = true, shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
        val original = hand(1, 1)
        val newHand = hand(0, 1)
        every { f.card.readCommandCards() } returnsMany listOf(original, newHand, newHand)
        f.battle.performBattle()
        // ParsedCard equality compares only card positions, not the recognized owner.
        verify(exactly = 1) { f.card.clickCommandCards(match { it.map { card -> card.servant } == newHand.map { card -> card.servant } }, NPUsage.none, false) }
        verify(exactly = 0) { f.card.clickCommandCards(match { it.map { card -> card.servant } == original.map { card -> card.servant } }, any(), any()) }
        assertThat(f.gate.waitingTurns).isEqualTo(1)
        assertThat(f.state.turn).isEqualTo(0)
        f.battle.performBattle()
        verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.C, null) }
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 0) { f.spam.spamSkills() }
        assertThat(f.gate.waitingTurns).isEqualTo(2)
        assertThat(f.state.turn).isEqualTo(1)
    }

    @Test
    fun `first turn mode cannot shuffle on a later turn or first turn of a later wave`() {
        for (laterWave in listOf(false, true)) {
            val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
            f.state.nextStage()
            if (laterWave) f.state.nextStage() else f.state.nextTurn()
            every { f.card.readCommandCards() } returns hand(1)
            f.battle.performBattle()
            verify(exactly = 0) { f.caster.castMasterSkill(any(), any()) }
            verify(exactly = 1) { f.card.clickCommandCards(any(), NPUsage.none, false) }
        }
    }

    @Test
    fun `recognition failure before shuffle cannot spend the shuffle skill or attack`() {
        val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
        every { f.card.readCommandCards() } returns hand(1).map { it.copy(servant = TeamSlot.Unknown) }
        val error = assertFailsWith<AutoBattle.BattleExitException> { f.battle.performBattle() }
        assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.CommandStartRecognitionFailed)
        verify(exactly = 3) { f.card.readCommandCards() }
        verify(exactly = 0) { f.caster.castMasterSkill(any(), any()) }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any(), any()) }
    }

    @Test
    fun `recognition failure after shuffle retries the new hand then stops without starting or attacking`() {
        val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
        val unknown = hand(2).map { it.copy(servant = TeamSlot.Unknown) }
        every { f.card.readCommandCards() } returnsMany listOf(hand(1), unknown, unknown, unknown)
        val error = assertFailsWith<AutoBattle.BattleExitException> { f.battle.performBattle() }
        assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.CommandStartRecognitionFailed)
        verify(exactly = 4) { f.card.readCommandCards() }
        verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.C, null) }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any(), any()) }
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        assertThat(f.gate.waitingTurns).isEqualTo(0)
        assertTrue(f.gate.isWaiting)
    }

    @Test
    fun `return failure before or after shuffle prevents the command from starting`() {
        for (before in listOf(true, false)) {
            val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
            every { f.card.readCommandCards() } returnsMany listOf(hand(1), hand(2))
            every { with(f.api) { f.screen.exists(any(), any(), any()) } } returnsMany
                if (before) listOf(false) else listOf(true, false)
            val error = assertFailsWith<AutoBattle.BattleExitException> { f.battle.performBattle() }
            assertThat(error.reason).isEqualTo(AutoBattle.ExitReason.CommandStartReturnFailed)
            verify(exactly = if (before) 0 else 1) { f.caster.castMasterSkill(Skill.Master.C, null) }
            verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
            verify(exactly = 0) { f.card.clickCommandCards(any(), any(), any()) }
            assertTrue(f.gate.isWaiting)
        }
    }

    @Test
    fun `the next quest can attempt the first turn shuffle again`() {
        val f = Fixture(shuffleMode = ShuffleCardsEnum.FirstTurnStartCondition)
        every { f.card.readCommandCards() } returnsMany listOf(hand(1), hand(2), hand(2), hand(1), hand(2), hand(2))
        f.battle.performBattle()
        f.battle.resetState()
        assertThat(f.state.shuffled).isEqualTo(false)
        f.battle.performBattle()
        verify(exactly = 2) { f.caster.castMasterSkill(Skill.Master.C, null) }
        verify(exactly = 2) { f.autoSkill.execute(0, 0) }
        assertThat(f.gate.waitingTurns).isEqualTo(0)
        assertThat(f.state.turn).isEqualTo(0)
    }

    @Test
    fun `legacy shuffle conditions do not run during waiting and still use common settings after second starts`() {
        for (mode in listOf(ShuffleCardsEnum.NoEffective, ShuffleCardsEnum.NoNPMatching)) {
            val waiting = Fixture(shuffleMode = mode)
            every { waiting.card.readCommandCards() } returns hand(1)
            every { waiting.shuffle.shouldShuffle(any(), any(), any()) } returns true
            waiting.battle.performBattle()
            verify(exactly = 0) { waiting.shuffle.shouldShuffle(any(), any(), any()) }
            verify(exactly = 0) { waiting.caster.castMasterSkill(any(), any()) }

            val ready = Fixture(dual = true, shuffleMode = mode)
            every { ready.card.readCommandCards() } returns hand(1)
            every { ready.shuffle.shouldShuffle(any(), any(), any()) } returns true
            ready.battle.performBattle()
            assertThat(ready.execution.selectedIndex).isEqualTo(1)
            verify(exactly = 1) { ready.shuffle.shouldShuffle(mode, any(), any()) }
            verify(exactly = 1) { ready.caster.castMasterSkill(Skill.Master.C, null) }
        }
    }

}
