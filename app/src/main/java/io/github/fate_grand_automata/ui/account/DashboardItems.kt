package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.log.LogResource
import io.github.fate_grand_automata.ui.log.ResourceTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Items section. Its content and helpers stay inside this file so the item rewrite touches nothing else. */
@Composable
internal fun DashboardItemsSection(items: List<DashboardItem>, names: GameNameMap) {
    DashboardBlock(DashboardBlockId.Items, stringResource(R.string.dashboard_items)) { Inventory(items, names) }
}

@Composable private fun Inventory(items: List<DashboardItem>, names: GameNameMap) {
    var groups by remember { mutableStateOf(emptyMap<InventoryGroup, List<DashboardItem>>()) }
    LaunchedEffect(items) { groups = withContext(Dispatchers.Default) { items.groupBy { it.group } } }
    InventoryGroup.entries.forEach { group ->
        val rows = groups[group].orEmpty()
        if (rows.isNotEmpty()) key(group) {
            var expanded by rememberSaveable { mutableStateOf(group !in setOf(InventoryGroup.Event, InventoryGroup.Coins, InventoryGroup.Other)) }
            TextButton(onClick = { expanded = !expanded }) {
                Text("${if (expanded) "−" else "+"} ${stringResource(group.label())} (${rows.size})")
            }
            if (expanded) LazyVerticalGrid(GridCells.Adaptive(144.dp), Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { ResourceTile(LogResource(id = it.id, count = it.count), names, signed = false) }
            }
        }
    }
}

private fun InventoryGroup.label() = when (this) {
    InventoryGroup.Materials -> R.string.dashboard_group_materials
    InventoryGroup.Gems -> R.string.dashboard_group_gems
    InventoryGroup.Pieces -> R.string.dashboard_group_pieces
    InventoryGroup.Grails -> R.string.dashboard_group_grails
    InventoryGroup.Event -> R.string.dashboard_group_event
    InventoryGroup.Coins -> R.string.dashboard_group_coins
    InventoryGroup.Other -> R.string.dashboard_group_other
}
