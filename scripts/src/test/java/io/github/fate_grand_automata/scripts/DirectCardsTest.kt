package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DirectCardsTest {
    private fun hand() = listOf(
        ParsedCard(CommandCard.Face.A, TeamSlot.A, FieldSlot.A, CardTypeEnum.Buster, CardAffinityEnum.Resist),
        ParsedCard(CommandCard.Face.B, TeamSlot.B, FieldSlot.B, CardTypeEnum.Arts, CardAffinityEnum.Weak),
        ParsedCard(CommandCard.Face.C, TeamSlot.B, FieldSlot.B, CardTypeEnum.Buster),
        ParsedCard(CommandCard.Face.D, TeamSlot.A, FieldSlot.A, CardTypeEnum.Arts, CardAffinityEnum.Resist),
        ParsedCard(CommandCard.Face.E, TeamSlot.C, FieldSlot.C, CardTypeEnum.Quick, CardAffinityEnum.Weak)
    )

    private class Fixture(command: String = "", secondCommand: String = "&a5b") {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val caster = mockk<Caster>(relaxed = true)
        val tracker = mockk<ServantTracker>()
        val chains = mockk<ApplyBraveChains>()
        val taps = mutableListOf<CommandCard>()
        val execution: BattleExecution
        val card: Card
        val autoSkill: AutoSkill
        init {
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C)
            every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
            every { caster.use(any<CommandCard.NP>()) } answers { taps += firstArg<CommandCard.NP>() }
            val config = mockk<IBattleConfig>(relaxed = true)
            every { config.skillCommand } returns command
            every { config.commandStartMode } returns CommandStartMode.CardCount
            every { config.secondaryStartEnabled } returns true
            every { config.secondaryExecution } returns TestExecutionProfile(skillCommand = secondCommand)
            every { config.useServantPriority } returns true
            every { config.servantPriority } returns ServantPriorityPerWave.of("3,2,1,4,5,6")
            every { config.cardPriority } returns CardPriorityPerWave.of("Q,WQ,RQ,A,WA,RA,B,WB,RB")
            every { config.braveChains } returns listOf(BraveChainEnum.Avoid)
            every { config.rearrangeCards } returns listOf(true)
            every { config.spam } returns listOf(ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(1))))
            execution = BattleExecution(config)
            val state = BattleState().also { it.nextStage(); it.commandPosition = CommandPosition(0, 0) }
            card = Card(api, tracker, state, caster, mockk<CardParser>(), chains, execution)
            autoSkill = AutoSkill(api, execution, caster)
        }
    }

    @Test
    fun `ampersand commands retain skills turns waves and NP identities`() {
        val command = AutoSkillCommand.parse("ab&ba4,&bba,&b5a,#,&4ba")
        assertThat(command[0, 0].size).isEqualTo(3)
        assertThat((command[0, 0].last() as AutoSkillAction.Atk).directCards?.code).isEqualTo("ba4")
        assertThat((command[0, 1].single() as AutoSkillAction.Atk).directCards?.code).isEqualTo("bba")
        assertThat((command[0, 2].single() as AutoSkillAction.Atk).nps).isEqualTo(setOf(CommandCard.NP.B))
        assertThat((command[1, 0].single() as AutoSkillAction.Atk).toCommandString()).isEqualTo("&4ba")
    }

    @Test
    fun `all slots including mixed NP positions are tapped in exact order without automatic policies`() {
        val expected = mapOf(
            "ba4" to listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.NP.A),
            "bba" to listOf(CommandCard.Face.A, CommandCard.Face.C, CommandCard.Face.B),
            "b5a" to listOf(CommandCard.Face.A, CommandCard.NP.B, CommandCard.Face.B),
            "a4b" to listOf(CommandCard.Face.B, CommandCard.NP.A, CommandCard.Face.A),
            "4ba" to listOf(CommandCard.NP.A, CommandCard.Face.A, CommandCard.Face.B),
            "baq" to listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.E),
            "4a5" to listOf(CommandCard.NP.A, CommandCard.Face.B, CommandCard.NP.B),
            "456" to listOf(CommandCard.NP.A, CommandCard.NP.B, CommandCard.NP.C)
        )
        for ((code, wanted) in expected) {
            val f = Fixture(command = "&$code")
            f.card.clickCommandCards(hand(), f.autoSkill.execute(0, 0))
            assertThat(f.taps).isEqualTo(wanted)
            verify(exactly = 0) { f.chains.pick(any(), any(), any(), any()) }
            verify(exactly = 0) { f.caster.canSpam(any()) }
        }
    }

    @Test
    fun `WB B and RB are all selected as B regardless of order in input`() {
        val sequence = DirectCardSequence.parse("bbb")
        val cards = hand().mapIndexed { index, card -> if (index < 3) card.copy(type = CardTypeEnum.Buster,
            affinity = CardAffinityEnum.entries[index]) else card }
        assertThat(sequence.resolve(cards.reversed())).isEqualTo(listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.C))
    }

    @Test
    fun `insufficient matching cards stop before an initial NP or any face card is tapped`() {
        for (code in listOf("4bb", "b4b", "bb4")) {
            val f = Fixture(command = "&$code")
            val cards = hand().map { if (it.card == CommandCard.Face.C) it.copy(type = CardTypeEnum.Quick) else it }
            val error = assertFailsWith<AutoBattle.BattleExitException> {
                f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0))
            }
            assertThat((error.reason as AutoBattle.ExitReason.DirectCardsUnavailable).sequence).isEqualTo("&$code")
            assertThat(f.taps).isEqualTo(emptyList())
        }
    }

    @Test
    fun `stunned cards are excluded and cannot be reused to satisfy two slots`() {
        val cards = hand().map { if (it.card == CommandCard.Face.A) it.copy(isStunned = true, type = CardTypeEnum.Unknown) else it }
        assertThat(DirectCardSequence.parse("b4a").resolve(cards)).isEqualTo(listOf(CommandCard.Face.C, CommandCard.NP.A, CommandCard.Face.B))
        assertThat(DirectCardSequence.parse("bba").resolve(cards)).isEqualTo(null)
    }

    @Test
    fun `unrecognized or incomplete hands never produce a partial fixed attack`() {
        val unknown = hand().map { if (it.card == CommandCard.Face.E) it.copy(type = CardTypeEnum.Unknown) else it }
        for (cards in listOf(unknown, hand().dropLast(1), hand().dropLast(1) + hand().first())) {
            val f = Fixture(command = "&4ba")
            assertFailsWith<AutoBattle.BattleExitException> { f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0)) }
            assertThat(f.taps).isEqualTo(emptyList())
        }
    }

    @Test
    fun `NP of an empty deployed position fails before any card click`() {
        val f = Fixture(command = "&b5a")
        every { f.tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
        assertFailsWith<AutoBattle.BattleExitException> { f.card.clickCommandCards(hand(), f.autoSkill.execute(0, 0)) }
        assertThat(f.taps).isEqualTo(emptyList())
    }

    @Test
    fun `invalid lengths choices duplicate NPs and mixing automatic attacks are rejected`() {
        for (code in listOf("&ba", "&bb44", "&wb4", "&4a4", "4&ba4", "n1&ba4", "&ba4a", "&ba4&bba", "&bq7", "&ba0")) {
            assertFailsWith<AutoBattle.BattleExitException>(code) { AutoSkillCommand.parse(code) }
        }
    }

    @Test
    fun `normal commands preserve their old syntax and uppercase fixed choices normalize`() {
        for (code in listOf("4", "n14", "n24", "54", "0")) {
            assertThat((AutoSkillCommand.parse(code)[0, 0].single() as AutoSkillAction.Atk).toCommandString()).isEqualTo(code)
        }
        assertThat((AutoSkillCommand.parse("&BA4")[0, 0].single() as AutoSkillAction.Atk).toCommandString()).isEqualTo("&ba4")
    }

    @Test
    fun `legacy no op before a fixed attack is harmless and does not remove preceding skills`() {
        val actions = AutoSkillCommand.parse("0a&bba")[0, 0]
        assertThat(actions.size).isEqualTo(2)
        assertThat((actions.last() as AutoSkillAction.Atk).toCommandString()).isEqualTo("&bba")
        assertThat((actions.first() as AutoSkillAction.ServantSkill).skill).isEqualTo(Skill.Servant.A1)
    }

    @Test
    fun `fixed attack is cleared on later turns and supports the selected second profile`() {
        val f = Fixture(command = "a&ba4,0")
        assertThat(f.autoSkill.execute(0, 0).directCards?.code).isEqualTo("ba4")
        verify(exactly = 1) { f.caster.castServantSkill(Skill.Servant.A1, emptyList()) }
        assertThat(f.autoSkill.execute(0, 1)).isEqualTo(NPUsage.none)
        f.execution.select(1)
        f.card.clickCommandCards(hand(), f.autoSkill.execute(0, 0))
        assertThat(f.taps).isEqualTo(listOf(CommandCard.Face.B, CommandCard.NP.B, CommandCard.Face.A))
    }
}
