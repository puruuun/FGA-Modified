package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.log.LogResource
import io.github.fate_grand_automata.ui.log.ResourceTile
import io.github.fate_grand_automata.util.grouped

@Composable
internal fun DashboardPresentBoxSection(presentBox: DashboardPresentBox, names: GameNameMap) {
    DashboardBlock(DashboardBlockId.PresentBox, stringResource(R.string.dashboard_present_box_header, presentBox.totalCount)) {
        PresentBox(presentBox, names)
    }
}

@Composable private fun PresentBox(presentBox: DashboardPresentBox, names: GameNameMap) {
    if (presentBox.entries.isEmpty()) {
        Text(
            stringResource(R.string.dashboard_present_box_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("dashboard-present-empty"),
        )
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) presentBox.entries else presentBox.entries.take(12)
    LazyVerticalGrid(
        GridCells.Adaptive(144.dp),
        Modifier.fillMaxWidth().heightIn(min = 48.dp, max = if (expanded) 380.dp else 360.dp).testTag("dashboard-present-grid"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(shown, key = { "${it.giftType}:${it.objectId}" }) { entry ->
            PresentTile(entry, names)
        }
    }
    if (expanded || presentBox.entries.size > 12) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-present-toggle")) {
            Text(if (expanded) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, presentBox.entries.size))
        }
    }
}

@Composable private fun PresentTile(entry: DashboardPresent, names: GameNameMap) {
    val id = entry.objectId
    when (entry.giftType) {
        2 -> ResourceTile(LogResource(id = id, count = entry.count), names, signed = false)
        11 -> ResourceTile(LogResource(LogResource.COMMAND_CODE, id, entry.count), names, signed = false)
        1 -> {
            val servant = names.servantData[id]
            val equip = if (servant == null) names.equipData[id] else null
            when {
                servant != null -> CompactResourceTile(
                    kind = "servants",
                    id = id,
                    name = servant.name ?: names.servants[id] ?: "#$id",
                    iconUrl = servant.icon,
                    countText = entry.count.grouped(),
                    tag = "present:1:$id",
                )
                equip != null -> CompactResourceTile(
                    kind = "servants",
                    id = id,
                    name = equip.name ?: names.equips[id] ?: "#$id",
                    iconUrl = equip.icon,
                    countText = entry.count.grouped(),
                    tag = "present:1:$id",
                )
                else -> ResourceTile(LogResource(LogResource.SVT, id, entry.count), names, signed = false)
            }
        }
        else -> CompactResourceTile(
            kind = null,
            id = id,
            name = "#$id",
            iconUrl = null,
            countText = entry.count.grouped(),
            tag = "present:${entry.giftType}:$id",
        )
    }
}
