package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.WEEKLY_ROUTE_TAG
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The right panel owns only a route; the shared launcher owns battle config and OK/Cancel. */
class WeeklyPanelState {
    var data by mutableStateOf<AtlasRouteRepository.Dataset?>(null)
    var plan by mutableStateOf<RoutePlan?>(null)
    val counts = mutableStateMapOf<Int, Int>()
    var busy by mutableStateOf(false)
    var stage by mutableStateOf("missions")
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    val chosen: List<RouteItem> get() = plan?.items.orEmpty().mapNotNull {
        val count = counts[it.quest.id] ?: 0
        if (count > 0) it.copy(count = count) else null
    }
    /** D-25 navigation probe: no AP is spent; the run only checks that each quest's panel can be found. */
    var probe by mutableStateOf(false)
    var probeScope by mutableStateOf(ProbeScope.All)
    val probeChapters = mutableStateListOf<Int>()
    var skipSucceeded by mutableStateOf(false)
    fun probeItems(data: AtlasRouteRepository.Dataset, disabled: Set<String>): List<RouteItem> =
        WeeklyProbe.select(data.server, data.quests, probeScope, probeChapters.toSet(), disabled)
    val manual: List<String> get() = plan?.manual.orEmpty().map { it.name.replace('\n', ' ') }
}

@Composable
fun WeeklyLauncherPanel(server: String, prefs: PrefsCore, state: WeeklyPanelState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember { File(context.cacheDir, "atlas-weekly") }
    val disabled by prefs.weeklyRoute.disabled.remember()

    LaunchedEffect(server) {
        state.data = withContext(Dispatchers.IO) { AtlasRouteRepository(cache).cached(server) }
    }
    LaunchedEffect(state.data, disabled) {
        state.plan = null
        state.counts.clear()
        state.data?.let { data ->
            state.busy = true
            state.stage = "solve"
            try {
                val plan = withContext(Dispatchers.Default) {
                    WeeklyRouteSolver.solve(data.week.missions,
                        WeeklyCandidateSettings.candidates(server, data.quests, disabled), setOf(WeeklyRouteSolver.MICTLAN_WAR))
                }
                state.counts.putAll(plan.items.associate { it.quest.id to it.count })
                state.plan = plan
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.error = e.message.orEmpty() }
            finally { state.busy = false }
        }
    }
    Column(modifier.padding(12.dp)) {
        Text(stringResource(R.string.weekly_route_title), style = MaterialTheme.typography.titleLarge)
        Text(state.data?.let { stringResource(R.string.weekly_route_header, it.server, it.week.id) } ?: server,
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !state.busy, onClick = {
            state.busy = true
            state.error = null
            scope.launch {
                try {
                    state.data = withContext(Dispatchers.IO) {
                        AtlasRouteRepository(cache, log = { Timber.tag(WEEKLY_ROUTE_TAG).i(it) }, progress = { stage, done, total ->
                            scope.launch { state.stage = stage; state.progress = if (total > 0) done.toFloat() / total else 0f }
                        }).load(server)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.error = e.message.orEmpty() }
                finally { state.busy = false }
            }
        }) { Text(stringResource(R.string.weekly_route_download)) }
        if (state.busy) {
            Text(stringResource(when (state.stage) {
                "chapters" -> R.string.weekly_route_progress_chapters
                "quests" -> R.string.weekly_route_progress_quests
                "solve" -> R.string.weekly_route_progress_solve
                else -> R.string.weekly_route_progress_missions
            }), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { Text(stringResource(R.string.weekly_route_load_failed, it), color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        val data = state.data
        val plan = state.plan
        if (data != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.probe, onCheckedChange = { state.probe = it })
            Text(stringResource(R.string.weekly_probe_toggle), style = MaterialTheme.typography.bodyMedium)
        }
        if (data != null && state.probe) ProbeOptions(state, data, disabled, Modifier.weight(1f))
        else if (data != null && plan != null) {
            val manualIds = plan.manual.map { it.id }.toSet()
            LazyColumn(Modifier.weight(1f)) {
                item { PanelHeading(stringResource(R.string.weekly_route_missions)) }
                items(data.week.missions.filter { !it.meta && it.id !in manualIds }) {
                    Text("· ${it.name.replace('\n', ' ')}", style = MaterialTheme.typography.bodySmall)
                }
                item { PanelHeading(stringResource(R.string.weekly_route_manual)) }
                items(plan.manual) { Text("· ${it.name.replace('\n', ' ')}", style = MaterialTheme.typography.bodySmall) }
                item { PanelHeading(stringResource(R.string.weekly_route_route)) }
                if (plan.items.isEmpty()) item { Text(stringResource(R.string.weekly_route_route_empty)) }
                items(plan.items, key = { it.quest.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.weekly_route_quest_line, item.quest.chapter.replace('\n', ' '),
                            item.quest.spot, item.quest.name, item.quest.ap), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        Stepper(value = state.counts[item.quest.id] ?: 0,
                            onValueChange = { state.counts[item.quest.id] = it }, valueRange = 0..999)
                    }
                }
                item {
                    Text(stringResource(R.string.weekly_route_total_ap,
                        state.chosen.sumOf { it.quest.ap.toLong() * it.count }), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.weekly_route_start_note), style = MaterialTheme.typography.bodySmall)
                    if (server == "KR") Text(stringResource(R.string.weekly_route_kr_note), style = MaterialTheme.typography.bodySmall)
                    val dropped = data.missing.size + data.mismatched.size
                    if (dropped > 0) Text(stringResource(R.string.weekly_route_dropped, dropped), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.weekly_route_mictlan), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PanelHeading(title: String) {
    Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
}

@Composable
private fun ProbeOptions(state: WeeklyPanelState, data: AtlasRouteRepository.Dataset, disabled: Set<String>, modifier: Modifier) {
    val chapters = remember(data) {
        data.quests.filter { it.warId != WeeklyRouteSolver.MICTLAN_WAR }.sortedWith(WeeklyRouteSolver.order).groupBy { it.warId }
    }
    LazyColumn(modifier) {
        item {
            Text(stringResource(R.string.weekly_probe_note), style = MaterialTheme.typography.bodySmall)
            PanelHeading(stringResource(R.string.weekly_probe_scope))
            for ((scope, label) in listOf(
                ProbeScope.All to R.string.weekly_probe_scope_all,
                ProbeScope.Chapters to R.string.weekly_probe_scope_chapters,
                ProbeScope.Enabled to R.string.weekly_probe_scope_enabled
            )) Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.probeScope == scope, onClick = { state.probeScope = scope })
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.skipSucceeded, onCheckedChange = { state.skipSucceeded = it })
                Text(stringResource(R.string.weekly_probe_skip), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.weekly_probe_count_selected, state.probeItems(data, disabled).size),
                style = MaterialTheme.typography.titleMedium)
        }
        if (state.probeScope == ProbeScope.Chapters) items(chapters.entries.toList(), key = { it.key }) { (war, quests) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = war in state.probeChapters, onCheckedChange = {
                    if (it) state.probeChapters.add(war) else state.probeChapters.remove(war)
                })
                Text(quests.first().chapter.replace('\n', ' '), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
