package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
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
        val style = source.cardPriorityStyle
        private val servantStyle = style == CardPriorityStyle.Servant
        // The servant style ignores the servant priority toggle, brave chains and rearrange.
        val priority = FaceCardPriority(
            source.cardPriority,
            source.servantPriority.takeIf { source.useServantPriority && !servantStyle },
            style,
            source.servantCardPriority
        )
        val braveChains: List<BraveChainEnum> = if (servantStyle) emptyList() else source.braveChains.toList()
        val rearrangeCards = if (servantStyle) emptyList() else source.rearrangeCards.toList()

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
