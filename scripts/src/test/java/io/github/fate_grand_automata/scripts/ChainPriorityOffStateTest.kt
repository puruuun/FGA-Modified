package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.DirectCardSequence
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.NpSpamConfig
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.ChainPriorityAvailability
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * Issue #654: with Chain Priority off, the cards clicked must stay what the code before the feature clicked.
 * The fixture was recorded from `origin/main` before any production change (set FGA_RECORD_CHAIN654=1 to
 * re-record, which is only correct on that code).
 */
class ChainPriorityOffStateTest {
    private val fixturePath = "src/test/resources/chain654/off-state-card-choices.txt"
    private val EXTRA_FIXTURE = "src/test/resources/chain654/off-state-card-choices-extra.txt"

    private val affinityLists = listOf(
        "WB, WA, WQ, B, A, Q, RB, RA, RQ",
        "WA, A, RA, WQ, Q, RQ, WB, B, RB",
        "Q, A, B, WQ, WA, WB, RQ, RA, RB"
    )
    private val servantLists = listOf("3,2,1,4,5,6", "2,1,3,4,5,6", "1,3,2,4,5,6")
    private val servantCardLists = listOf("2B,2A,2Q,1B,1A,1Q,3B,3A,3Q", "1Q,3Q,2Q,1A,3A,2A,1B,3B,2B", "3B,1B,2B,3A,1A,2A,3Q,1Q,2Q")

    private fun profile(
        styles: List<CardPriorityStyle>,
        servantPriority: Boolean,
        brave: BraveChainEnum,
        rearrange: Boolean,
        split: CardPrioritySplit = CardPrioritySplit.Wave,
        rotate: Int = 0,
        chain: List<Boolean> = emptyList()
    ) = TestExecutionProfile(
        cardPriority = CardPriorityPerWave.of(List(3) { affinityLists[(it + rotate) % 3] }.joinToString("\n")),
        cardPrioritySplit = split,
        cardPriorityStyles = styles,
        servantCardPriority = ServantCardPriorityPerWave.of(List(3) { servantCardLists[(it + rotate) % 3] }.joinToString("\n")),
        useServantPriorities = listOf(servantPriority, !servantPriority),
        servantPriority = ServantPriorityPerWave.of(List(3) { servantLists[(it + rotate) % 3] }.joinToString("\n")),
        braveChains = listOf(brave, BraveChainEnum.None),
        rearrangeCards = listOf(rearrange, !rearrange),
        useChainPriorities = chain
    )

