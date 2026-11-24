package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.ChainTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.DirectCardSequence
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.NpSpamConfig
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityItem
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityPerWave
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ChainPriorityAvailability
import io.github.fate_grand_automata.scripts.modules.FaceCardCheckPolicy
import io.github.fate_grand_automata.scripts.modules.FaceCardReasonKind
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

/** Chain Priority on the fork's card code (#654): precedence, per-entry settings and the log field. */
class ChainPriorityForkTest {
    private val A = CommandCard.Face.A
    private val B = CommandCard.Face.B
    private val C = CommandCard.Face.C
    private val D = CommandCard.Face.D
    private val E = CommandCard.Face.E

    private fun card(face: CommandCard.Face, position: Int, type: CardTypeEnum) = ParsedCard(
        card = face,
        servant = TeamSlot.list[position - 1],
        fieldSlot = FieldSlot.list[position - 1],
        type = type,
        affinity = CardAffinityEnum.Normal
    )

    private val artsFirst = "A, B, Q, WA, WB, WQ, RA, RB, RQ"

    private fun profile(
        chain: List<Boolean> = listOf(true),
        order: String = "",
        brave: BraveChainEnum = BraveChainEnum.None,
        styles: List<CardPriorityStyle> = emptyList(),
        split: CardPrioritySplit = CardPrioritySplit.Wave,
        rearrange: Boolean = false
    ) = TestExecutionProfile(
        cardPriority = CardPriorityPerWave.of(List(3) { artsFirst }.joinToString("\n")),
        cardPrioritySplit = split,
        cardPriorityStyles = styles,
        braveChains = listOf(brave),
        rearrangeCards = listOf(rearrange),
        useChainPriorities = chain,
        chainPriority = ChainPriorityPerWave.of(order)
    )

