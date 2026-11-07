package io.github.fate_grand_automata.ui.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.exit.battleSummaryContent
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val outsideEntries by viewModel.outsideEntries.collectAsState()
    val openEntries by viewModel.openOutsideEntries.collectAsState()
    val retention by viewModel.retention.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CaptureTracker.init(context) }
    }
    val cache = remember { GameNames(File(context.filesDir, "capture/names")) }
    var names by remember { mutableStateOf(GameNameMap()) }
    LaunchedEffect(entries, outsideEntries, openEntries) {
        val gains = entries.mapNotNull { it.gains }
        val itemIds = (outsideEntries + openEntries).flatMap { it.period.sources.flatMap { source -> source.delta.items.keys } }
        if (gains.isNotEmpty() || outsideEntries.isNotEmpty() || openEntries.isNotEmpty()) names = cache.resolve(
            gains, itemIds + iconItemIds, includeCards = outsideEntries.isNotEmpty() || openEntries.isNotEmpty(),
        )
    }
    LogScreenContent(rememberCaptureLogEntries(entries), viewModel::delete, viewModel::clear, names,
        outsideEntries = outsideEntries, openOutsideEntries = openEntries,
        retention = LogRetentionPrefsCore.normalize(retention), onRetention = viewModel::setRetention)
}

@Composable
fun LogScreenContent(
    entries: List<BattleLogEntry>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    names: GameNameMap = GameNameMap(),
    outsideEntries: List<OutsideLogEntry> = emptyList(),
    openOutsideEntries: List<OutsideLogEntry> = emptyList(),
    retention: Int = LogRetentionPrefsCore.DEFAULT,
    onRetention: (Int) -> Unit = {},
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var time by rememberSaveable { mutableStateOf(LogTimeFilter.All) }
    var resource by rememberSaveable { mutableStateOf<String?>(null) }
    var start by rememberSaveable { mutableStateOf<Long?>(null) }
    var end by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var deletion by rememberSaveable(stateSaver = listSaver<LogDeleteSelection, String>(
        save = { listOf(it.active.toString()) + it.selected },
        restore = { LogDeleteSelection(it.first().toBoolean(), it.drop(1).toSet()) },
    )) { mutableStateOf(LogDeleteSelection()) }
    var pendingIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val all = mixedCaptureLogs(entries, outsideEntries, openOutsideEntries)
    val sorted = filterCaptureLogs(all, time, resource, System.currentTimeMillis(), customStart = start, customEnd = end)
    val filtered = time != LogTimeFilter.All || resource != null
    val emptyText = stringResource(if (filtered) R.string.capture_log_filter_empty else R.string.battle_log_empty)
    val cancelDelete = { deletion = deletion.cancel(); pendingIds = null; confirmAll = false }
    val clearFilters = { time = LogTimeFilter.All; resource = null; start = null; end = null }
    val summary = if (filtered) listOfNotNull(
        if (time == LogTimeFilter.Custom) customRangeLabel(start, end) else if (time != LogTimeFilter.All) timeFilterLabel(time) else null,
        resource?.let { resourceLabel(it, names) }).joinToString(" · ") else null
    BackHandler(enabled = deletion.active) { cancelDelete() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val twoPanes = maxWidth >= 840.dp
        val selected = sorted.find { it.id == selectedId } ?: if (twoPanes) sorted.firstOrNull() else null
        val list: @Composable ColumnScope.() -> Unit = {
            LogList(sorted, if (deletion.active) null else selected?.id,
                onSelect = { if (deletion.active) deletion = deletion.toggle(it) else selectedId = it },
                deletion = deletion.copy(selected = deletion.selected.intersect(all.map { it.id }.toSet())),
                onEnterDelete = { deletion = deletion.enter() }, onCancelDelete = cancelDelete,
                onDeleteSelected = { pendingIds = ArrayList(deletion.targets(all)); confirmAll = false },
                onClear = { pendingIds = ArrayList(deletion.targets(all, all = true)); confirmAll = true },
                hasEntries = all.isNotEmpty(), emptyText = if (twoPanes) "" else emptyText,
                onFilter = { showFilters = true }, filterSummary = summary, onClearFilter = clearFilters)
        }
        if (twoPanes) {
            CappedWidth {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.48f).fillMaxHeight(), content = list)
                    Column(Modifier.weight(0.52f).fillMaxHeight()) {
                        if (selected == null) Text(emptyText, Modifier.padding(16.dp))
                        else LazyColumn(Modifier.fillMaxSize()) { captureLogDetailItems(selected, names) }
                    }
                }
            }
        } else {
            BackHandler(enabled = selected != null && !deletion.active) { selectedId = null }
            if (selected != null && !deletion.active) {
                selected.battle?.let { BattleLogDetail(it, onBack = { selectedId = null }, names = names) }
                selected.outside?.let { OutsideLogDetail(it, onBack = { selectedId = null }, names = names) }
            } else CappedWidth { Column(Modifier.fillMaxSize(), content = list) }
        }
    }
    if (showFilters) LogFilterDialog(time, resource, start, end, retention, entries.size, outsideEntries.size,
        logResources(all), names, onDismiss = { showFilters = false }, onApply = { newTime, newResource, newStart, newEnd, newRetention ->
            time = newTime; resource = newResource; start = newStart; end = newEnd
            if (newRetention != retention) onRetention(newRetention)
            showFilters = false
        })
    if (pendingIds != null) AlertDialog(
        onDismissRequest = { pendingIds = null; confirmAll = false },
        title = { Text(stringResource(if (confirmAll) R.string.battle_log_delete_all else R.string.capture_log_delete_selected)) },
        text = { Text(if (confirmAll) stringResource(R.string.capture_log_confirm_clear) else
            stringResource(R.string.capture_log_confirm_selected, pendingIds!!.size)) },
        confirmButton = { TextButton(onClick = {
            if (confirmAll) onClear() else pendingIds.orEmpty().forEach(onDelete)
            cancelDelete()
        }) { Text(stringResource(R.string.battle_log_delete)) } },
        dismissButton = { TextButton(onClick = { pendingIds = null; confirmAll = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun ColumnScope.LogList(
    sorted: List<CaptureLogItem>, selectedId: String?, onSelect: (String) -> Unit,
    deletion: LogDeleteSelection, onEnterDelete: () -> Unit, onCancelDelete: () -> Unit,
    onDeleteSelected: () -> Unit, onClear: () -> Unit, hasEntries: Boolean,
    emptyText: String, onFilter: () -> Unit, filterSummary: String?, onClearFilter: () -> Unit,
) {
    Heading(stringResource(R.string.p_logs), trailing = {
        if (!deletion.active) Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasEntries) TextButton(onClick = onEnterDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.battle_log_delete), Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.battle_log_delete))
            }
            IconButton(onClick = onFilter, modifier = Modifier.testTag("log-filter-button")) {
                BadgedBox(badge = { if (filterSummary != null) Badge(Modifier.testTag("log-filter-active")) }) {
                    Icon(Icons.Default.FilterList, stringResource(R.string.capture_log_filter))
                }
            }
        }
    })
    if (deletion.active) FlowRow(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = onDeleteSelected, enabled = deletion.selected.isNotEmpty()) { Text(stringResource(R.string.capture_log_delete_selected)) }
        TextButton(onClick = onClear) { Text(stringResource(R.string.battle_log_delete_all)) }
        TextButton(onClick = onCancelDelete) { Text(stringResource(android.R.string.cancel)) }
    }
    if (filterSummary != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag("log-filter-summary"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(filterSummary, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onClearFilter) { Text(stringResource(R.string.capture_log_clear_filter)) }
    }
    if (sorted.isEmpty()) {
        if (emptyText.isNotEmpty()) Text(emptyText, Modifier.padding(16.dp))
    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(sorted, key = { it.id }) { entry ->
            val selected = if (deletion.active) entry.id in deletion.selected else entry.id == selectedId
            val check = if (deletion.active) entry.id in deletion.selected else null
            entry.battle?.let { LogCard(it, selected, { onSelect(entry.id) }, check) }
            entry.outside?.let { OutsideLogCard(it, selected, { onSelect(entry.id) }, entry.inProgress, check) }
        }
    }
}

