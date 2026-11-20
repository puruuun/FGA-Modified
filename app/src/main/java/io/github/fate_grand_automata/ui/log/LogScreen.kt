package io.github.fate_grand_automata.ui.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.GameDay
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val outsideEntries by viewModel.outsideEntries.collectAsState()
    val openEntries by viewModel.openOutsideEntries.collectAsState()
    val retention by viewModel.retention.collectAsState()
    val context = LocalContext.current
    val accounts by CaptureTracker.accounts.collectAsState()
    val cache = remember { GameNames(File(context.filesDir, "capture/names")) }
    var names by remember { mutableStateOf(GameNameMap()) }
    LaunchedEffect(entries, outsideEntries, openEntries, accounts) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CaptureTracker.init(context) }
        val gains = entries.mapNotNull { it.gains }
        val allOutside = outsideEntries + openEntries
        val itemIds = allOutside.flatMap { it.period.sources.flatMap { source -> source.delta.items.keys } }
        val cardIds = allOutside.flatMap {
            it.period.total.svts.keys + it.period.sources.flatMap { source -> source.delta.svts.keys }
        }.toSet()
        val logAccounts = (entries.mapNotNull { it.accountId } + allOutside.map { it.accountId }).toSet()
        if (gains.isNotEmpty() || allOutside.isNotEmpty()) names = cache.resolve(
            gains, itemIds + iconItemIds, includeCards = allOutside.isNotEmpty(),
            hasJpAccount = CaptureTracker.accounts.value.any { it.id in logAccounts && it.server == "JP" },
            cardIds = cardIds,
        )
    }
    LogScreenContent(rememberCaptureLogEntries(entries), viewModel::delete, viewModel::clear, names,
        outsideEntries = outsideEntries, openOutsideEntries = openEntries,
        retention = LogRetentionPrefsCore.normalize(retention), onRetention = viewModel::setRetention,
        loadBattles = viewModel::battlesOf, accountServers = accounts.associate { it.id to it.server })
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
    loadBattles: (suspend (String) -> List<BattleGains>?)? = null,
    accountServers: Map<String, String> = emptyMap(),
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var time by rememberSaveable { mutableStateOf(LogTimeFilter.All) }
    var resources by rememberSaveable(stateSaver = LogResourceSetSaver) { mutableStateOf(LogFilterState.DEFAULT.resources) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<LogSourceFilter?>(null) }
    var start by rememberSaveable { mutableStateOf<Long?>(null) }
    var end by rememberSaveable { mutableStateOf<Long?>(null) }
    // Not persisted across restarts: the screen opens By record, and only a configuration change keeps the choice.
    var dayView by rememberSaveable { mutableStateOf(false) }
    var selectedDayKey by rememberSaveable { mutableStateOf<String?>(null) }
    var dayBattleId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var deletion by rememberSaveable(stateSaver = listSaver<LogDeleteSelection, String>(
        save = { listOf(it.active.toString()) + it.selected },
        restore = { LogDeleteSelection(it.first().toBoolean(), it.drop(1).toSet()) },
    )) { mutableStateOf(LogDeleteSelection()) }
    var pendingIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val all = mixedCaptureLogs(entries, outsideEntries, openOutsideEntries)
    val profiles = logProfiles(all)
    val activeProfile = availableLogProfile(profile, profiles, hasLogWithoutProfile(all))
    val now = System.currentTimeMillis()
    val activeResources = availableLogResources(resources, logResources(
        filterCaptureLogs(all, time, emptySet(), now, customStart = start, customEnd = end, profile = activeProfile, source = source)))
    val sorted = filterCaptureLogs(all, time, activeResources, now, customStart = start, customEnd = end, profile = activeProfile, source = source)
    val filtered = time != LogTimeFilter.All || activeResources.isNotEmpty() || activeProfile != null || source != null
    val emptyText = stringResource(if (filtered) R.string.capture_log_filter_empty else R.string.battle_log_empty)
    val days = if (dayView) groupLogDays(sorted, logProfiles(all), accountServers) else emptyList()
    val cancelDelete = { deletion = deletion.cancel(); pendingIds = null; confirmAll = false }
    val noProfileLabel = stringResource(R.string.capture_log_no_profile)
    val (resourceNames, hiddenResources) = resourceSummaryParts(activeResources.map { resourceLabel(it, names) })
    val resourceSummaryText = if (hiddenResources > 0) stringResource(R.string.capture_log_resources_more, resourceNames, hiddenResources) else resourceNames
    val summary = if (filtered || dayView) listOfNotNull(
        if (dayView) stringResource(R.string.capture_log_view_day) else null,
        if (time == LogTimeFilter.Custom) customRangeLabel(start, end) else if (time != LogTimeFilter.All) timeFilterLabel(time) else null,
        activeProfile?.let { if (it == NO_LOG_PROFILE) noProfileLabel else profiles[it] }, resourceSummaryText.takeIf { it.isNotEmpty() }, source?.let { sourceFilterLabel(it) }).joinToString(" · ") else null
    BackHandler(enabled = deletion.active) { cancelDelete() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val twoPanes = maxWidth >= 840.dp
        val selected = sorted.find { it.id == selectedId } ?: if (twoPanes) sorted.firstOrNull() else null
        val selectedDay = days.find { it.key == selectedDayKey } ?: if (twoPanes) days.firstOrNull() else null
        val dayBattle = selectedDay?.battles?.find { it.id == dayBattleId }?.battle
        val list: @Composable ColumnScope.() -> Unit = {
            LogList(sorted, if (deletion.active) null else selected?.id,
                onSelect = { if (deletion.active) deletion = deletion.toggle(it) else selectedId = it },
                deletion = deletion.copy(selected = deletion.selected.intersect(all.map { it.id }.toSet())),
                onEnterDelete = { deletion = deletion.enter() }, onCancelDelete = cancelDelete,
                onDeleteSelected = { pendingIds = ArrayList(deletion.targets(all)); confirmAll = false },
                onClear = { pendingIds = ArrayList(deletion.targets(all, all = true)); confirmAll = true },
                hasEntries = all.isNotEmpty(), emptyText = if (twoPanes) "" else emptyText,
                onFilter = { showFilters = true }, filterSummary = summary,
                dayView = dayView, filterActive = filtered,
                days = days, selectedDay = selectedDay?.day, now = now,
                onSelectDay = { selectedDayKey = it.key(); dayBattleId = null })
        }
        if (twoPanes) {
            CappedWidth {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.48f).fillMaxHeight(), content = list)
                    Column(Modifier.weight(0.52f).fillMaxHeight()) {
                        if (dayView) {
                            if (dayBattle != null) BattleLogDetail(dayBattle, onBack = { dayBattleId = null }, names = names, loadBattles = loadBattles)
                            else if (selectedDay != null) LogDayDetail(selectedDay, names, now, onBack = null, onBattle = { dayBattleId = it })
                            else Text(emptyText, Modifier.padding(16.dp))
                        } else if (selected == null) Text(emptyText, Modifier.padding(16.dp))
                        else LazyColumn(Modifier.fillMaxSize()) { captureLogDetailItems(selected, names, loadBattles, source) }
                    }
                }
            }
        } else {
            BackHandler(enabled = !dayView && selected != null && !deletion.active) { selectedId = null }
            BackHandler(enabled = dayView && (dayBattle != null || selectedDay != null)) {
                if (dayBattle != null) dayBattleId = null else selectedDayKey = null
            }
            if (dayView) {
                if (dayBattle != null) BattleLogDetail(dayBattle, onBack = { dayBattleId = null }, names = names, loadBattles = loadBattles)
                else if (selectedDay != null) CappedWidth {
                    LogDayDetail(selectedDay, names, now, onBack = { selectedDayKey = null }, onBattle = { dayBattleId = it })
                } else CappedWidth { Column(Modifier.fillMaxSize(), content = list) }
            } else if (selected != null && !deletion.active) {
                selected.battle?.let { BattleLogDetail(it, onBack = { selectedId = null }, names = names, loadBattles = loadBattles) }
                selected.outside?.let { OutsideLogDetail(it, onBack = { selectedId = null }, names = names, source = source) }
            } else CappedWidth { Column(Modifier.fillMaxSize(), content = list) }
        }
    }
    if (showFilters) LogFilterDialog(time, activeResources, start, end, retention, entries.size, outsideEntries.size,
        all, names, profile, onDismiss = { showFilters = false }, onApply = { newTime, newResources, newStart, newEnd, newRetention, newProfile, newSource ->
            time = newTime; resources = newResources; start = newStart; end = newEnd
            profile = newProfile; source = newSource
            if (newRetention != retention) onRetention(newRetention)
            showFilters = false
        }, source = source, dayView = dayView,
        onViewApply = { if (it) cancelDelete(); dayView = it })
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
    emptyText: String, onFilter: () -> Unit, filterSummary: String?,
    dayView: Boolean = false, filterActive: Boolean = filterSummary != null, days: List<LogDay> = emptyList(),
    selectedDay: GameDay? = null, now: Long = 0L, onSelectDay: (GameDay) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val newestId = sorted.firstOrNull()?.id
    // Read the old viewport before LazyColumn can retain its key after a prepend.
    val followNewest = remember(newestId) { listState.firstVisibleItemIndex == 0 }
    LaunchedEffect(newestId) {
        if (newestId != null && followNewest) listState.scrollToItem(0)
    }
    Heading(stringResource(R.string.p_logs), trailing = {
        if (!deletion.active) Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasEntries && !dayView) TextButton(onClick = onEnterDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.battle_log_delete), Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.battle_log_delete))
            }
            IconButton(onClick = onFilter, modifier = Modifier.testTag("log-filter-button")) {
                BadgedBox(badge = { if (filterActive) Badge(Modifier.testTag("log-filter-active")) }) {
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
        Text(filterSummary, Modifier.weight(1f).clickable(onClick = onFilter).padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
    if (sorted.isEmpty()) {
        if (emptyText.isNotEmpty()) Text(emptyText, Modifier.padding(16.dp))
    } else if (dayView) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(days, key = { it.key }) { LogDayCard(it, it.day == selectedDay, now) { onSelectDay(it.day) } }
    } else LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(sorted, key = { it.id }) { entry ->
            val selected = if (deletion.active) entry.id in deletion.selected else entry.id == selectedId
            val check = if (deletion.active) entry.id in deletion.selected else null
            entry.battle?.let { LogCard(it, selected, { onSelect(entry.id) }, check) }
            entry.outside?.let { OutsideLogCard(it, selected, { onSelect(entry.id) }, entry.inProgress, check) }
        }
    }
}

