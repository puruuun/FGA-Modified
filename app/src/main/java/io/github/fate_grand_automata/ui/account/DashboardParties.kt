package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
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
import io.github.fate_grand_automata.capture.*

/** The icon spot of a servant row: a 40 dp icon and the 8 dp gap, so text lines up across every row. */
private val IconSize = 40.dp

/** 파티 편성: one party at a time, chosen by chips like the support formations; only parties with a servant get a chip. */
@Composable
internal fun PartyFormation(parties: List<DashboardParty>, names: GameNameMap) {
    // The party number is what survives a configuration change; a number that is gone falls back to the first party.
    var chosen by rememberSaveable { mutableLongStateOf(-1L) }
    val party = parties.firstOrNull { it.deckNo == chosen } ?: parties.firstOrNull() ?: return
    Column(Modifier.fillMaxWidth().testTag("dashboard-parties")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("dashboard-party-chips")) {
            parties.forEach { option ->
                FilterChip(
                    selected = option.deckNo == party.deckNo, onClick = { chosen = option.deckNo },
                    label = { Text(stringResource(R.string.dashboard_party_chip, option.deckNo.toInt())) },
                    modifier = Modifier.testTag("dashboard-party-chip:${option.deckNo}"),
                )
            }
        }
        PartyCard(party, names)
    }
}

@Composable
private fun PartyCard(party: DashboardParty, names: GameNameMap) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("dashboard-party:${party.deckNo}")) {
        val title = party.name?.let { stringResource(R.string.dashboard_party_title, party.deckNo.toInt(), it) }
            ?: stringResource(R.string.dashboard_party_title_plain, party.deckNo.toInt())
        Text(title, Modifier.testTag("dashboard-party-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        party.slots.forEach { slot ->
            if (slot.support) PartySupportRow() else PartyMemberRow(slot, names)
        }
        party.mysticCodeId?.let { id -> PartyMysticRow(id, names) }
    }
}

@Composable
private fun PartyMemberRow(slot: DashboardPartySlot, names: GameNameMap) {
    val svtId = slot.svtId ?: return
    val atlas = names.servantData[svtId]
    val detail = listOfNotNull(
        slot.level?.let { stringResource(R.string.friends2_level_compact, it.toInt()) },
        slot.np?.let { stringResource(R.string.friends2_np_compact, it.toInt()) },
        atlas?.className?.let { dashboardClass(it) },
    ).joinToString(" · ")
    ResponsiveSlotRow(
        svtId = svtId, svtName = names.servants[svtId] ?: atlas?.name ?: "#$svtId", detail = detail, names = names, rowTag = "dashboard-party-row",
    ) { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // A CE that is not in the owned list is unknown, which is not the same as no CE.
            if (slot.ces.isEmpty() && !slot.ceMissing) FormationCe(0, 0, null, names)
            slot.ces.forEach { ce -> FormationCe(ce.svtId, ce.limitCount?.toInt() ?: 0, ce.level?.toInt(), names) }
        }
    }
}

/** An outlined square in the icon spot, for rows that have no image of their own. */
@Composable
private fun IconPlaceholder(content: @Composable BoxScope.() -> Unit = {}) {
    Surface(
        Modifier.size(IconSize), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content) }
}

/** The support slot holds a friend's servant, which the account state does not carry, so it is only marked. */
@Composable
private fun PartySupportRow() {
    Column(Modifier.fillMaxWidth().testTag("dashboard-party-support")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconPlaceholder {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.dashboard_party_support), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
    }
}

/** The icon sits in the same spot as a servant face; without one the outlined square keeps the text from floating. */
@Composable
private fun PartyMysticRow(id: Long, names: GameNameMap) {
    val metadata = names.mysticData[id]
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("dashboard-party-mystic"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconPlaceholder { AtlasIcon("mystics", id, metadata?.icon, size = IconSize) }
        Text(stringResource(R.string.dashboard_party_mystic, metadata?.name ?: "#$id"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
