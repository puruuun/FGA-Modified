package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.modules.FaceCardCheckPolicy
import io.github.fate_grand_automata.scripts.modules.FaceCardCheckPolicy.Input
import io.github.fate_grand_automata.scripts.modules.FaceCardCheckPolicy.ProfileInput
import io.github.fate_grand_automata.scripts.modules.FaceCardReason
import io.github.fate_grand_automata.scripts.modules.FaceCardReasonKind
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceCardCheckPolicyTest {
    companion object {
        fun profile(
            skillCommand: String = "",
            entryCount: Int = 3,
            split: CardPrioritySplit = CardPrioritySplit.Wave,
            legacyStyle: CardPriorityStyle = CardPriorityStyle.Affinity,
            styles: List<CardPriorityStyle> = emptyList(),
            legacyServantPriority: Boolean = false,
            servantPriorities: List<Boolean> = emptyList(),
            braveChains: List<BraveChainEnum> = emptyList()
        ) = ProfileInput(
            skillCommand, entryCount, split, legacyStyle, styles, legacyServantPriority, servantPriorities, braveChains
        )

        fun input(
            commandStartMode: CommandStartMode = CommandStartMode.Basic,
            secondaryStartEnabled: Boolean = false,
            shuffleCards: ShuffleCardsEnum = ShuffleCardsEnum.None,
            primary: ProfileInput = profile(),
            secondary: ProfileInput = profile()
        ) = Input(commandStartMode, secondaryStartEnabled, shuffleCards, primary, secondary)

        private fun execution(p: ProfileInput): IExecutionProfile = mockk<IExecutionProfile>().also {
            every { it.skillCommand } returns p.skillCommand
            every { it.cardPriority } returns CardPriorityPerWave.default
            every { it.cardPrioritySplit } returns p.prioritySplit
            every { it.cardPriorityStyle } returns p.legacyStyle
            every { it.cardPriorityStyles } returns p.styles
            every { it.useServantPriority } returns p.legacyServantPriority
            every { it.useServantPriorities } returns p.servantPriorities
            every { it.braveChains } returns p.braveChains
        }

        /** A strict battle config mock for the settings the policy reads. */
        fun config(
            commandStartMode: CommandStartMode = CommandStartMode.Basic,
            secondaryStartEnabled: Boolean = false,
            shuffleCards: ShuffleCardsEnum = ShuffleCardsEnum.None,
            primary: ProfileInput = profile(),
            secondary: ProfileInput = profile()
        ): IBattleConfig {
            val config = mockk<IBattleConfig>()
            val first = execution(primary)
            every { config.commandStartMode } returns commandStartMode
            every { config.secondaryStartEnabled } returns secondaryStartEnabled
            every { config.shuffleCards } returns shuffleCards
            every { config.secondaryExecution } returns execution(secondary)
            every { config.skillCommand } answers { first.skillCommand }
            every { config.cardPriority } answers { first.cardPriority }
            every { config.cardPrioritySplit } answers { first.cardPrioritySplit }
            every { config.cardPriorityStyle } answers { first.cardPriorityStyle }
            every { config.cardPriorityStyles } answers { first.cardPriorityStyles }
            every { config.useServantPriority } answers { first.useServantPriority }
            every { config.useServantPriorities } answers { first.useServantPriorities }
            every { config.braveChains } answers { first.braveChains }
            return config
        }

        private const val HAKUNO = "aH{3:1b2}"
    }

    private fun assertReason(expected: FaceCardReason, input: Input) {
        val decision = FaceCardCheckPolicy.decide(input)
        assertTrue(decision.enabled)
        assertEquals(expected, decision.reason)
    }

    @Test
    fun `nothing needing faces turns the check off`() {
        val decision = FaceCardCheckPolicy.decide(input())
        assertFalse(decision.enabled)
        assertNull(decision.reason)
        assertEquals("face-card check: off", decision.toLogText())
    }

    @Test
    fun `servant style on an entry turns it on`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.ServantStyle, entry = 1),
            input(primary = profile(styles = listOf(CardPriorityStyle.Affinity, CardPriorityStyle.Servant)))
        )
    }

    @Test
    fun `servant priority on an affinity entry turns it on`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.ServantPriority, entry = 2),
            input(primary = profile(servantPriorities = listOf(false, false, true)))
        )
    }

    @Test
    fun `brave chains with NP or avoid turn it on and the log says where`() {
        for (chain in listOf(BraveChainEnum.WithNP, BraveChainEnum.Avoid)) {
            val decision = FaceCardCheckPolicy.decide(
                input(primary = profile(braveChains = listOf(BraveChainEnum.None, chain)))
            )
            assertEquals(FaceCardReason(FaceCardReasonKind.BraveChains, entry = 1), decision.reason)
            assertEquals("face-card check: on (brave chains, entry 2)", decision.toLogText())
        }
    }

    @Test
    fun `brave chains none does not count`() {
        assertFalse(
            FaceCardCheckPolicy.decide(input(primary = profile(braveChains = listOf(BraveChainEnum.None)))).enabled
        )
    }

    @Test
    fun `brave chains on a servant style entry do not count`() {
        // The only entry is Servant style, so the reason is the style, never the chains.
        val servantOnly = profile(
            entryCount = 1, styles = listOf(CardPriorityStyle.Servant), braveChains = listOf(BraveChainEnum.WithNP)
        )
        assertEquals(
            FaceCardReasonKind.ServantStyle,
            FaceCardCheckPolicy.decide(input(primary = servantOnly)).reason?.kind
        )
        // Entry 1 is Servant style with chains, entry 2 is Affinity with none: chains must not be reported.
        val mixed = profile(
            entryCount = 2,
            styles = listOf(CardPriorityStyle.Servant, CardPriorityStyle.Affinity),
            braveChains = listOf(BraveChainEnum.WithNP, BraveChainEnum.None)
        )
        assertEquals(
            FaceCardReasonKind.ServantStyle,
            FaceCardCheckPolicy.decide(input(primary = mixed)).reason?.kind
        )
    }

    @Test
    fun `NP matching shuffle turns it on`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.NpMatchingShuffle),
            input(shuffleCards = ShuffleCardsEnum.NoNPMatching)
        )
        assertFalse(FaceCardCheckPolicy.decide(input(shuffleCards = ShuffleCardsEnum.NoEffective)).enabled)
    }

    @Test
    fun `hakuno shuffle in the primary command turns it on`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.HakunoShuffle, profile = 0),
            input(primary = profile(skillCommand = HAKUNO))
        )
    }

    @Test
    fun `ordinary skills and an unparsable command do not count as hakuno shuffle`() {
        assertFalse(FaceCardCheckPolicy.decide(input(primary = profile(skillCommand = "abc,#,def"))).enabled)
        assertFalse(FaceCardCheckPolicy.decide(input(primary = profile(skillCommand = "aH{garbage"))).enabled)
    }

    @Test
    fun `card count start mode turns it on`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.CardCountStart),
            input(commandStartMode = CommandStartMode.CardCount)
        )
    }

    @Test
    fun `secondary profile counts only when active`() {
        val needy = profile(
            skillCommand = HAKUNO, styles = listOf(CardPriorityStyle.Servant), braveChains = listOf(BraveChainEnum.Avoid)
        )
        // Secondary start disabled, or not in card-count mode: ignored.
        assertFalse(
            FaceCardCheckPolicy.decide(input(secondaryStartEnabled = false, secondary = needy)).enabled
        )
        assertFalse(
            FaceCardCheckPolicy.decide(
                input(commandStartMode = CommandStartMode.Basic, secondaryStartEnabled = true, secondary = needy)
            ).enabled
        )
        // Card-count mode without the secondary start: only the card-count reason.
        assertEquals(
            FaceCardReasonKind.CardCountStart,
            FaceCardCheckPolicy.decide(
                input(commandStartMode = CommandStartMode.CardCount, secondaryStartEnabled = false, secondary = needy)
            ).reason?.kind
        )
        // Active: the secondary entry is the reason.
        assertReason(
            FaceCardReason(FaceCardReasonKind.ServantStyle, entry = 0, profile = 1),
            input(commandStartMode = CommandStartMode.CardCount, secondaryStartEnabled = true, secondary = needy)
        )
    }

    @Test
    fun `hakuno shuffle in the secondary command counts when active`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.HakunoShuffle, profile = 1),
            input(
                commandStartMode = CommandStartMode.CardCount,
                secondaryStartEnabled = true,
                secondary = profile(skillCommand = HAKUNO)
            )
        )
    }

    @Test
    fun `legacy single values apply when the per entry lists are empty`() {
        assertReason(
            FaceCardReason(FaceCardReasonKind.ServantStyle, entry = 0),
            input(primary = profile(legacyStyle = CardPriorityStyle.Servant))
        )
        assertReason(
            FaceCardReason(FaceCardReasonKind.ServantPriority, entry = 0),
            input(primary = profile(legacyServantPriority = true))
        )
    }

    @Test
    fun `per entry lists override the legacy values`() {
        val profile = profile(
            legacyStyle = CardPriorityStyle.Servant,
            styles = listOf(CardPriorityStyle.Affinity),
            legacyServantPriority = true,
            servantPriorities = listOf(false)
        )
        assertFalse(FaceCardCheckPolicy.decide(input(primary = profile)).enabled)
    }

    @Test
    fun `turn split reason is flagged`() {
        val reason = FaceCardCheckPolicy.decide(
            input(primary = profile(split = CardPrioritySplit.Turn, servantPriorities = listOf(true)))
        ).reason
        assertTrue(reason!!.turnSplit)
    }

    @Test
    fun `deciding from a battle config matches deciding from its input`() {
        val primary = profile(braveChains = listOf(BraveChainEnum.WithNP))
        assertEquals(
            FaceCardCheckPolicy.decide(input(primary = primary)),
            FaceCardCheckPolicy.decide(config(primary = primary))
        )
        assertEquals(
            FaceCardCheckPolicy.decide(input(commandStartMode = CommandStartMode.CardCount)),
            FaceCardCheckPolicy.decide(config(commandStartMode = CommandStartMode.CardCount))
        )
    }
}
