package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BattleExecutionTest {
    @Test fun `shuffle runs in command order and preserves subsequent direct cards or NP actions`() {
        for (suffix in listOf("&ba4", "456")) {
            val execution = BattleExecution(config(first = TestExecutionProfile(skillCommand = "b1aH{8:13}d2$suffix")))
            val caster = mockk<Caster>(relaxed = true)
            val shuffle = mockk<ShuffleUntil>(relaxed = true)
            val autoSkill = AutoSkill(mockk(relaxed = true), execution, caster, shuffle)
            val usage = autoSkill.execute(0, 0)
            verifyOrder {
                caster.castServantSkill(Skill.Servant.A2, listOf(ServantTarget.A))
                shuffle.execute(any())
                caster.castServantSkill(Skill.Servant.B1, listOf(ServantTarget.B))
            }
            if (suffix.startsWith('&')) assertThat(usage.directCards?.code).isEqualTo("ba4")
            else assertThat(usage.nps).isEqualTo(CommandCard.NP.list.toSet())
        }
    }
    private fun config(
        first: TestExecutionProfile = TestExecutionProfile(skillCommand = "a4"),
        second: TestExecutionProfile = TestExecutionProfile(skillCommand = "d5,e5,#,f5"),
        mode: CommandStartMode = CommandStartMode.CardCount,
        enabled: Boolean = true,
        spam: List<ServantSpamConfig> = emptyList(),
        shuffle: ShuffleCardsEnum = ShuffleCardsEnum.None,
        shuffleWave: Int = 3
    ): IBattleConfig = mockk<IBattleConfig>().also { config ->
        every { config.skillCommand } answers { first.skillCommand }
        every { config.cardPriority } answers { first.cardPriority }
        every { config.servantPriority } returns first.servantPriority
        every { config.useServantPriority } returns first.useServantPriority
        every { config.cardPrioritySplit } returns first.cardPrioritySplit
        every { config.cardPriorityStyle } returns first.cardPriorityStyle
        every { config.servantCardPriority } returns first.servantCardPriority
        every { config.rearrangeCards } returns first.rearrangeCards
        every { config.braveChains } returns first.braveChains
        every { config.spam } returns spam
        every { config.shuffleCards } returns shuffle
        every { config.shuffleCardsWave } returns shuffleWave
        every { config.commandStartMode } returns mode
        every { config.secondaryStartEnabled } returns enabled
        every { config.secondaryExecution } returns second
    }

    @Test
    fun `selected second command executes its own skills and NP across shifted waves`() {
        val execution = BattleExecution(config())
        execution.select(1)
        val caster = mockk<Caster>(relaxed = true)
        val autoSkill = AutoSkill(mockk<IFgoAutomataApi>(relaxed = true), execution, caster, mockk(relaxed = true))
        assertThat(autoSkill.execute(0, 0)).isEqualTo(NPUsage(setOf(CommandCard.NP.B), 0))
        autoSkill.execute(0, 1)
        autoSkill.execute(1, 0)
        verify(exactly = 1) { caster.castServantSkill(Skill.Servant.B1, emptyList()) }
        verify(exactly = 1) { caster.castServantSkill(Skill.Servant.B2, emptyList()) }
        verify(exactly = 1) { caster.castServantSkill(Skill.Servant.B3, emptyList()) }
        verify(exactly = 0) { caster.castServantSkill(Skill.Servant.A1, any<List<ServantTarget>>()) }
    }

    @Test
    fun `profiles retain independent card priorities and servant-priority toggle`() {
        val first = TestExecutionProfile(useServantPriority = false)
        val second = TestExecutionProfile(
            cardPriority = CardPriorityPerWave.of("Q, A, B, WQ, WA, WB, RQ, RA, RB"),
            useServantPriority = true,
            servantPriority = ServantPriorityPerWave.of("2,1,3,4,5,6")
        )
        val cards = listOf(
            ParsedCard(CommandCard.Face.A, TeamSlot.A, FieldSlot.A, CardTypeEnum.Buster),
            ParsedCard(CommandCard.Face.B, TeamSlot.B, FieldSlot.B, CardTypeEnum.Arts),
            ParsedCard(CommandCard.Face.C, TeamSlot.B, FieldSlot.B, CardTypeEnum.Quick)
        )
        val execution = BattleExecution(config(first, second))
        assertThat(execution.active.priority.sort(cards, 0).map { it.card })
            .isEqualTo(listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.C))
        execution.select(1)
        assertThat(execution.active.priority.sort(cards, 0).map { it.card })
            .isEqualTo(listOf(CommandCard.Face.C, CommandCard.Face.B, CommandCard.Face.A))
    }

    @Test
    fun `second profile brave chain and rearrangement remain separate with common shuffle`() {
        val second = TestExecutionProfile(
            braveChains = listOf(BraveChainEnum.WithNP),
            rearrangeCards = listOf(true)
        )
        val execution = BattleExecution(config(second = second, shuffle = ShuffleCardsEnum.NoNPMatching, shuffleWave = 2))
        assertThat(execution.active.braveChains).isEqualTo(emptyList())
        assertThat(execution.shuffleCards).isEqualTo(ShuffleCardsEnum.NoNPMatching)
        assertThat(execution.shuffleCardsWave).isEqualTo(2)
        execution.select(1)
        assertThat(execution.active.braveChains).isEqualTo(listOf(BraveChainEnum.WithNP))
        assertThat(execution.active.rearrangeCards).isEqualTo(listOf(true))
        assertThat(execution.shuffleCards).isEqualTo(ShuffleCardsEnum.NoNPMatching)
        assertThat(execution.shuffleCardsWave).isEqualTo(2)
    }

    @Test
    fun `a selected profile cannot switch before the next quest`() {
        val execution = BattleExecution(config())
        execution.select(1)
        assertFailsWith<IllegalStateException> { execution.select(0) }
        execution.nextRun()
        execution.select(0)
        assertThat(execution.selectedIndex).isEqualTo(0)
    }

    @Test
    fun `basic or single-condition mode ignores an invalid unused second command`() {
        for ((mode, enabled) in listOf(CommandStartMode.Basic to true, CommandStartMode.CardCount to false)) {
            val execution = BattleExecution(config(second = TestExecutionProfile(skillCommand = "!"), mode = mode, enabled = enabled))
            assertThat(execution.selectedIndex).isEqualTo(null)
            assertFailsWith<IllegalArgumentException> { execution.select(1) }
        }
    }

    @Test
    fun `second card selection uses its own Brave Chain and shared NP spam`() {
        val first = TestExecutionProfile()
        val second = TestExecutionProfile(
            useServantPriority = true,
            servantPriority = ServantPriorityPerWave.of("2,1,3,4,5,6"),
            braveChains = listOf(BraveChainEnum.WithNP)
        )
        val sharedSpam = listOf(ServantSpamConfig(), ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(1))))
        val execution = BattleExecution(config(first, second, spam = sharedSpam))
        execution.select(1)
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val tracker = mockk<ServantTracker>()
        every { tracker.deployed } returns mapOf(FieldSlot.B to TeamSlot.B)
        val state = BattleState().also { it.nextStage(); it.nextStage(); it.commandPosition = CommandPosition(0, 0) }
        val caster = mockk<Caster>(relaxed = true)
        every { caster.canSpam(SpamEnum.Spam) } returns true
        val cards = CommandCard.Face.list.mapIndexed { index, card ->
            ParsedCard(card, if (index < 2) TeamSlot.B else TeamSlot.A,
                if (index < 2) FieldSlot.B else FieldSlot.A, CardTypeEnum.Buster)
        }
        Card(api, tracker, state, caster, mockk<CardParser>(), ApplyBraveChains(), execution)
            .clickCommandCards(cards, NPUsage.none)
        verify(exactly = 1) { caster.use(CommandCard.NP.B) }
        verify(exactly = 0) { caster.use(CommandCard.NP.A) }
        verify(exactly = 1) { caster.use(CommandCard.Face.A) }
        verify(exactly = 1) { caster.use(CommandCard.Face.B) }
    }

    @Test
    fun `common automatic skill and NP settings survive both selections and quest reset`() {
        val automatic = ServantSpamConfig(
            np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(2)),
            skills = listOf(SkillSpamConfig(spam = SpamEnum.Danger, waves = setOf(1)))
        )
        val execution = BattleExecution(config(spam = listOf(automatic)))
        for (index in listOf(0, 1, 0)) {
            execution.select(index)
            assertThat(execution.spam[TeamSlot.A]).isEqualTo(automatic)
            execution.nextRun()
        }
    }

    @Test
    fun `both branches use the common automatic skill settings`() {
        val automatic = ServantSpamConfig(skills = listOf(SkillSpamConfig(spam = SpamEnum.Spam, waves = setOf(1))))
        for (index in listOf(0, 1)) {
            val execution = BattleExecution(config(spam = listOf(automatic)))
            execution.select(index)
            val tracker = mockk<ServantTracker>()
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
            every { tracker.checkImages } returns mutableMapOf()
            val caster = mockk<Caster>(relaxed = true)
            every { caster.canSpam(SpamEnum.Spam) } returns true
            val state = BattleState().also { it.nextStage(); it.commandPosition = CommandPosition(0, 0) }
            SkillSpam(mockk<IFgoAutomataApi>(relaxed = true), tracker, state, execution, caster).spamSkills()
            verify(exactly = 1) { caster.canSpam(SpamEnum.Spam) }
        }
    }
}
