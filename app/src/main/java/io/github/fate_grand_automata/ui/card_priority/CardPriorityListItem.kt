package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.CardScore
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityItem
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityPerWave

data class CardPriorityListItem(
    val scores: SnapshotStateList<CardScore>,
    val servantPriority: SnapshotStateList<TeamSlot>,
    val servantCards: SnapshotStateList<ServantCardKey>,
    var rearrangeCards: MutableState<Boolean>,
    var braveChains: MutableState<BraveChainEnum>,
    /** Card ranking style of this entry only. */
    val style: MutableState<CardPriorityStyle>,
    /** Servant priority switch of this entry only. */
    val useServantPriority: MutableState<Boolean>,
    /** Chain Priority switch of this entry only (#654). */
    val useChainPriority: MutableState<Boolean> = mutableStateOf(false),
    val chainPriority: SnapshotStateList<ChainPriorityItem> =
        ChainPriorityPerWave.default.atWave(0).toMutableStateList()
)
