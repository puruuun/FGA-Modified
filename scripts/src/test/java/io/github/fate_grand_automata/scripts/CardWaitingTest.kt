package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.NpSpamConfig
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.FaceCardPriority
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardWaitingTest {
    @Test
    fun `waiting cards suppress real NP Spam while normal calls preserve it`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val tracker = mockk<ServantTracker>()
        every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
        val state = BattleState()
        state.nextStage()
        state.nextStage()
        state.nextStage() // actual wave 3, shifted command wave 1
        state.commandPosition = CommandPosition(0, 0)
        val caster = mockk<Caster>(relaxed = true)
        every { caster.canSpam(SpamEnum.Spam) } returns true
        val parser = mockk<CardParser>()
        val priority = mockk<FaceCardPriority>()
        val chains = mockk<ApplyBraveChains>()
        val config = mockk<IBattleConfig>()
        every { config.braveChains } returns emptyList()
        every { config.rearrangeCards } returns emptyList()
        val cards = CommandCard.Face.list.map {
            ParsedCard(it, TeamSlot.A, FieldSlot.A, CardTypeEnum.Buster)
        }
        every { priority.sort(cards, 0) } returns cards
        every { chains.pick(any(), any(), any(), any()) } answers { firstArg() }
        val spam = SpamConfigPerTeamSlot(listOf(
            ServantSpamConfig(np = NpSpamConfig(waves = setOf(1), spam = SpamEnum.Spam))
        ))
        val card = Card(api, tracker, state, spam, caster, parser, priority, chains, config)

        card.clickCommandCards(cards, NPUsage.none, allowNpSpam = false)
        verify(exactly = 0) { caster.use(any<CommandCard.NP>()) }
        verify(exactly = 3) { caster.use(any<CommandCard.Face>()) }
        verify(exactly = 0) { caster.canSpam(any()) }

        card.clickCommandCards(cards, NPUsage.none)
        verify(exactly = 1) { caster.use(CommandCard.NP.A) }
        verify(exactly = 2) { priority.sort(cards, 0) }
    }

    @Test
    fun `card count mode overrides face checks without writing the global setting`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.prefs.skipServantFaceCardCheck } returns true
        val config = mockk<IBattleConfig>()
        every { config.commandStartMode } returns CommandStartMode.CardCount
        assertTrue(ServantTracker(api, config).faceCardCheckEnabled)
        every { config.commandStartMode } returns CommandStartMode.Basic
        assertFalse(ServantTracker(api, config).faceCardCheckEnabled)
        assertTrue(api.prefs.skipServantFaceCardCheck)
    }
}
