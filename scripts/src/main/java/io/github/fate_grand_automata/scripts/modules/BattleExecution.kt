package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.ChainTypeEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityPerWave
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Immutable action settings selected together, once per quest, after checking the hand. */
@ScriptScope
class BattleExecution(config: IBattleConfig, chainShown: Boolean) {
    @Inject constructor(config: IBattleConfig) : this(config, ChainPriorityAvailability.SHOWN)

    class Profile(source: IExecutionProfile, private val chainShown: Boolean = ChainPriorityAvailability.SHOWN) {
        val skillCommand = AutoSkillCommand.parse(source.skillCommand)
        val prioritySplit = source.cardPrioritySplit
        // An empty per-entry list means the profile predates per-entry settings: use the old single values.
        private val styles = PerEntrySettings.withLegacy(source.cardPriorityStyles, source.cardPriorityStyle)
        private val servantPriorityFlags =
            PerEntrySettings.withLegacy(source.useServantPriorities, source.useServantPriority)
        val priority = FaceCardPriority(
            source.cardPriority,
            source.servantPriority,
            styles,
            servantPriorityFlags,
            source.servantCardPriority
        )
        private val braveChains: List<BraveChainEnum> = source.braveChains.toList()
        private val rearrangeCards = source.rearrangeCards.toList()
        // Not read at all while the feature is hidden: a stored "on" then counts as off.
        private val chainFlags = if (chainShown) source.useChainPriorities.toList() else emptyList()
        private val chainOrder = if (chainShown) source.chainPriority else ChainPriorityPerWave.default

        fun styleAt(entry: Int) = priority.styleAt(entry)

        /** The chains to try at [entry], or null when Chain Priority is off there. A Servant style entry ignores it. */
        fun chainPriorityAt(entry: Int): List<ChainTypeEnum>? =
            if (styleAt(entry) == CardPriorityStyle.Servant || !PerEntrySettings.at(chainFlags, entry, false)) null
            else chainOrder.enabledAtWave(entry)

        /** The servant style ignores brave chains and rearrange for that entry only. */
        fun braveChainAt(entry: Int) =
            if (styleAt(entry) == CardPriorityStyle.Servant) BraveChainEnum.None
            else ChainPriorityAvailability.effectiveBraveChain(
                PerEntrySettings.at(braveChains, entry, BraveChainEnum.None), chainShown, chainPriorityAt(entry) != null
            )

        fun rearrangeAt(entry: Int) =
            styleAt(entry) != CardPriorityStyle.Servant && PerEntrySettings.at(rearrangeCards, entry, false)

        /** The index of the card priority, brave chain and rearrange entries to use. */
        fun priorityIndex(stage: Int, totalTurn: Int) =
            if (prioritySplit == CardPrioritySplit.Turn) totalTurn else stage
    }

    private val primary = Profile(config, chainShown)
    val spam = SpamConfigPerTeamSlot(config.spam.toList())
    val shuffleCards: ShuffleCardsEnum = config.shuffleCards
    val shuffleCardsWave = config.shuffleCardsWave
    private val secondary = if (config.commandStartMode == CommandStartMode.CardCount && config.secondaryStartEnabled)
        Profile(config.secondaryExecution, chainShown) else null
    private val tertiary = if (secondary != null && config.tertiaryStartEnabled)
        Profile(config.tertiaryExecution, chainShown) else null

    var selectedIndex: Int? = null
        private set
    // The first profile also supplies the shared waiting attack priorities.
    val active get() = when (selectedIndex) {
        1 -> checkNotNull(secondary)
        2 -> checkNotNull(tertiary)
        else -> primary
    }

    fun select(index: Int) {
        require(index == 0 || (index == 1 && secondary != null) || (index == 2 && tertiary != null))
        check(selectedIndex == null || selectedIndex == index) { "Execution profile is already selected" }
        selectedIndex = index
    }

    fun nextRun() {
        selectedIndex = null
    }
}
