package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle.Affinity
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle.Servant
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.DirectCardSequence
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.battle.CommandPosition
import io.github.fate_grand_automata.scripts.modules.ApplyBraveChains
import io.github.fate_grand_automata.scripts.modules.BattleExecution
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.CardParser
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.FaceCardPriority
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

/** Card priority style and servant priority are chosen per wave or turn entry. */
class PerEntryCardPriorityTest {
    private val A = CommandCard.Face.A
    private val B = CommandCard.Face.B
    private val C = CommandCard.Face.C
    private val D = CommandCard.Face.D
    private val E = CommandCard.Face.E

    private fun card(face: CommandCard.Face, slot: FieldSlot, type: CardTypeEnum) = ParsedCard(
        card = face,
        servant = when (slot) {
            FieldSlot.A -> TeamSlot.A
            FieldSlot.B -> TeamSlot.B
            FieldSlot.C -> TeamSlot.C
        },
        fieldSlot = slot,
        type = type,
        affinity = CardAffinityEnum.Normal
    )

    /** 1B, 2A, 1A, 3Q, 2B */
    private val hand = listOf(
        card(A, FieldSlot.A, CardTypeEnum.Buster),
        card(B, FieldSlot.B, CardTypeEnum.Arts),
        card(C, FieldSlot.A, CardTypeEnum.Arts),
        card(D, FieldSlot.C, CardTypeEnum.Quick),
        card(E, FieldSlot.B, CardTypeEnum.Buster)
    )

    private val affinityList = "B, A, Q, WB, WA, WQ, RB, RA, RQ"
    private val servantList = "2B,2A,2Q,1B,1A,1Q,3B,3A,3Q"

    // Affinity: buster, arts, quick.
    private val affinityOrder = listOf(A, E, B, C, D)
    // Servant list: 2B, 2A, 1B, 1A, 3Q.
    private val servantOrder = listOf(E, B, A, C, D)
    // Servant priority 3, 2, 1 first, then the affinity order inside each servant.
    private val servantPriorityOrder = listOf(D, E, B, A, C)

    private fun entries(n: Int, text: String) = List(n) { text }.joinToString("\n")

    private fun profile(
        styles: List<CardPriorityStyle> = emptyList(),
        flags: List<Boolean> = emptyList(),
        entryCount: Int = 2,
        split: CardPrioritySplit = CardPrioritySplit.Wave,
        rearrange: List<Boolean> = emptyList(),
        braveChains: List<BraveChainEnum> = emptyList(),
        legacyStyle: CardPriorityStyle = Affinity,
        legacyFlag: Boolean = false,
        skillCommand: String = ""
    ) = TestExecutionProfile(
        skillCommand = skillCommand,
        cardPriority = CardPriorityPerWave.of(entries(entryCount, affinityList)),
        cardPrioritySplit = split,
        cardPriorityStyle = legacyStyle,
        cardPriorityStyles = styles,
        servantCardPriority = ServantCardPriorityPerWave.of(entries(entryCount, servantList)),
        useServantPriority = legacyFlag,
        useServantPriorities = flags,
        servantPriority = ServantPriorityPerWave.of(entries(entryCount, "3,2,1,4,5,6")),
        braveChains = braveChains,
        rearrangeCards = rearrange
    )

    private fun configOf(primary: TestExecutionProfile, secondary: TestExecutionProfile? = null) =
        mockk<IBattleConfig>(relaxed = true).also { config ->
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
            every { config.spam } returns emptyList()
            if (secondary != null) {
                every { config.commandStartMode } returns io.github.fate_grand_automata.scripts.enums.CommandStartMode.CardCount
                every { config.secondaryStartEnabled } returns true
                every { config.secondaryExecution } returns secondary
            }
        }

    private class Clicker(config: IBattleConfig, secondaryIndex: Int? = null) {
        val taps = mutableListOf<CommandCard>()
        val state = BattleState().also { it.nextStage() }
        val execution = BattleExecution(config)
        private val card: Card

