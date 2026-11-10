package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.ui.more.classLabel

/** 서포트 편성: the owner's own support decks, with the normal/event and deck number choices. */
@Composable
internal fun SupportFormation(formations: OwnFormations?, names: GameNameMap) {
    if (formations == null || !formations.hasSupport) {
        FormationNone()
        return
    }
    var event by rememberSaveable { mutableStateOf(false) }
    // A kind without decks cannot stay selected.
    val showEvent = event && formations.event.isNotEmpty() || formations.main.isEmpty()
    val decks = if (showEvent) formations.event else formations.main
    var deckIndex by rememberSaveable { mutableIntStateOf(1) }
    val deck = decks.firstOrNull { it.index == deckIndex } ?: decks.firstOrNull()
    if (formations.main.isNotEmpty() && formations.event.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = !showEvent, onClick = { event = false }, label = { Text(stringResource(R.string.dashboard_formation_main)) },
                modifier = Modifier.testTag("formation-kind-main"))
            FilterChip(selected = showEvent, onClick = { event = true }, label = { Text(stringResource(R.string.dashboard_formation_event)) },
                modifier = Modifier.testTag("formation-kind-event"))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("formation-decks")) {
        decks.forEach { option ->
            FilterChip(selected = option.index == deck?.index, onClick = { deckIndex = option.index },
                label = { Text(stringResource(R.string.dashboard_formation_deck, option.index)) })
        }
    }
    if (deck == null) {
        FormationNone()
        return
    }
    deck.slots.forEach { slot -> FormationRow(slot, null, names, classLabel(slot.classId)) }
}

/** 관위 편성: the Grand seats in seat order. */
@Composable
internal fun GrandFormation(grand: List<FriendGrandSlot>, names: GameNameMap) {
    grand.forEach { seat ->
        val atlas = names.servantData[seat.svtId]
        FormationRow(seat.supportSlot(), seat, names,
            atlas?.className?.let { dashboardClass(it) } ?: classLabel(seat.seatNumber))
    }
}

@Composable
private fun FormationNone() {
    Text(stringResource(R.string.dashboard_formation_none), modifier = Modifier.testTag("formation-none"))
}

@Composable
private fun FormationRow(slot: FriendSlot, grand: FriendGrandSlot?, names: GameNameMap, place: String) {
    Column(Modifier.fillMaxWidth().testTag("formation-row")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).then(
            grand?.let { Modifier.testTag("formation-seat:${it.seatNumber}") } ?: Modifier), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            AtlasIcon("servants", slot.svtId, names.servantData[slot.svtId]?.icon)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(names.servants[slot.svtId] ?: "#${slot.svtId}", Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (slot.grand) Text(stringResource(R.string.friends_grand),
                        Modifier.testTag("formation-grand-badge")
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1, softWrap = false)
                }
                Text(listOf(stringResource(R.string.friends2_level_compact, slot.lv),
                    stringResource(R.string.friends2_np_compact, slot.np), place).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FormationCe(slot.ceId, slot.ceLimitCount, slot.ceLv, names)
                if (grand != null || slot.ce2Id?.takeIf { it != 0L } != null) {
                    FormationCe(slot.ce2Id ?: 0, slot.ce2LimitCount ?: 0, slot.ce2Lv ?: 0, names)
                }
                grand?.let {
                    Text(stringResource(when (it.bondEffect) {
                        FriendBondEffect.NpCharge -> R.string.friends_bond_np
                        FriendBondEffect.Base -> R.string.friends_bond_base
                        FriendBondEffect.None -> R.string.friends_bond_none
                    }), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("formation-bond-effect"))
                }
            }
        }
        HorizontalDivider()
    }
}

/** Reserve the level/MLB suffix first: only the CE name may ellipsize. No CE image. */
@Composable
private fun FormationCe(id: Long, limitCount: Int, level: Int, names: GameNameMap) {
    Row(Modifier.fillMaxWidth().testTag("formation-ce-line"), verticalAlignment = Alignment.CenterVertically) {
        if (id == 0L) {
            Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, softWrap = false)
        } else {
            Text(names.equips[id] ?: "#$id", Modifier.weight(1f, fill = false).alignByBaseline(),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(" · ${stringResource(R.string.friends2_level_compact, level)}", Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            if (limitCount == FriendList.MAX_LIMIT_COUNT) {
                Text(" · ${stringResource(R.string.friends_ce_max)}", Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        }
    }
}
