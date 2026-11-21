package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Servants section. Its content and helpers stay inside this file so the servant rewrite touches nothing else. */
@Composable
internal fun DashboardServantsSection(data: DashboardData) {
    DashboardBlock(DashboardBlockId.Servants, stringResource(R.string.dashboard_servants)) { Servants(data) }
}

@Composable internal fun Servants(data: DashboardData) {
    val servants = data.servants.orEmpty()
    var sort by rememberSaveable { mutableStateOf(ServantSort.Bond) }
    var classLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var classMenu by remember { mutableStateOf(false) }
    // Deduplicate by the displayed label, including future beast variants and raw fallbacks.
    val classes = linkedMapOf<String, MutableSet<String>>()
    data.servantClasses.forEach { name -> classes.getOrPut(dashboardClass(name)) { linkedSetOf() }.add(name) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ServantSort.entries.forEach { option ->
            FilterChip(selected = sort == option, onClick = { sort = option }, label = {
                Text(stringResource(when (option) { ServantSort.Bond -> R.string.dashboard_sort_bond; ServantSort.Cost -> R.string.dashboard_sort_cost; ServantSort.Level -> R.string.dashboard_sort_level }))
            })
        }
        Box {
            OutlinedButton(onClick = { classMenu = true }) { Text(classLabel ?: stringResource(R.string.dashboard_all_classes)) }
            DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_all_classes)) }, onClick = { classLabel = null; classMenu = false })
                classes.keys.forEach { label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { classLabel = label; classMenu = false })
                }
            }
        }
    }
    var selected by remember { mutableStateOf(emptyList<DashboardServant>()) }
    val selectedClasses = classes[classLabel]?.toSet()
    LaunchedEffect(servants, sort, selectedClasses) { selected = withContext(Dispatchers.Default) { servants.selectServants(sort, selectedClasses) } }
    val shown = if (expanded) selected else selected.take(5)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (expanded) 560.dp else 280.dp).testTag("dashboard-servant-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.id }) { servant ->
            Row(Modifier.heightIn(min = 48.dp).testTag("dashboard-servant-row"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AtlasIcon("servants", servant.id, servant.atlas?.icon)
                Column(Modifier.weight(1f)) {
                    val parts = buildList {
                        servant.atlas?.className?.let { add(dashboardClass(it)) }
                        servant.atlas?.rarity?.let { add("★$it") }
                        servant.level?.let { add(stringResource(R.string.friends2_level_compact, it)) }
                        servant.bond?.let { add(stringResource(R.string.dashboard_bond, it)) }
                        servant.np?.let { add(stringResource(R.string.friends2_np_compact, it)) }
                    }
                    Text(buildAnnotatedString {
                        append(servant.name)
                        if (parts.isNotEmpty()) withStyle(MaterialTheme.typography.labelSmall.toSpanStyle()) {
                            append("  "); append(parts.joinToString(" · "))
                        }
                    }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.friends2_dashboard_skills,
                        (0..2).joinToString("/") { servant.skills.getOrNull(it)?.toString() ?: "–" },
                        (0..4).joinToString("/") { servant.appends.getOrNull(it)?.toString() ?: "–" }), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (expanded || selected.size > 5) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-servants-toggle")) {
        Text(if (expanded) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, selected.size))
    }
}
