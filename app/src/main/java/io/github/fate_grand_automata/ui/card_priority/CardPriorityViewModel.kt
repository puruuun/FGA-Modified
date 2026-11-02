package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import io.github.fate_grand_automata.scripts.models.CardPriority
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave

@HiltViewModel(assistedFactory = CardPriorityViewModel.Factory::class)
class CardPriorityViewModel @AssistedInject constructor(
    prefsCore: PrefsCore,
    @Assisted id: String,
    @Assisted val profileIndex: Int
) : ViewModel() {
    val battleConfig = prefsCore.forBattleConfig(id).executionProfile(profileIndex)

    @AssistedFactory
    interface Factory {
        fun create(id: String, profileIndex: Int = 0): CardPriorityViewModel
    }
    val cardPriorityItems: SnapshotStateList<CardPriorityListItem> by lazy {
        val cardPriority = battleConfig.cardPriority.get()
        val servantPriority = battleConfig.servantPriority.get()
        val servantCards = battleConfig.servantCardPriority.get()

        val rearrangeCards = battleConfig.rearrangeCards.get()
        val braveChains = battleConfig.braveChains.get()
        // Empty per-entry prefs mean a config saved before per-entry settings: use the old single values.
        val styles = PerEntrySettings.withLegacy(battleConfig.cardPriorityStyles.get(), battleConfig.cardPriorityStyle.get())
        val servantFlags = PerEntrySettings.withLegacy(battleConfig.useServantPriorities.get(), battleConfig.useServantPriority.get())

        cardPriority
            .take(CARD_PRIORITY_MAX_ENTRIES)
            .map { it.toMutableStateList() }
            .withIndex()
            .map {
                CardPriorityListItem(
                    it.value,
                    servantPriority.atWave(it.index).toMutableStateList(),
                    servantCards.atWave(it.index).toMutableStateList(),
                    mutableStateOf(rearrangeCards.getOrElse(it.index) { false }),
                    mutableStateOf(braveChains.getOrElse(it.index) { BraveChainEnum.None }),
                    mutableStateOf(PerEntrySettings.at(styles, it.index, CardPriorityStyle.Affinity)),
                    mutableStateOf(PerEntrySettings.at(servantFlags, it.index, false))
                )
            }
            .toMutableStateList()
    }

    val cardPrioritySplit = battleConfig.cardPrioritySplit

    fun save() {
        battleConfig.cardPriority.set(
            CardPriorityPerWave.from(
                cardPriorityItems.map { CardPriority.from(it.scores.toList()) }
            )
        )

        battleConfig.servantPriority.set(
            ServantPriorityPerWave.from(
                cardPriorityItems.map { it.servantPriority.toList() }
            )
        )

        battleConfig.servantCardPriority.set(
            ServantCardPriorityPerWave.from(
                cardPriorityItems.map { it.servantCards.toList() }
            )
        )

        battleConfig.rearrangeCards.set(cardPriorityItems.map { it.rearrangeCards.value })
        battleConfig.braveChains.set(cardPriorityItems.map { it.braveChains.value })
        // The old single prefs stay as they are; they only supply values for configs without these lists.
        battleConfig.cardPriorityStyles.set(cardPriorityItems.map { it.style.value })
        battleConfig.useServantPriorities.set(cardPriorityItems.map { it.useServantPriority.value })
    }
}