        init {
            secondaryIndex?.let { execution.select(it) }
            val caster = mockk<Caster>(relaxed = true)
            every { caster.use(any<CommandCard.Face>()) } answers { taps += firstArg<CommandCard.Face>() }
            every { caster.use(any<CommandCard.NP>()) } answers { taps += firstArg<CommandCard.NP>() }
            val tracker = mockk<ServantTracker>()
            every { tracker.deployed } returns mapOf(
                FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C
            )
            card = Card(mockk(relaxed = true), tracker, state, caster, mockk<CardParser>(), ApplyBraveChains(), execution)
        }

        fun click(hand: List<ParsedCard>, stage: Int, totalTurn: Int = 0, npUsage: NPUsage = NPUsage.none): List<CommandCard> {
            taps.clear()
            state.commandPosition = CommandPosition(stage, 0, totalTurn)
            card.clickCommandCards(hand, npUsage, allowNpSpam = false)
            return taps.toList()
        }
    }

    private fun clicker(primary: TestExecutionProfile) = Clicker(configOf(primary))

    @Test
    fun `mixed styles pick differently per wave`() {
        val c = clicker(profile(styles = listOf(Affinity, Servant)))

        assertThat(c.click(hand, 0)).containsExactly(*affinityOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 1)).containsExactly(*servantOrder.take(3).toTypedArray())
    }

    @Test
    fun `servant priority applies only to the entries whose switch is on`() {
        val c = clicker(profile(flags = listOf(true, false)))

        assertThat(c.click(hand, 0)).containsExactly(*servantPriorityOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 1)).containsExactly(*affinityOrder.take(3).toTypedArray())
    }

    @Test
    fun `servant priority switch is ignored on a servant style entry`() {
        val c = clicker(profile(styles = listOf(Servant, Affinity), flags = listOf(true, true)))

        assertThat(c.click(hand, 0)).containsExactly(*servantOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 1)).containsExactly(*servantPriorityOrder.take(3).toTypedArray())
    }

    @Test
    fun `rearrange is applied on affinity entries and skipped only on servant entries`() {
        val c = clicker(profile(styles = listOf(Affinity, Servant, Affinity), entryCount = 3, rearrange = listOf(true, true, true)))

        // Rearrange swaps the last two of the first three picks.
        assertThat(c.click(hand, 0)).containsExactly(A, B, E)
        assertThat(c.click(hand, 1)).containsExactly(*servantOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 2)).containsExactly(A, B, E)
    }

    @Test
    fun `brave chains are skipped only on servant entries`() {
        val chains = listOf(BraveChainEnum.WithNP, BraveChainEnum.WithNP, BraveChainEnum.Avoid)
        val active = BattleExecution(
            configOf(profile(styles = listOf(Affinity, Servant, Affinity), entryCount = 3, braveChains = chains, rearrange = listOf(true, true, false)))
        ).active

        assertThat(active.braveChainAt(0)).isEqualTo(BraveChainEnum.WithNP)
        assertThat(active.braveChainAt(1)).isEqualTo(BraveChainEnum.None)
        assertThat(active.braveChainAt(2)).isEqualTo(BraveChainEnum.Avoid)
        assertThat(active.rearrangeAt(0)).isEqualTo(true)
        assertThat(active.rearrangeAt(1)).isEqualTo(false)
        assertThat(active.rearrangeAt(2)).isEqualTo(false)
    }

    @Test
    fun `turn split selects the style by the run turn`() {
        val c = clicker(profile(styles = listOf(Affinity, Servant), split = CardPrioritySplit.Turn))

        // The wave is the same, only the total turn moves the entry.
        assertThat(c.click(hand, 0, totalTurn = 0)).containsExactly(*affinityOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 0, totalTurn = 1)).containsExactly(*servantOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 0, totalTurn = 7)).containsExactly(*servantOrder.take(3).toTypedArray())
    }

    @Test
    fun `the last entry repeats for style and servant priority`() {
        val styled = clicker(profile(styles = listOf(Affinity, Servant), entryCount = 2))
        assertThat(styled.click(hand, 5)).containsExactly(*servantOrder.take(3).toTypedArray())

        val flagged = FaceCardPriority(
            CardPriorityPerWave.of(affinityList),
            ServantPriorityPerWave.of("3,2,1,4,5,6"),
            listOf(Affinity),
            listOf(false, true)
        )
        assertThat(flagged.sort(hand, 0).map { it.card }).isEqualTo(affinityOrder)
        assertThat(flagged.sort(hand, 1).map { it.card }).isEqualTo(servantPriorityOrder)
        assertThat(flagged.sort(hand, 9).map { it.card }).isEqualTo(servantPriorityOrder)
    }

    @Test
    fun `empty per entry prefs fall back to the old single values for every entry`() {
        val legacyServant = BattleExecution(configOf(profile(legacyStyle = Servant, entryCount = 3))).active
        for (entry in 0..5) {
            assertThat(legacyServant.styleAt(entry)).isEqualTo(Servant)
        }
        assertThat(legacyServant.priority.sort(hand, 2).map { it.card }).isEqualTo(servantOrder)

        val legacyFlag = BattleExecution(configOf(profile(legacyFlag = true, entryCount = 3))).active
        for (entry in 0..5) {
            assertThat(legacyFlag.priority.useServantPriorityAt(entry)).isEqualTo(true)
            assertThat(legacyFlag.priority.sort(hand, entry).map { it.card }).isEqualTo(servantPriorityOrder)
        }

        val legacyOff = BattleExecution(configOf(profile())).active
        assertThat(legacyOff.priority.sort(hand, 1).map { it.card }).isEqualTo(affinityOrder)
    }

    @Test
    fun `stored per entry values win over the old single values`() {
        val active = BattleExecution(
            configOf(profile(styles = listOf(Affinity), flags = listOf(false), legacyStyle = Servant, legacyFlag = true))
        ).active

        assertThat(active.styleAt(3)).isEqualTo(Affinity)
        assertThat(active.priority.useServantPriorityAt(3)).isEqualTo(false)
    }

    @Test
    fun `secondary profile uses its own per entry values`() {
        val primary = profile(styles = listOf(Affinity))
        val secondary = profile(styles = listOf(Servant, Affinity), flags = listOf(false, true), skillCommand = "d5")
        val c = Clicker(configOf(primary, secondary), secondaryIndex = 1)

        assertThat(c.click(hand, 0)).containsExactly(*servantOrder.take(3).toTypedArray())
        assertThat(c.click(hand, 1)).containsExactly(*servantPriorityOrder.take(3).toTypedArray())
    }

    @Test
    fun `direct cards unselected fallback follows the style of the entry`() {
        val c = clicker(profile(styles = listOf(Affinity, Servant)))
        val usage = AutoSkillAction.Atk.direct(DirectCardSequence.parse("?aaa")).toNPUsage()

        // 2A and 1A are the only Arts cards; the third pick comes from the entry's own ranking.
        assertThat(c.click(hand, 0, npUsage = usage).toSet()).isEqualTo(setOf(B, C, A))
        assertThat(c.click(hand, 1, npUsage = usage).toSet()).isEqualTo(setOf(B, C, E))
    }

    // ---- storage text ----

    @Test
    fun `blank stored text is an empty list so the old values apply`() {
        assertThat(PerEntrySettings.parseStyles("")).isEmpty()
        assertThat(PerEntrySettings.parseFlags("  ")).isEmpty()
        assertThat(PerEntrySettings.withLegacy(PerEntrySettings.parseStyles(""), Servant)).isEqualTo(listOf(Servant))
        assertThat(PerEntrySettings.withLegacy(PerEntrySettings.parseFlags(""), true)).isEqualTo(listOf(true))
    }

    @Test
    fun `stored text round trips and tolerates bad entries`() {
        val styles = listOf(Affinity, Servant, Servant)
        assertThat(PerEntrySettings.serializeStyles(styles)).isEqualTo("Affinity,Servant,Servant")
        assertThat(PerEntrySettings.parseStyles("Affinity,Servant,Servant")).isEqualTo(styles)
        assertThat(PerEntrySettings.parseStyles("Servant,Nonsense")).isEqualTo(listOf(Servant, Affinity))

        val flags = listOf(true, false)
        assertThat(PerEntrySettings.serializeFlags(flags)).isEqualTo("true,false")
        assertThat(PerEntrySettings.parseFlags("true,false")).isEqualTo(flags)
    }
}
