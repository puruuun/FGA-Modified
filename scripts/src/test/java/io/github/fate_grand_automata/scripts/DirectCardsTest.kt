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

    private class Fixture(
        command: String = "", secondCommand: String = "&a5b",
        cardPriority: String = "Q,WQ,RQ,A,WA,RA,B,WB,RB",
        useServantPriority: Boolean = true,
        servantPriority: String = "3,2,1,4,5,6",
        secondary: TestExecutionProfile = TestExecutionProfile(skillCommand = secondCommand)
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val caster = mockk<Caster>(relaxed = true)
        val tracker = mockk<ServantTracker>()
        val chains = mockk<ApplyBraveChains>()
        val taps = mutableListOf<CommandCard>()
        val execution: BattleExecution
        val state: BattleState
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
            every { config.secondaryExecution } returns secondary
            every { config.useServantPriority } returns useServantPriority
            every { config.servantPriority } returns ServantPriorityPerWave.of(servantPriority)
            every { config.cardPriority } returns CardPriorityPerWave.of(cardPriority)
            every { config.braveChains } returns listOf(BraveChainEnum.Avoid)
            every { config.rearrangeCards } returns listOf(true)
            every { config.spam } returns listOf(ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(1))))
            execution = BattleExecution(config)
            state = BattleState().also { it.nextStage(); it.commandPosition = CommandPosition(0, 0) }
            card = Card(api, tracker, state, caster, mockk<CardParser>(), chains, execution)
            autoSkill = AutoSkill(api, execution, caster, mockk(relaxed = true))
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
            "ba4" to listOf(CommandCard.Face.C, CommandCard.Face.B, CommandCard.NP.A),
            "bba" to listOf(CommandCard.Face.C, CommandCard.Face.A, CommandCard.Face.B),
            "b5a" to listOf(CommandCard.Face.C, CommandCard.NP.B, CommandCard.Face.B),
            "a4b" to listOf(CommandCard.Face.B, CommandCard.NP.A, CommandCard.Face.C),
            "4ba" to listOf(CommandCard.NP.A, CommandCard.Face.C, CommandCard.Face.B),
            "baq" to listOf(CommandCard.Face.C, CommandCard.Face.B, CommandCard.Face.E),
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
    fun `Weak Normal Resist Busters are selected in affinity order regardless of input order`() {
        val sequence = DirectCardSequence.parse("bbb")
        val cards = hand().mapIndexed { index, card -> if (index < 3) card.copy(type = CardTypeEnum.Buster,
            affinity = CardAffinityEnum.entries[index]) else card }
        assertThat(sequence.resolve(cards.reversed())).isEqualTo(listOf(CommandCard.Face.B, CommandCard.Face.A, CommandCard.Face.C))
    }

    @Test
    fun `every requested colour prefers Weak then Normal then Resist`() {
        for ((token, colour) in listOf('b' to CardTypeEnum.Buster, 'a' to CardTypeEnum.Arts, 'q' to CardTypeEnum.Quick)) {
            val cards = hand().mapIndexed { index, card ->
                card.copy(type = colour, affinity = when (index) {
                    2 -> CardAffinityEnum.Weak
                    1 -> CardAffinityEnum.Normal
                    else -> CardAffinityEnum.Resist
                })
            }
            assertThat(DirectCardSequence.parse("${token}45").resolve(cards.reversed())?.first())
                .isEqualTo(CommandCard.Face.C)
            assertThat(DirectCardSequence.parse("${token}45").resolve(cards.map {
                if (it.card == CommandCard.Face.C) it.copy(isStunned = true) else it
            })?.first()).isEqualTo(CommandCard.Face.B)
        }
    }

    @Test
    fun `affinity ties stay leftmost even with shuffled input`() {
        val cards = hand().map { it.copy(type = CardTypeEnum.Buster, affinity = CardAffinityEnum.Weak) }
        assertThat(DirectCardSequence.parse("bbb").resolve(cards.reversed()))
            .isEqualTo(listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.C))
    }

    @Test
    fun `bba and bbb reserve the best Buster for the earliest slot`() {
        val cards = hand().mapIndexed { index, card -> card.copy(
            type = if (index < 3) CardTypeEnum.Buster else CardTypeEnum.Arts,
            affinity = when (index) { 0 -> CardAffinityEnum.Resist; 2 -> CardAffinityEnum.Weak; else -> CardAffinityEnum.Normal }) }
        assertThat(DirectCardSequence.parse("bba").resolve(cards))
            .isEqualTo(listOf(CommandCard.Face.C, CommandCard.Face.B, CommandCard.Face.D))
        assertThat(DirectCardSequence.parse("bbb").resolve(cards))
            .isEqualTo(listOf(CommandCard.Face.C, CommandCard.Face.B, CommandCard.Face.A))
    }

    @Test
    fun `fallback still uses supplied priority for gaps after affinity reservation`() {
        val cards = hand().map { if (it.type == CardTypeEnum.Quick) it.copy(type = CardTypeEnum.Arts) else it }
        assertThat(DirectCardSequence.parse("?q4b").resolve(cards) { remaining -> remaining.sortedBy { it.card.index } })
            .isEqualTo(listOf(CommandCard.Face.A, CommandCard.NP.A, CommandCard.Face.C))
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
        assertThat(f.taps).isEqualTo(listOf(CommandCard.Face.B, CommandCard.NP.B, CommandCard.Face.C))
    }

    private fun artsAndQuicks() = hand().mapIndexed { index, card ->
        card.copy(type = if (index == 0) CardTypeEnum.Arts else CardTypeEnum.Quick,
            affinity = CardAffinityEnum.Normal)
    }

    @Test
    fun `fallback reserves later Arts before replacing missing Buster and logs final order`() {
        val f = Fixture(command = "&?b4a", cardPriority = "B,WB,RB,A,WA,RA,Q,WQ,RQ", useServantPriority = false)
        val expected = listOf(CommandCard.Face.B, CommandCard.NP.A, CommandCard.Face.A)
        f.card.clickCommandCards(artsAndQuicks(), f.autoSkill.execute(0, 0))
        assertThat(f.taps).isEqualTo(expected)
        verify(exactly = 1) { f.api.messages.log(match<ScriptLog.DirectCardsFallback> {
            it.requested == "&?b4a" && it.cards == expected
        }) }
        verify(exactly = 0) { f.chains.pick(any(), any(), any(), any()) }
        verify(exactly = 0) { f.caster.canSpam(any()) }
        val strict = Fixture(command = "&b4a")
        assertFailsWith<AutoBattle.BattleExitException> {
            strict.card.clickCommandCards(artsAndQuicks(), strict.autoSkill.execute(0, 0))
        }
        assertThat(strict.taps).isEqualTo(emptyList())
    }

    @Test
    fun `multiple gaps repeated colors and all NP positions reserve globally without reuse`() {
        val cards = artsAndQuicks()
        val expected = mapOf(
            "?bba" to listOf(CommandCard.Face.B, CommandCard.Face.C, CommandCard.Face.A),
            "?aaa" to listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.Face.C),
            "?4ba" to listOf(CommandCard.NP.A, CommandCard.Face.B, CommandCard.Face.A),
            "?b4a" to listOf(CommandCard.Face.B, CommandCard.NP.A, CommandCard.Face.A),
            "?ba4" to listOf(CommandCard.Face.B, CommandCard.Face.A, CommandCard.NP.A),
            "?bb4" to listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.NP.A)
        )
        for ((code, wanted) in expected) {
            assertThat(DirectCardSequence.parse(code).resolve(cards)).isEqualTo(wanted)
        }
        // A scarce repeated color is reserved for the earlier slot, even with reverse priority.
        assertThat(DirectCardSequence.parse("?aaa").resolve(cards) { it.reversed() })
            .isEqualTo(listOf(CommandCard.Face.A, CommandCard.Face.E, CommandCard.Face.D))
    }

    @Test
    fun `satisfied fallback is exactly strict affinity selection and never calls priority sorter or logs`() {
        for (code in listOf("bba", "ba4", "b5a", "4ba", "456")) {
            val f = Fixture(command = "&?$code")
            f.card.clickCommandCards(hand().reversed(), f.autoSkill.execute(0, 0))
            assertThat(f.taps).isEqualTo(DirectCardSequence.parse(code).resolve(hand()))
            assertThat(DirectCardSequence.parse("?$code").resolve(hand()) { error("Must not sort") })
                .isEqualTo(f.taps)
            verify(exactly = 0) { f.api.messages.log(any<ScriptLog.DirectCardsFallback>()) }
        }
    }

    @Test
    fun `fallback uses shared card servant wave and secondary priorities only for gaps`() {
        val cards = hand().map { it.copy(type = if (it.card == CommandCard.Face.B) CardTypeEnum.Arts else
            if (it.card == CommandCard.Face.E) CardTypeEnum.Quick else CardTypeEnum.Buster,
            affinity = CardAffinityEnum.Normal) }
        val priorities = "Q,WQ,RQ,B,WB,RB,A,WA,RA\nB,WB,RB,Q,WQ,RQ,A,WA,RA"
        for ((wave, face) in listOf(0 to CommandCard.Face.E, 1 to CommandCard.Face.A)) {
            val f = Fixture(command = "&?aaa", cardPriority = priorities, useServantPriority = false)
            f.state.commandPosition = CommandPosition(wave, 0)
            f.card.clickCommandCards(cards, AutoSkillAction.Atk.direct(DirectCardSequence.parse("?aaa")).toNPUsage())
            assertThat(f.taps.first()).isEqualTo(CommandCard.Face.B)
            assertThat(f.taps[1]).isEqualTo(face)
        }
        for ((priority, face) in listOf("1,2,3,4,5,6" to CommandCard.Face.A, "3,2,1,4,5,6" to CommandCard.Face.E)) {
            val f = Fixture(command = "&?aaa", servantPriority = priority)
            f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0))
            assertThat(f.taps.take(2)).isEqualTo(listOf(CommandCard.Face.B, face))
        }
        val f = Fixture(command = "&?aaa", secondary = TestExecutionProfile(skillCommand = "&?aaa",
            cardPriority = CardPriorityPerWave.of(priorities), useServantPriority = false))
        f.execution.select(1)
        f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0))
        assertThat(f.taps.take(2)).isEqualTo(listOf(CommandCard.Face.B, CommandCard.Face.E))
    }

    @Test
    fun `fallback excludes stunned cards and rejects bad hands insufficient cards and missing NP before taps`() {
        val stunned = artsAndQuicks().map { if (it.card == CommandCard.Face.B)
            it.copy(isStunned = true, type = CardTypeEnum.Unknown) else it }
        assertThat(DirectCardSequence.parse("?b4a").resolve(stunned))
            .isEqualTo(listOf(CommandCard.Face.C, CommandCard.NP.A, CommandCard.Face.A))
        val bad = listOf(
            artsAndQuicks().map { it.copy(type = CardTypeEnum.Unknown) },
            artsAndQuicks().dropLast(1),
            artsAndQuicks().dropLast(1) + artsAndQuicks().first(),
            artsAndQuicks().map { it.copy(isStunned = it.card != CommandCard.Face.A) }
        )
        for (cards in bad) {
            val f = Fixture(command = "&?4bb")
            assertFailsWith<AutoBattle.BattleExitException> { f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0)) }
            assertThat(f.taps).isEqualTo(emptyList())
        }
        val f = Fixture(command = "&?b5a")
        every { f.tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A)
        assertFailsWith<AutoBattle.BattleExitException> { f.card.clickCommandCards(artsAndQuicks(), f.autoSkill.execute(0, 0)) }
        assertThat(f.taps).isEqualTo(emptyList())
    }

    @Test
    fun `malformed duplicate priorities never reuse faces and fail atomically when exhausted`() {
        val cards = artsAndQuicks().map { if (it.card != CommandCard.Face.A)
            it.copy(type = CardTypeEnum.Buster) else it }
        for (code in listOf("?qqq", "?4qq")) {
            val f = Fixture(command = "&$code", cardPriority = "A,A,A,A,A,A,A,A,A", useServantPriority = false)
            assertFailsWith<AutoBattle.BattleExitException> { f.card.clickCommandCards(cards, f.autoSkill.execute(0, 0)) }
            assertThat(f.taps).isEqualTo(emptyList())
        }
        assertThat(DirectCardSequence.parse("?bb4").resolve(artsAndQuicks()) { listOf(it[0], it[0], it[1]) })
            .isEqualTo(listOf(CommandCard.Face.A, CommandCard.Face.B, CommandCard.NP.A))
    }

    @Test
    fun `fallback syntax normalizes and retains skills separators no ops and per turn flag`() {
        val parsed = AutoSkillCommand.parse("0a&?B4A,&b4a,#,&?456")
        assertThat(parsed[0, 0].size).isEqualTo(2)
        assertThat((parsed[0, 0].last() as AutoSkillAction.Atk).toCommandString()).isEqualTo("&?b4a")
        assertThat((parsed[0, 1].single() as AutoSkillAction.Atk).directCards?.fallback).isEqualTo(false)
        assertThat((parsed[1, 0].single() as AutoSkillAction.Atk).toCommandString()).isEqualTo("&?456")
        val f = Fixture(command = "&?b4a,0")
        assertThat(f.autoSkill.execute(0, 0).directCards?.fallback).isEqualTo(true)
        assertThat(f.autoSkill.execute(0, 1)).isEqualTo(NPUsage.none)
        assertThat(DirectCardSequence.parse("b4a").fallback).isEqualTo(false)
    }

    @Test
    fun `fallback marker is allowed only once immediately after ampersand with three choices`() {
        for (code in listOf("&?", "&?ba", "&?bb44", "&??ba4", "&b?4a", "?&ba4", "&?4a4", "&?ba4a",
            "&?ba4&bba", "4&?ba4", "n1&?ba4", "&?bq7", "&?ba0", "&?ba4?")) {
            assertFailsWith<AutoBattle.BattleExitException>(code) { AutoSkillCommand.parse(code) }
        }
    }
}
