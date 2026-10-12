package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
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
        val priority = FaceCardPriority(source.cardPriority, source.servantPriority.takeIf { source.useServantPriority })
        val braveChains: List<BraveChainEnum> = source.braveChains.toList()
        val rearrangeCards = source.rearrangeCards.toList()
        val spam = SpamConfigPerTeamSlot(source.spam.toList())
        val shuffleCards: ShuffleCardsEnum = source.shuffleCards
        val shuffleCardsWave = source.shuffleCardsWave
    }

    private val primary = Profile(config)
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