private fun LazyListScope.captureLogDetailItems(entry: CaptureLogItem, names: GameNameMap, loadBattles: (suspend (String) -> List<BattleGains>?)?, source: LogSourceFilter?) {
    entry.battle?.let { battleLogDetailItems(it, names, loadBattles) }
    entry.outside?.let { outsideLogDetailItems(it, names, source) }
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
                    else if (collected) Icon(painterResource(R.drawable.ic_swords), stringResource(R.string.capture_log_recorded_with_collection), Modifier.size(16.dp))
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
internal fun LogCard(entry: BattleLogEntry, selected: Boolean, onClick: () -> Unit, checked: Boolean?) {
    val summary = listOfNotNull(stringResource(R.string.battle_log_card_runs, entry.timesRan, entry.averageTurnsPerRun),
        if (entry.gains != null) stringResource(R.string.capture_gains_tracked) else null, entry.reasonText.takeIf { it.isNotBlank() }).joinToString(" · ")
    LogCardFrame(entry.id, entry.configName, entry.accountName, localDateTime(entry.endedAt), summary, selected, onClick,
        checked = checked, collected = entry.gains != null)
}
private fun LazyListScope.battleLogDetailItems(entry: BattleLogEntry, names: GameNameMap, loadBattles: (suspend (String) -> List<BattleGains>?)? = null) {
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
    entry.gains?.let { gainsContent(it, names, loadBattles?.let { load -> { load(entry.id) } }, entry.id) }
}

@Composable
fun BattleLogDetail(entry: BattleLogEntry, onBack: () -> Unit, names: GameNameMap = GameNameMap(), loadBattles: (suspend (String) -> List<BattleGains>?)? = null) {
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
                battleLogDetailItems(entry, names, loadBattles)
            }
        }
    }
}

@Composable
private fun localDateTime(endedAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(endedAt))
