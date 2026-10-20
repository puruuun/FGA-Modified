package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import io.github.fate_grand_automata.scripts.weekly.RouteItem
import io.github.fate_grand_automata.scripts.weekly.RoutePlan
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteSolver
import io.github.fate_grand_automata.ui.FgaScreen
import io.github.fate_grand_automata.ui.Stepper

/** What the user confirmed: the route quests with their counts (above zero), the manual missions and the battle config. */
data class WeeklyRouteChoice(val items: List<RouteItem>, val manual: List<String>, val config: IBattleConfig)

private fun oneLine(text: String) = text.replace('\n', ' ')

/**
 * D-1 route confirm dialog. The route is recomputed whenever a chapter is switched off; the counts can be edited.
 * Confirming starts the whole route (design 0009 step 2): every quest whose count is still above zero.
 */
@Composable
fun WeeklyRouteScreen(
    data: AtlasRouteRepository.Dataset,
    prefs: IPreferences,
    onResult: (WeeklyRouteChoice?) -> Unit
) {
    val configs = remember { eligibleBattleConfigs(prefs) }
    val configNames = remember(configs) { configs.map { it.name } }
    var configIndex by remember { mutableStateOf(configs.indexOf(prefs.selectedBattleConfig)) }

    val chapters = remember(data) {
        data.quests.groupBy { it.warId }.values
            .map { it.first() }
            .sortedWith(WeeklyRouteSolver.order)
            .map { it.warId to oneLine(it.chapter) }
    }
    var excluded by remember { mutableStateOf(emptySet<Int>()) }

    val plan: Result<RoutePlan> = remember(data, excluded) {
        runCatching { WeeklyRouteSolver.solve(data.week.missions, data.quests, excluded + WeeklyRouteSolver.MICTLAN_WAR) }
    }
    val counts = remember(plan) {
        mutableStateMapOf<Int, Int>().apply { plan.getOrNull()?.items?.forEach { put(it.quest.id, it.count) } }
    }
    val items = plan.getOrNull()?.items.orEmpty()
    val manual = plan.getOrNull()?.manual.orEmpty()
    val manualIds = manual.map { it.id }.toSet()
    val totalAp = items.sumOf { it.quest.ap.toLong() * (counts[it.quest.id] ?: 0) }
    val chosen = items.filter { (counts[it.quest.id] ?: 0) > 0 }.map { RouteItem(it.quest, counts.getValue(it.quest.id)) }

    FgaScreen {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    Text(stringResource(R.string.weekly_route_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.weekly_route_header, data.server, data.week.id),
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }

                item { Section(stringResource(R.string.weekly_route_missions)) }
                items(data.week.missions.filter { !it.meta && it.id !in manualIds }) {
                    Text("· ${oneLine(it.name)}", style = MaterialTheme.typography.bodySmall)
                }

                if (manual.isNotEmpty()) {
                    item { Section(stringResource(R.string.weekly_route_manual)) }
                    items(manual) { Text("· ${oneLine(it.name)}", style = MaterialTheme.typography.bodySmall) }
                }

                item { Section(stringResource(R.string.weekly_route_route)) }
                plan.exceptionOrNull()?.let { error ->
                    item {
                        Text(
                            stringResource(R.string.weekly_route_solve_failed, error.message.orEmpty()),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (plan.isSuccess && items.isEmpty()) {
                    item { Text(stringResource(R.string.weekly_route_route_empty), style = MaterialTheme.typography.bodySmall) }
                }
                items(items, key = { it.quest.id }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.weekly_route_quest_line,
                                oneLine(item.quest.chapter), item.quest.spot, item.quest.name, item.quest.ap
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Stepper(
                            value = counts[item.quest.id] ?: 0,
                            onValueChange = { counts[item.quest.id] = it },
                            valueRange = 0..999
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.weekly_route_total_ap, totalAp),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(stringResource(R.string.weekly_route_start_note), style = MaterialTheme.typography.bodySmall)
                    if (data.server == "KR") {
                        Text(stringResource(R.string.weekly_route_kr_note), style = MaterialTheme.typography.bodySmall)
                    }
                    val dropped = data.mismatched.size + data.missing.size
                    if (dropped > 0) {
                        Text(stringResource(R.string.weekly_route_dropped, dropped), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.weekly_route_mictlan), style = MaterialTheme.typography.bodySmall)
                }

                item { Section(stringResource(R.string.weekly_route_chapters)) }
                items(chapters, key = { it.first }) { (war, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { excluded = if (war in excluded) excluded - war else excluded + war }
                    ) {
                        Checkbox(
                            checked = war !in excluded,
                            onCheckedChange = { excluded = if (it) excluded - war else excluded + war }
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item { Section(stringResource(R.string.weekly_route_battle_config)) }
                if (configs.isEmpty()) {
                    item { Text(stringResource(R.string.battle_config_list_no_items)) }
                }
                items(configs.indices.toList()) { index ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { configIndex = index }
                    ) {
                        RadioButton(selected = configIndex == index, onClick = { configIndex = index })
                        Text(configNames[index], style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onResult(null) }) { Text(stringResource(android.R.string.cancel)) }
                TextButton(
                    onClick = {
                        onResult(WeeklyRouteChoice(chosen, manual.map { oneLine(it.name) }, configs[configIndex]))
                    },
                    enabled = chosen.isNotEmpty() && configIndex in configs.indices
                ) { Text(stringResource(R.string.weekly_route_run)) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}
