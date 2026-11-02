package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Immutable action settings selected together, once per quest, after checking the hand. */
@ScriptScope
class BattleExecution @Inject constructor(config: IBattleConfig) {
    class Profile(source: IExecutionProfile) {
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

        fun styleAt(entry: Int) = priority.styleAt(entry)

        /** The servant style ignores brave chains and rearrange for that entry only. */
        fun braveChainAt(entry: Int) =
            if (styleAt(entry) == CardPriorityStyle.Servant) BraveChainEnum.None
            else PerEntrySettings.at(braveChains, entry, BraveChainEnum.None)

        fun rearrangeAt(entry: Int) =
            styleAt(entry) != CardPriorityStyle.Servant && PerEntrySettings.at(rearrangeCards, entry, false)

        /** The index of the card priority, brave chain and rearrange entries to use. */
        fun priorityIndex(stage: Int, totalTurn: Int) =
            if (prioritySplit == CardPrioritySplit.Turn) totalTurn else stage
    }

    private val primary = Profile(config)
    val spam = SpamConfigPerTeamSlot(config.spam.toList())
    val shuffleCards: ShuffleCardsEnum = config.shuffleCards
    val shuffleCardsWave = config.shuffleCardsWave
    private val secondary = if (config.commandStartMode == CommandStartMode.CardCount && config.secondaryStartEnabled)
        Profile(config.secondaryExecution) else null

    var selectedIndex: Int? = null
        private set
    // The first profile also supplies the shared waiting attack priorities.
    val active get() = if (selectedIndex == 1) checkNotNull(secondary) else primary

    fun select(index: Int) {
        require(index == 0 || (index == 1 && secondary != null))
        check(selectedIndex == null || selectedIndex == index) { "Execution profile is already selected" }
        selectedIndex = index
    }

    fun nextRun() {
        selectedIndex = null
    }
}
