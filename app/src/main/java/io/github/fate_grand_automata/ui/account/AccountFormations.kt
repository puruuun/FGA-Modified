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
import io.github.fate_grand_automata.ui.more.CeLine
import io.github.fate_grand_automata.ui.more.classLabel

private val COLUMN_WEIGHTS = listOf(.30f, .20f, .18f, .32f)
/** Phone width: the CE column drops below the other three. */
private val COMPACT_WEIGHTS = listOf(.44f, .24f, .32f)
private val COMPACT_WIDTH = 420.dp

/** 서포트 편성: the owner's own support decks, with the normal/event and deck number choices. */
@Composable
internal fun SupportFormation(formations: OwnFormations?, names: GameNameMap, updatedAt: Long) {
    FormationTime(updatedAt)
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
    FormationHeader()
    deck.slots.forEach { slot -> FormationRow(slot, null, names, classLabel(slot.classId)) }
}

/** 관위 편성: the Grand seats in seat order. */
@Composable
internal fun GrandFormation(grand: List<FriendGrandSlot>, names: GameNameMap, updatedAt: Long) {
    FormationTime(updatedAt)
    FormationHeader()
    grand.forEach { seat -> FormationRow(seat.supportSlot(), seat, names, stringResource(R.string.dashboard_formation_seat, seat.seatNumber)) }
}

@Composable
private fun FormationTime(updatedAt: Long) {
    Text(stringResource(R.string.dashboard_formation_updated, date(updatedAt)), style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.testTag("formation-time"))
}

@Composable
private fun FormationNone() {
    Text(stringResource(R.string.dashboard_formation_none), modifier = Modifier.testTag("formation-none"))
}

@Composable
private fun FormationHeader() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labels = listOf(R.string.friends2_name, R.string.friends2_skills, R.string.friends2_appends, R.string.friends2_ce)
            .let { if (maxWidth < COMPACT_WIDTH) it.take(3) else it }
        val weights = if (maxWidth < COMPACT_WIDTH) COMPACT_WEIGHTS else COLUMN_WEIGHTS
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 10.dp)) {
            labels.forEachIndexed { index, label ->
                Text(stringResource(label), Modifier.weight(weights[index]).padding(horizontal = 1.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The friend manager's row look without the friend name and login columns. [place] is the class or the seat. On a
 * narrow card the CE lines sit under the other columns instead of squeezing the servant name.
 */
@Composable
private fun FormationRow(slot: FriendSlot, grand: FriendGrandSlot?, names: GameNameMap, place: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("formation-row")) {
        val compact = maxWidth < COMPACT_WIDTH
        val weights = if (compact) COMPACT_WEIGHTS else COLUMN_WEIGHTS
        val ces: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                val twoCes = grand != null || slot.ce2Id?.takeIf { it != 0L } != null
                if (slot.ceId == 0L) Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else CeLine(names.equips[slot.ceId] ?: "#${slot.ceId}", slot.ceLimitCount, slot.ceLv, if (twoCes || compact) 1 else 2)
                if (grand != null && slot.ce2Id == null) Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall)
                slot.ce2Id?.takeIf { it != 0L }?.let { CeLine(names.equips[it] ?: "#$it", slot.ce2LimitCount ?: 0, slot.ce2Lv ?: 0) }
                grand?.let {
                    Text(stringResource(when (it.bondEffect) {
                        FriendBondEffect.NpCharge -> R.string.friends_bond_np
                        FriendBondEffect.Base -> R.string.friends_bond_base
                        FriendBondEffect.None -> R.string.friends_bond_none
                    }), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("formation-bond-effect"))
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = if (compact) 2.dp else 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(weights[0]).padding(end = 2.dp)) {
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
                    Text(stringResource(R.string.friends2_name_line, slot.lv, slot.np, place), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(slot.skills.joinToString("/"), Modifier.weight(weights[1]).padding(horizontal = 1.dp), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                Text((0..4).joinToString("/") { slot.appends.getOrNull(it)?.toString() ?: "-" },
                    Modifier.weight(weights[2]).padding(horizontal = 1.dp).testTag("formation-appends"),
                    style = MaterialTheme.typography.bodySmall, softWrap = true)
                if (!compact) ces(Modifier.weight(weights[3]).padding(start = 1.dp, end = 4.dp))
            }
            if (compact) ces(Modifier.fillMaxWidth().padding(bottom = 8.dp, end = 4.dp))
            HorizontalDivider()
        }
    }
}
