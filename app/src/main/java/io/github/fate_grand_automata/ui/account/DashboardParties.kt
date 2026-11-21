package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*

/** 파티 편성: the owner's own parties that have at least one servant, in party-number order. */
@Composable
internal fun PartyFormation(parties: List<DashboardParty>, names: GameNameMap) {
    Column(Modifier.fillMaxWidth().testTag("dashboard-parties"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        parties.forEach { party -> PartyCard(party, names) }
    }
}

@Composable
private fun PartyCard(party: DashboardParty, names: GameNameMap) {
    Column(Modifier.fillMaxWidth().testTag("dashboard-party:${party.deckNo}")) {
        val title = party.name?.let { stringResource(R.string.dashboard_party_title, party.deckNo.toInt(), it) }
            ?: stringResource(R.string.dashboard_party_title_plain, party.deckNo.toInt())
        Text(title, Modifier.testTag("dashboard-party-title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        party.slots.forEach { slot ->
            if (slot.support) PartySupportRow() else PartyMemberRow(slot, names)
        }
        party.mysticCodeId?.let { id ->
            val metadata = names.mysticData[id]
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("dashboard-party-mystic"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AtlasIcon("mystics", id, metadata?.icon)
                Text(stringResource(R.string.dashboard_party_mystic, metadata?.name ?: "#$id"), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
            if (slot.ces.isEmpty()) FormationCe(0, 0, null, names)
            slot.ces.forEach { ce -> FormationCe(ce.svtId, ce.limitCount?.toInt() ?: 0, ce.level?.toInt(), names) }
        }
    }
}

/** The support slot holds a friend's servant, which the account state does not carry, so it is only marked. */
@Composable
private fun PartySupportRow() {
    Column(Modifier.fillMaxWidth().testTag("dashboard-party-support")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_party_support), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}
