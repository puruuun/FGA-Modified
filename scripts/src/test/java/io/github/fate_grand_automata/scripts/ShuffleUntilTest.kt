package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ShuffleUntilTest {
    private fun hand(count: Int) = CommandCard.Face.list.mapIndexed { index, card ->
        ParsedCard(card, if (index < count) TeamSlot.A else TeamSlot.B, null, CardTypeEnum.Buster)
    }

    private inner class Fixture(val meetAfter: Int = Int.MAX_VALUE, val shortageAt: Int? = null) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val caster = mockk<Caster>(relaxed = true)
        val card = mockk<Card>(relaxed = true)
        val tracker = mockk<ServantTracker>(relaxed = true)
        val state = BattleState()
        val handReader = mockk<CommandHand>(relaxed = true)
        val popup = Region(10, 10, 320, 130)
        val close = Region(20, 20, 100, 50)
        val icon = Location(50, 100)
        var presses = 0
        val readsAt = mutableListOf<Int>()
        val logs = mutableListOf<ScriptLog.ShuffleUntil>()
        val module = ShuffleUntil(api, caster, card, tracker, state, handReader)
        init {
            every { tracker.faceCardCheckEnabled } returns true
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B)
            every { handReader.open() } answers {
                readsAt += presses
                hand(if (presses >= meetAfter) 3 else 1)
            }
            every { handReader.back() } returns true
            every { handReader.battleVisible() } returns true
            every { caster.waitForAnimationToFinish(any()) } answers { presses++; Unit }
            every { api.locations.battle.shuffleNPShortageRegion } returns popup
            every { api.locations.battle.locate(any<Skill.Servant>()) } returns icon
            every { with(api) { popup.exists(any<Pattern>(), 1.seconds, any()) } } answers { presses == shortageAt }
            every { with(api) { popup.find(any<Pattern>(), any()) } } returns Match(close, 0.99)
            every { api.messages.log(any()) } answers { (firstArg<ScriptLog>() as? ScriptLog.ShuffleUntil)?.let(logs::add); Unit }
        }
        fun run(min: Int = 0, max: Int = 3, stop: Boolean = false) = module.execute(
            AutoSkillAction.ShuffleUntil(Skill.Servant.A1, min, max,
                listOf(AutoSkillAction.ShuffleCondition(FieldSlot.A, 3)), stop)
        )
    }

    @Test fun `first hand can meet with zero presses`() {
        val f = Fixture(meetAfter = 0)
        f.run()
        assertEquals(listOf(0), f.readsAt)
        assertEquals(0, f.presses)
        assertTrue(f.logs.single().message.contains("reason=Met"))
        verify(exactly = 0) { f.caster.waitForAnimationToFinish(any()) }
    }

    @Test fun `met after k presses and min prevents every earlier read`() {
        for ((min, meet, expected) in listOf(Triple(0, 2, listOf(0, 1, 2)), Triple(2, 0, listOf(2)))) {
            val f = Fixture(meetAfter = meet)
            f.run(min = min, stop = true)
            assertEquals(expected, f.readsAt)
            assertEquals(2, f.presses)
            assertTrue(f.state.hakunoShuffledThisTurn)
        }
    }

    @Test fun `NP shortage before and after min closes popup and honors stop flag`() {
        for (stop in listOf(false, true)) for ((min, shortage) in listOf(3 to 1, 1 to 2)) {
            val f = Fixture(shortageAt = shortage)
            if (stop) {
                val error = assertFailsWith<AutoBattle.BattleExitException> { f.run(min, 3, true) }
                assertEquals(AutoBattle.ExitReason.ShuffleUntilUnmet(ShuffleEndReason.NPShortage, shortage), error.reason)
            } else f.run(min, 3)
            verify { with(f.api) { f.close.click(1) } }
            verify { f.handReader.battleVisible() }
            verify(exactly = 0) { f.caster.castServantSkill(any(), any<List<ServantTarget>>()) }
            assertTrue(f.logs.single().message.contains("reason=NPShortage"))
            if (shortage < min) assertTrue(f.readsAt.isEmpty())
        }
    }

    @Test fun `max reached honors stop flag and reads the final hand`() {
        for (stop in listOf(false, true)) {
            val f = Fixture()
            if (stop) {
                assertEquals(AutoBattle.ExitReason.ShuffleUntilUnmet(ShuffleEndReason.MaxReached, 2),
                    assertFailsWith<AutoBattle.BattleExitException> { f.run(max = 2, stop = true) }.reason)
            } else f.run(max = 2)
            assertEquals(listOf(0, 1, 2), f.readsAt)
            assertEquals(2, f.presses)
        }
    }

    @Test fun `failed hand retries twice then returns before ending with either stop policy`() {
        for (stop in listOf(false, true)) {
            val f = Fixture()
            val invalid = hand(1).map { it.copy(type = CardTypeEnum.Unknown) }
            every { f.handReader.open() } returns invalid
            every { f.card.readCommandCards() } returns invalid
            if (stop) {
                assertEquals(AutoBattle.ExitReason.ShuffleUntilUnmet(ShuffleEndReason.ReadFailed, 0),
                    assertFailsWith<AutoBattle.BattleExitException> { f.run(stop = true) }.reason)
            } else f.run()
            verify(exactly = 2) { f.card.readCommandCards() }
            verify(exactly = 1) { f.handReader.back() }
            assertEquals(0, f.presses)
        }
    }

    @Test fun `same hand retry can recover without a skill press`() {
        val f = Fixture()
        every { f.handReader.open() } returns hand(3).dropLast(1)
        every { f.card.readCommandCards() } returns hand(3)
        f.run()
        verify(exactly = 1) { f.card.readCommandCards() }
        assertEquals(0, f.presses)
    }

    @Test fun `return failure after hand or popup always stops`() {
        for (popup in listOf(false, true)) {
            val f = Fixture(meetAfter = if (popup) Int.MAX_VALUE else 0, shortageAt = if (popup) 0 else null)
            every { f.handReader.back() } returns popup
            every { f.handReader.battleVisible() } returns !popup
            assertEquals(AutoBattle.ExitReason.ShuffleUntilReturnFailed,
                assertFailsWith<AutoBattle.BattleExitException> { f.run() }.reason)
        }
    }

    @Test fun `disabled face check exits before reading or pressing`() {
        val f = Fixture()
        every { f.tracker.faceCardCheckEnabled } returns false
        assertEquals(AutoBattle.ExitReason.ShuffleUntilFaceCheckDisabled,
            assertFailsWith<AutoBattle.BattleExitException> { f.run() }.reason)
        verify(exactly = 0) { f.handReader.open() }
        assertFalse(f.state.hakunoShuffledThisTurn)
    }

    @Test fun `counts follow current field occupants including support and ignore stunned cards and empty slots`() {
        val cards = hand(3).mapIndexed { i, card -> card.copy(servant = TeamSlot.F, isStunned = i == 0) }
        assertEquals(listOf(0, 4, 0), ShuffleUntil.counts(cards, mapOf(FieldSlot.B to TeamSlot.F)))
        assertFalse(CommandHand.valid(cards.dropLast(1)))
        assertFalse(CommandHand.valid(cards.map { it.copy(servant = TeamSlot.Unknown) }))
        assertTrue(CommandHand.valid(cards.map { it.copy(isStunned = true, type = CardTypeEnum.Unknown, servant = TeamSlot.Unknown) }))
    }

    @Test fun `OR accepts a later slot with at least the requested count`() {
        val f = Fixture()
        f.module.execute(AutoSkillAction.ShuffleUntil(Skill.Servant.B1, 0, 3, listOf(
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 3), AutoSkillAction.ShuffleCondition(FieldSlot.B, 3))))
        assertEquals(0, f.presses)
        assertTrue(f.logs.single().message.contains("reason=Met"))
    }

    @Test fun `turn and run boundaries clear the shuffle flag`() {
        val state = BattleState()
        state.hakunoShuffledThisTurn = true
        state.nextStage()
        state.nextTurn()
        assertFalse(state.hakunoShuffledThisTurn)
        state.hakunoShuffledThisTurn = true
        state.nextRun()
        assertFalse(state.hakunoShuffledThisTurn)
    }
}
