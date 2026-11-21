package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*

/** Formations section: the owner's parties, then the support formation, Grand formation and mystic codes. */
@Composable
internal fun DashboardFormationsSection(data: DashboardData, names: GameNameMap) {
    if (data.parties.isNotEmpty()) {
        DashboardBlock(DashboardBlockId.Parties, stringResource(R.string.dashboard_formation_parties)) {
            PartyFormation(data.parties, names)
        }
    }
    DashboardBlock(DashboardBlockId.SupportFormation, stringResource(R.string.dashboard_formation_support)) {
        SupportFormation(data.formations, names)
    }
    if (data.formations?.grand?.isNotEmpty() == true) {
        DashboardBlock(DashboardBlockId.GrandFormation, stringResource(R.string.dashboard_formation_grand)) {
            GrandFormation(data.formations.grand, names)
        }
    }
    if (!data.equips.isNullOrEmpty()) {
        DashboardBlock(DashboardBlockId.MysticCodes, stringResource(R.string.dashboard_mystics)) {
            MysticCodes(data.equips, names)
        }
    }
}

@Composable private fun MysticCodes(equips: List<DashboardEquip>, names: GameNameMap) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(equips, key = { it.id }) { equip ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val metadata = names.mysticData[equip.id]
            AtlasIcon("mystics", equip.id, metadata?.icon)
            Column(Modifier.weight(1f)) {
                Text(metadata?.name ?: "#${equip.id}")
                equip.level?.let { Text(stringResource(R.string.dashboard_level_value, it)) }
                if (equip.equipped) Text(stringResource(R.string.dashboard_equipped), style = MaterialTheme.typography.labelSmall)
            }
        }
        }
    }
}