private fun LazyListScope.captureLogDetailItems(entry: CaptureLogItem, names: GameNameMap) {
    entry.battle?.let { battleLogDetailItems(it, names) }
    entry.outside?.let { outsideLogDetailItems(it, names) }
}

/** Shared two-line frame gives both entry kinds the same selected border and checkbox behavior. */
@Composable
internal fun LogCardFrame(id: String, title: String, account: String?, time: String, summary: String,
    selected: Boolean, onClick: () -> Unit, outside: Boolean = false, checked: Boolean? = null,
    collected: Boolean = false,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .testTag("log-card:$id").semantics { this.selected = selected },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (outside) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) Checkbox(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (outside) Icon(Icons.AutoMirrored.Default.CompareArrows, null, Modifier.size(16.dp).testTag("log-outside-icon"))
                    else if (collected) Icon(Icons.Default.VpnKey, stringResource(R.string.capture_log_recorded_with_collection), Modifier.size(16.dp))
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!account.isNullOrEmpty()) Text(account, Modifier.widthIn(max = 64.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(time, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LogCard(entry: BattleLogEntry, selected: Boolean, onClick: () -> Unit, checked: Boolean?) {
    val summary = listOfNotNull(stringResource(R.string.battle_log_card_runs, entry.timesRan, entry.averageTurnsPerRun),
        if (entry.gains != null) stringResource(R.string.capture_gains_tracked) else null, entry.reasonText.takeIf { it.isNotBlank() }).joinToString(" · ")
    LogCardFrame(entry.id, entry.configName, entry.accountName, localDateTime(entry.endedAt), summary, selected, onClick,
        checked = checked, collected = entry.gains != null)
}
private fun LazyListScope.battleLogDetailItems(entry: BattleLogEntry, names: GameNameMap) {
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(entry.configName, style = MaterialTheme.typography.titleMedium)
            entry.accountName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(localDateTime(entry.endedAt), style = MaterialTheme.typography.bodySmall)
        }
    }
    battleSummaryContent(
        reasonText = { entry.reasonText },
        state = entry.exitState(),
        refillEnabled = entry.refillEnabled,
        ceLimitReached = entry.reasonKey == "limit_ces"
    )
    entry.gains?.let { gainsContent(it, names) }
}

@Composable
fun BattleLogDetail(entry: BattleLogEntry, onBack: () -> Unit, names: GameNameMap = GameNameMap()) {
    CappedWidth {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Heading(
                stringResource(R.string.battle_log_detail),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back))
                    }
                }
            )
            LazyColumn(Modifier.weight(1f)) {
                battleLogDetailItems(entry, names)
            }
        }
    }
}

@Composable
private fun localDateTime(endedAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(endedAt))