    private fun configOf(
        primary: TestExecutionProfile,
        secondary: TestExecutionProfile? = null,
        tertiary: TestExecutionProfile? = null,
        spam: Boolean = false
    ) = mockk<IBattleConfig>(relaxed = true).also { config ->
        fun stub(source: TestExecutionProfile, target: io.github.fate_grand_automata.scripts.prefs.IExecutionProfile) {
            every { target.skillCommand } returns source.skillCommand
            every { target.cardPriority } returns source.cardPriority
            every { target.cardPrioritySplit } returns source.cardPrioritySplit
            every { target.cardPriorityStyle } returns source.cardPriorityStyle
            every { target.cardPriorityStyles } returns source.cardPriorityStyles
            every { target.servantCardPriority } returns source.servantCardPriority
            every { target.useServantPriority } returns source.useServantPriority
            every { target.useServantPriorities } returns source.useServantPriorities
            every { target.servantPriority } returns source.servantPriority
            every { target.braveChains } returns source.braveChains
            every { target.rearrangeCards } returns source.rearrangeCards
            every { target.useChainPriorities } returns source.useChainPriorities
            every { target.chainPriority } returns source.chainPriority
        }
        stub(primary, config)
        every { config.spam } returns if (spam) listOf(
            ServantSpamConfig(), ServantSpamConfig(),
            ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(1, 2, 3)))
        ) else emptyList()
        if (secondary != null) {
            every { config.commandStartMode } returns CommandStartMode.CardCount
            every { config.secondaryStartEnabled } returns true
            every { config.secondaryExecution } returns secondary
            every { config.tertiaryStartEnabled } returns (tertiary != null)
            if (tertiary != null) every { config.tertiaryExecution } returns tertiary
        }
    }

    /** Shared by every clicker: mocks are slow to create and keep their calls, so there is one of each. */
    private object Mocks {
        val taps = mutableListOf<CommandCard>()
        var spam = false
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val caster = mockk<Caster>(relaxed = true).also { caster ->
            every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
            every { caster.use(any<CommandCard.NP>()) } answers { taps += firstArg<CommandCard.NP>() }
            every { caster.canSpam(any()) } answers { spam && firstArg<SpamEnum>() == SpamEnum.Spam }
        }

        // Strict: the off state may ask the tracker only for the deployed servants, as before.
        val tracker = mockk<ServantTracker>().also { tracker ->
            every { tracker.deployed } returns mapOf(
                FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C
            )
        }
        val parser = mockk<CardParser>()

        fun forget() = clearMocks(api, caster, tracker, answers = false, recordedCalls = true, childMocks = false)
    }

    private class Clicker(config: IBattleConfig, profileIndex: Int?, private val spam: Boolean, chainShown: Boolean) {
        val state = BattleState().also { it.nextStage() }
        private val card: Card

        init {
            val execution = BattleExecution(config, chainShown)
            profileIndex?.let { execution.select(it) }
            card = Card(Mocks.api, Mocks.tracker, state, Mocks.caster, Mocks.parser, ApplyBraveChains(), execution)
        }

        fun click(hand: List<ParsedCard>, stage: Int, totalTurn: Int, npUsage: NPUsage, allowNpSpam: Boolean): String {
            Mocks.taps.clear()
            Mocks.spam = spam
            state.commandPosition = CommandPosition(stage, 0, totalTurn)
            return try {
                card.clickCommandCards(hand, npUsage, allowNpSpam)
                Mocks.taps.joinToString(",") { it.label() }
            } catch (e: AutoBattle.BattleExitException) {
                "EXIT(${Mocks.taps.joinToString(",") { it.label() }})"
            } finally {
                Mocks.forget()
            }
        }
    }

    private val faces = CommandCard.Face.list

    private fun randomHand(random: Random): List<ParsedCard> = faces.map { face ->
        val slot = FieldSlot.list[random.nextInt(3)]
        val type = if (random.nextInt(10) == 0) CardTypeEnum.Unknown
        else listOf(CardTypeEnum.Buster, CardTypeEnum.Arts, CardTypeEnum.Quick)[random.nextInt(3)]
        ParsedCard(
            card = face,
            servant = when (slot) {
                FieldSlot.A -> TeamSlot.A
                FieldSlot.B -> TeamSlot.B
                FieldSlot.C -> TeamSlot.C
            },
            fieldSlot = slot,
            type = type,
            affinity = CardAffinityEnum.entries[random.nextInt(CardAffinityEnum.entries.size)],
            isStunned = random.nextInt(12) == 0
        )
    }

    private fun ParsedCard.label() =
        "${card.index}:${fieldSlot?.position}${type.name.first()}${affinity.name.first()}${if (isStunned) "s" else ""}"

    private val npUsages = listOf(
        NPUsage.none,
        NPUsage(setOf(CommandCard.NP.A), 0),
        NPUsage(setOf(CommandCard.NP.B), 1),
        NPUsage(setOf(CommandCard.NP.C), 2),
        NPUsage(setOf(CommandCard.NP.A, CommandCard.NP.B), 0),
        NPUsage(setOf(CommandCard.NP.B, CommandCard.NP.C), 1),
        NPUsage(setOf(CommandCard.NP.A, CommandCard.NP.B, CommandCard.NP.C), 0)
    )

    private fun NPUsage.label() =
        nps.joinToString("") { "${it.autoSkillCode}" } + "/" + cardsBeforeNP + (directCards?.let { "/$it" } ?: "")

    /** [chain] is the stored Chain Priority switch of every entry; [chainShown] the feature's availability. */
    private fun record(chainShown: Boolean = ChainPriorityAvailability.SHOWN, chain: List<Boolean> = emptyList()): List<String> {
        val random = Random(654)
        val lines = mutableListOf<String>()
        val styleSets = listOf(
            listOf(CardPriorityStyle.Affinity),
            listOf(CardPriorityStyle.Servant),
            listOf(CardPriorityStyle.Affinity, CardPriorityStyle.Servant)
        )
        val braves = listOf(BraveChainEnum.None, BraveChainEnum.WithNP, BraveChainEnum.Avoid)

        // Wave entries of the primary profile, with and without NP Spam.
        for (styles in styleSets) for (servantPriority in listOf(false, true)) for (brave in braves)
            for (rearrange in listOf(false, true)) for (spam in listOf(false, true)) {
                val clicker = Clicker(
                    configOf(profile(styles, servantPriority, brave, rearrange, chain = chain), spam = spam), null, spam, chainShown
                )
                for (npUsage in npUsages) for (stage in 0..2) repeat(2) {
                    val hand = randomHand(random)
                    val allowNpSpam = random.nextBoolean()
                    val result = clicker.click(hand, stage, 0, npUsage, allowNpSpam)
                    lines += "wave styles=${styles.joinToString("+")} sp=$servantPriority brave=$brave rearrange=$rearrange " +
                        "spam=$spam allow=$allowNpSpam stage=$stage np=${npUsage.label()} " +
                        "hand=${hand.joinToString(" ") { it.label() }} -> $result"
                }
            }

        // Turn entries, and the second and third card-count profiles.
        for (brave in braves) for (rearrange in listOf(false, true)) {
            val primary = profile(listOf(CardPriorityStyle.Affinity), false, brave, rearrange, CardPrioritySplit.Turn, chain = chain)
            val secondary = profile(listOf(CardPriorityStyle.Servant, CardPriorityStyle.Affinity), true, brave, !rearrange, rotate = 1, chain = chain)
            val tertiary = profile(listOf(CardPriorityStyle.Affinity), true, brave, rearrange, CardPrioritySplit.Turn, rotate = 2, chain = chain)
            for (profileIndex in 0..2) {
                val clicker = Clicker(configOf(primary, secondary, tertiary), profileIndex, false, chainShown)
                for (npUsage in npUsages) for (totalTurn in 0..3) {
                    val hand = randomHand(random)
                    val stage = random.nextInt(3)
                    val result = clicker.click(hand, stage, totalTurn, npUsage, true)
                    lines += "profile=$profileIndex brave=$brave rearrange=$rearrange stage=$stage turn=$totalTurn " +
                        "np=${npUsage.label()} hand=${hand.joinToString(" ") { it.label() }} -> $result"
                }
            }
        }

        // Direct cards, strict and with the priority fallback.
        val directCodes = listOf("baq", "b4a", "?b4a", "4ab", "?qq5", "?aaa", "?456", "ab6", "?b5b")
        for (styles in styleSets) for (servantPriority in listOf(false, true)) for (brave in braves) {
            val clicker = Clicker(configOf(profile(styles, servantPriority, brave, true, chain = chain), spam = true), null, true, chainShown)
            for (code in directCodes) repeat(3) {
                val sequence = DirectCardSequence.parse(code)
                val npUsage = NPUsage(sequence.nps, sequence.cardsBeforeNP, sequence)
                val hand = randomHand(random)
                val stage = random.nextInt(3)
                val result = clicker.click(hand, stage, 0, npUsage, true)
                lines += "direct styles=${styles.joinToString("+")} sp=$servantPriority brave=$brave stage=$stage " +
                    "np=${npUsage.label()} hand=${hand.joinToString(" ") { it.label() }} -> $result"
            }
        }
        return lines
    }

    /**
     * Stored Brave Chains [storedAlways] (Always on this branch; recorded on main with None, which is how main reads a
     * stored Always) and direct cards with unselected `p` places.
     */
    private fun recordExtra(storedAlways: BraveChainEnum): List<String> {
        val random = Random(2654)
        val lines = mutableListOf<String>()
        val styleSets = listOf(
            listOf(CardPriorityStyle.Affinity),
            listOf(CardPriorityStyle.Affinity, CardPriorityStyle.Servant)
        )
        for (styles in styleSets) for (servantPriority in listOf(false, true)) for (rearrange in listOf(false, true))
            for (spam in listOf(false, true)) {
                val clicker = Clicker(
                    configOf(profile(styles, servantPriority, storedAlways, rearrange), spam = spam), null, spam, false
                )
                for (npUsage in npUsages) for (stage in 0..1) repeat(2) {
                    val hand = randomHand(random)
                    val allowNpSpam = random.nextBoolean()
                    val result = clicker.click(hand, stage, 0, npUsage, allowNpSpam)
                    lines += "always styles=${styles.joinToString("+")} sp=$servantPriority rearrange=$rearrange " +
                        "spam=$spam allow=$allowNpSpam stage=$stage np=${npUsage.label()} " +
                        "hand=${hand.joinToString(" ") { it.label() }} -> $result"
                }
            }

        val directCodes = listOf("?bpp", "?p4p", "?ppp", "?pp6", "?aqp", "?5pp", "?p45")
        for (styles in styleSets) for (servantPriority in listOf(false, true))
            for (brave in listOf(BraveChainEnum.None, BraveChainEnum.WithNP)) {
                val clicker = Clicker(configOf(profile(styles, servantPriority, brave, true), spam = true), null, true, false)
                for (code in directCodes) repeat(3) {
                    val sequence = DirectCardSequence.parse(code)
                    val npUsage = NPUsage(sequence.nps, sequence.cardsBeforeNP, sequence)
                    val hand = randomHand(random)
                    val stage = random.nextInt(3)
                    val result = clicker.click(hand, stage, 0, npUsage, true)
                    lines += "direct-p styles=${styles.joinToString("+")} sp=$servantPriority brave=$brave stage=$stage " +
                        "np=${npUsage.label()} hand=${hand.joinToString(" ") { it.label() }} -> $result"
                }
            }
        return lines
    }

    private fun assertMatchesFixture(actual: List<String>, path: String = fixturePath) {
        val file = File(path)
        if (System.getenv("FGA_RECORD_CHAIN654") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual.joinToString("\n") + "\n")
        }
        val expected = file.readLines().filter { it.isNotEmpty() }
        assertThat(actual.size).isEqualTo(expected.size)
        actual.zip(expected).forEachIndexed { index, (a, e) ->
            assertThat(a, "line ${index + 1}").isEqualTo(e)
        }
    }

    @Test
    fun `a config that never touched chain priority picks as recorded on beta 15 with the production switch`() {
        assertMatchesFixture(record())
    }

    @Test
    fun `a stored Always while hidden and direct cards with unselected places match the recorded choices`() {
        assertMatchesFixture(recordExtra(BraveChainEnum.Always), EXTRA_FIXTURE)
    }

    @Test
    fun `a stored chain priority on is ignored while the feature is hidden`() {
        assertMatchesFixture(record(chainShown = false, chain = listOf(true)))
    }

    @Test
    fun `shown chain priority that is off in every entry picks as before`() {
        assertMatchesFixture(record(chainShown = true, chain = listOf(false, false)))
    }

    @Test
    fun `a saved Always acts as With NP where chain priority is shown but off`() {
        val random = Random(2)
        for (rearrange in listOf(false, true)) {
            val always = Clicker(
                configOf(profile(listOf(CardPriorityStyle.Affinity), false, BraveChainEnum.Always, rearrange)), null, false, true
            )
            val withNp = Clicker(
                configOf(profile(listOf(CardPriorityStyle.Affinity), false, BraveChainEnum.WithNP, rearrange)), null, false, true
            )
            for (npUsage in npUsages) repeat(20) {
                val hand = randomHand(random)
                assertThat(always.click(hand, 0, 0, npUsage, false), "$npUsage $hand")
                    .isEqualTo(withNp.click(hand, 0, 0, npUsage, false))
            }
        }
    }
}

private fun CommandCard.label() = when (this) {
    is CommandCard.NP -> "NP${autoSkillCode}"
    is CommandCard.Face -> "F${index}"
}