    private fun configOf(
        primary: TestExecutionProfile,
        secondary: TestExecutionProfile? = null,
        spamSlot3: Boolean = false
    ) = mockk<IBattleConfig>(relaxed = true).also { config ->
        every { config.skillCommand } returns primary.skillCommand
        every { config.cardPriority } returns primary.cardPriority
        every { config.cardPrioritySplit } returns primary.cardPrioritySplit
        every { config.cardPriorityStyle } returns primary.cardPriorityStyle
        every { config.cardPriorityStyles } returns primary.cardPriorityStyles
        every { config.servantCardPriority } returns primary.servantCardPriority
        every { config.useServantPriority } returns primary.useServantPriority
        every { config.useServantPriorities } returns primary.useServantPriorities
        every { config.servantPriority } returns primary.servantPriority
        every { config.braveChains } returns primary.braveChains
        every { config.rearrangeCards } returns primary.rearrangeCards
        every { config.useChainPriorities } returns primary.useChainPriorities
        every { config.chainPriority } returns primary.chainPriority
        every { config.spam } returns if (spamSlot3) listOf(
            ServantSpamConfig(), ServantSpamConfig(),
            ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(1, 2, 3)))
        ) else emptyList()
        if (secondary != null) {
            every { config.commandStartMode } returns CommandStartMode.CardCount
            every { config.secondaryStartEnabled } returns true
            every { config.secondaryExecution } returns secondary
        }
    }

    private class Harness(
        config: IBattleConfig,
        npTypes: Map<TeamSlot, CardTypeEnum> = emptyMap(),
        detectedNps: Set<CommandCard.NP> = CommandCard.NP.list.toSet(),
        chainShown: Boolean = true,
        profileIndex: Int? = null
    ) {
        val taps = mutableListOf<CommandCard>()
        val logs = mutableListOf<ScriptLog>()
        val state = BattleState().also { it.nextStage() }
        val tracker = mockk<ServantTracker>()
        private val card: Card

        init {
            val execution = BattleExecution(config, chainShown)
            profileIndex?.let { execution.select(it) }
            val caster = mockk<Caster>(relaxed = true)
            every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
            every { caster.use(any<CommandCard.NP>()) } answers { taps += firstArg<CommandCard.NP>() }
            every { caster.canSpam(any()) } answers { firstArg<SpamEnum>() == SpamEnum.Spam }
            every { tracker.deployed } returns mapOf(
                FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C
            )
            every { tracker.getNpCardType(any()) } answers { npTypes[firstArg()] ?: CardTypeEnum.Unknown }
            every { tracker.npCardsDetectedUsingServantFaces() } returns detectedNps
            val api = mockk<IFgoAutomataApi>(relaxed = true)
            every { api.messages } returns object : IScriptMessages {
                override fun log(item: ScriptLog) {
                    logs += item
                }

                override fun notify(action: ScriptNotify) = Unit
            }
            card = Card(api, tracker, state, caster, mockk<CardParser>(), ApplyBraveChains(), execution)
        }

        fun click(hand: List<ParsedCard>, npUsage: NPUsage = NPUsage.none, stage: Int = 0, totalTurn: Int = 0, allowNpSpam: Boolean = true): List<CommandCard> {
            taps.clear()
            state.commandPosition = CommandPosition(stage, 0, totalTurn)
            card.clickCommandCards(hand, npUsage, allowNpSpam)
            return taps.toList()
        }

        val lastDecision get() = logs.filterIsInstance<ScriptLog.CardDecision>().last()
    }

    // Walkthrough example 1: 1A, 1B, 2B, 3B, 3Q with Arts > Buster > Quick.
    private val example1 = listOf(
        card(A, 1, CardTypeEnum.Arts),
        card(B, 1, CardTypeEnum.Buster),
        card(C, 2, CardTypeEnum.Buster),
        card(D, 3, CardTypeEnum.Buster),
        card(E, 3, CardTypeEnum.Quick)
    )

    @Test
    fun `example 1 without NP`() {
        assertThat(Harness(configOf(profile(chain = listOf(false)))).click(example1)).containsExactly(A, B, C)
        assertThat(Harness(configOf(profile())).click(example1)).containsExactly(A, B, E)
        assertThat(Harness(configOf(profile(order = "Buster,Mighty,Arts,Quick"))).click(example1)).containsExactly(B, C, D)
    }

    // Walkthrough example 2: Servant 1's Buster NP with 1B, 1A, 2B, 2Q, 3A.
    private val example2 = listOf(
        card(A, 1, CardTypeEnum.Buster),
        card(B, 1, CardTypeEnum.Arts),
        card(C, 2, CardTypeEnum.Buster),
        card(D, 2, CardTypeEnum.Quick),
        card(E, 3, CardTypeEnum.Arts)
    )
    private val np1 = NPUsage(setOf(CommandCard.NP.A), 0)
    private val busterNp = mapOf<TeamSlot, CardTypeEnum>(TeamSlot.A to CardTypeEnum.Buster)

    // The NP is tapped first and three face cards after it; the game takes the first three taps.
    @Test
    fun `example 2 with a Buster NP`() {
        val off = Harness(configOf(profile(chain = listOf(false))), busterNp).click(example2, np1)
        assertThat(off.take(3)).containsExactly(CommandCard.NP.A, B, E)

        val on = Harness(configOf(profile()), busterNp).click(example2, np1)
        assertThat(on.take(3)).containsExactly(CommandCard.NP.A, B, D)

        val withNp = Harness(configOf(profile(brave = BraveChainEnum.WithNP)), busterNp).click(example2, np1)
        assertThat(withNp.take(3)).containsExactly(CommandCard.NP.A, B, A)
    }

    @Test
    fun `the decision log gets a chain field only where chain priority is on`() {
        val config = configOf(profile(chain = listOf(true, false)))
        val harness = Harness(config, busterNp)

        harness.click(example2, np1, stage = 0)
        val chain = harness.lastDecision.chain
        assertThat(chain).isNotNull()
        assertThat(chain!!.formed).isEqualTo(ChainTypeEnum.Mighty)
        assertThat(chain.order).isEqualTo(ChainTypeEnum.defaultOrder)
        assertThat(chain.npTypes).isEqualTo(mapOf(FieldSlot.A to CardTypeEnum.Buster))

        harness.click(example2, np1, stage = 1)
        assertThat(harness.lastDecision.chain).isNull()
    }

    @Test
    fun `chain priority applies per wave entry`() {
        val harness = Harness(configOf(profile(chain = listOf(false, true))))

        assertThat(harness.click(example1, stage = 0)).containsExactly(A, B, C)
        assertThat(harness.click(example1, stage = 1)).containsExactly(A, B, E)
        // The last entry repeats.
        assertThat(harness.click(example1, stage = 2)).containsExactly(A, B, E)
    }

    @Test
    fun `chain priority applies per turn entry`() {
        val harness = Harness(configOf(profile(chain = listOf(true, false), split = CardPrioritySplit.Turn)))

        assertThat(harness.click(example1, stage = 1, totalTurn = 0)).containsExactly(A, B, E)
        assertThat(harness.click(example1, stage = 0, totalTurn = 1)).containsExactly(A, B, C)
    }

    @Test
    fun `a servant style entry ignores its chain priority switch`() {
        val styles = listOf(CardPriorityStyle.Servant, CardPriorityStyle.Affinity)
        val withChain = Harness(configOf(profile(chain = listOf(true), styles = styles)))
        val withoutChain = Harness(configOf(profile(chain = listOf(false), styles = styles)))

        assertThat(withChain.click(example1, stage = 0)).isEqualTo(withoutChain.click(example1, stage = 0))
        assertThat(withChain.lastDecision.chain).isNull()
        verify(exactly = 0) { withChain.tracker.getNpCardType(any()) }
        assertThat(withChain.click(example1, stage = 1)).containsExactly(A, B, E)
    }

    @Test
    fun `a direct cards turn ignores chain priority, also in its fallback places`() {
        val hand = listOf(
            card(A, 1, CardTypeEnum.Quick),
            card(B, 2, CardTypeEnum.Buster),
            card(C, 3, CardTypeEnum.Quick),
            card(D, 3, CardTypeEnum.Arts),
            card(E, 1, CardTypeEnum.Quick)
        )
        for (code in listOf("b4a", "?b4a", "?qq5", "?aaa")) {
            val sequence = DirectCardSequence.parse(code)
            val npUsage = NPUsage(sequence.nps, sequence.cardsBeforeNP, sequence)
            val on = Harness(configOf(profile(order = "Quick,Mighty")), busterNp)
            val off = Harness(configOf(profile(chain = listOf(false))), busterNp)

            assertThat(runCatching { on.click(hand, npUsage) }.getOrNull(), code)
                .isEqualTo(runCatching { off.click(hand, npUsage) }.getOrNull())
            verify(exactly = 0) { on.tracker.getNpCardType(any()) }
            verify(exactly = 0) { on.tracker.npCardsDetectedUsingServantFaces() }
        }
    }

    @Test
    fun `a stored chain priority on is ignored while the feature is hidden`() {
        val harness = Harness(configOf(profile()), chainShown = false)

        assertThat(harness.click(example1)).containsExactly(A, B, C)
        assertThat(harness.lastDecision.chain).isNull()
    }

    @Test
    fun `the production switch hides chain priority for now`() {
        assertThat(ChainPriorityAvailability.SHOWN).isFalse()
    }

    @Test
    fun `spam NPs join the chain plan only when their portrait is detected`() {
        // Servant 3 spams a Quick NP; 1Q, 2Q and two other cards are in hand.
        val hand = listOf(
            card(A, 1, CardTypeEnum.Quick),
            card(B, 2, CardTypeEnum.Quick),
            card(C, 1, CardTypeEnum.Arts),
            card(D, 2, CardTypeEnum.Buster),
            card(E, 1, CardTypeEnum.Arts)
        )
        val types = mapOf<TeamSlot, CardTypeEnum>(TeamSlot.C to CardTypeEnum.Quick)
        val config = configOf(profile(order = "Quick,Mighty"), spamSlot3 = true)

        val detected = Harness(config, types, detectedNps = setOf(CommandCard.NP.C))
        assertThat(detected.click(hand).take(3)).containsExactly(CommandCard.NP.C, A, B)
        assertThat(detected.lastDecision.chain!!.npTypes).isEqualTo(mapOf(FieldSlot.C to CardTypeEnum.Quick))

        val notDetected = Harness(config, types, detectedNps = emptySet())
        notDetected.click(hand)
        assertThat(notDetected.lastDecision.chain!!.npTypes).isEqualTo(emptyMap())
    }

    @Test
    fun `a turn that allows no NP spam never matches NP portraits`() {
        val harness = Harness(configOf(profile(), spamSlot3 = true))

        harness.click(example1, allowNpSpam = false)

        verify(exactly = 0) { harness.tracker.npCardsDetectedUsingServantFaces() }
    }

    @Test
    fun `each profile keeps its own chain priority`() {
        val config = configOf(profile(chain = listOf(false)), secondary = profile(chain = listOf(true)))

        assertThat(Harness(config, profileIndex = 0).click(example1)).containsExactly(A, B, C)
        assertThat(Harness(config, profileIndex = 1).click(example1)).containsExactly(A, B, E)
    }

    @Test
    fun `an Aoko card change counts with its new type`() {
        val hand = listOf(
            card(A, 1, CardTypeEnum.Arts).copy(swappedFrom = CardTypeEnum.Buster),
            card(B, 2, CardTypeEnum.Arts),
            card(C, 3, CardTypeEnum.Arts),
            card(D, 1, CardTypeEnum.Buster),
            card(E, 2, CardTypeEnum.Quick)
        )

        assertThat(Harness(configOf(profile(order = "Arts"))).click(hand)).containsExactly(A, B, C)
    }

    @Test
    fun `battle execution gives the chains of an entry, or null`() {
        val config = configOf(
            profile(
                chain = listOf(true, false, true),
                order = "Mighty,!Buster,Arts,Quick,!Avoid\nBuster\nAvoid,Quick",
                styles = listOf(CardPriorityStyle.Affinity, CardPriorityStyle.Affinity, CardPriorityStyle.Servant)
            )
        )
        val shown = BattleExecution(config, true).active
        assertThat(shown.chainPriorityAt(0)).isEqualTo(listOf(ChainTypeEnum.Mighty, ChainTypeEnum.Arts, ChainTypeEnum.Quick))
        assertThat(shown.chainPriorityAt(1)).isNull()
        assertThat(shown.chainPriorityAt(2)).isNull()

        val hidden = BattleExecution(config, false).active
        assertThat((0..2).map { hidden.chainPriorityAt(it) }).isEqualTo(listOf(null, null, null))
    }

    @Test
    fun `a saved Always stays Always only where chain priority is on`() {
        val config = configOf(profile(chain = listOf(true, false), brave = BraveChainEnum.Always))

        val shown = BattleExecution(config, true).active
        assertThat(shown.braveChainAt(0)).isEqualTo(BraveChainEnum.Always)
        assertThat(shown.braveChainAt(1)).isEqualTo(BraveChainEnum.WithNP)
        assertThat(BattleExecution(config, false).active.braveChainAt(0)).isEqualTo(BraveChainEnum.WithNP)
    }

    @Test
    fun `chain priority turns the face card check on, not on a servant style entry`() {
        fun input(styles: List<CardPriorityStyle>, chain: List<Boolean>) = FaceCardCheckPolicyTest.input(
            primary = FaceCardCheckPolicyTest.profile(styles = styles, chainPriorities = chain)
        )

        val on = input(emptyList(), listOf(false, true))
        assertThat(FaceCardCheckPolicy.decide(on).reason?.kind).isEqualTo(FaceCardReasonKind.ChainPriority)
        assertThat(FaceCardCheckPolicy.decide(on).reason?.entry).isEqualTo(1)
        assertThat(FaceCardCheckPolicy.usesChainPriority(on)).isTrue()

        val servantOnly = input(listOf(CardPriorityStyle.Servant), listOf(true))
        assertThat(FaceCardCheckPolicy.decide(servantOnly).reason?.kind).isEqualTo(FaceCardReasonKind.ServantStyle)
        assertThat(FaceCardCheckPolicy.usesChainPriority(servantOnly)).isFalse()

        val off = input(emptyList(), listOf(false))
        assertThat(FaceCardCheckPolicy.decide(off).enabled).isFalse()
        assertThat(FaceCardCheckPolicy.usesChainPriority(off)).isFalse()
    }

    @Test
    fun `the battle config is read without chain settings while the feature is hidden`() {
        val config = FaceCardCheckPolicyTest.config(
            primary = FaceCardCheckPolicyTest.profile(chainPriorities = listOf(true))
        )
        assertThat(FaceCardCheckPolicy.decide(FaceCardCheckPolicy.Input.of(config, chainShown = false)).enabled).isFalse()
        assertThat(FaceCardCheckPolicy.decide(FaceCardCheckPolicy.Input.of(config, chainShown = true)).reason?.kind)
            .isEqualTo(FaceCardReasonKind.ChainPriority)
    }

    @Test
    fun `the chain priority text keeps upstream's format`() {
        val text = "Mighty,!Buster,Arts,Quick,!Avoid\nBuster,Avoid"
        val parsed = ChainPriorityPerWave.of(text)
        assertThat(parsed.toString()).isEqualTo("Mighty,!Buster,Arts,Quick,!Avoid\nBuster,Avoid,!Mighty,!Arts,!Quick")
        assertThat(parsed.atWave(0)[1]).isEqualTo(ChainPriorityItem(ChainTypeEnum.Buster, isEnabled = false))
    }
}
