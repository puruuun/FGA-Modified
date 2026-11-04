package io.github.fate_grand_automata.ui.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.ui.ChaldeaExportAction
import io.github.fate_grand_automata.ui.rememberChaldeaExport
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.capture.GameNameMap
import java.io.File
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.exit.battleSummaryContent
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    val context = LocalContext.current
    val export = rememberChaldeaExport()
    val accountState by CaptureTracker.state.collectAsState()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CaptureTracker.init(context) }
    }
    val cache = remember { GameNames(File(context.filesDir, "capture/names")) }
    var names by remember { mutableStateOf(GameNameMap()) }
    LaunchedEffect(entries) {
        val gains = entries.mapNotNull { it.gains }
        if (gains.isNotEmpty()) names = cache.resolve(gains)
    }
    LogScreenContent(entries, viewModel::delete, viewModel::clear, names, export, accountState?.lastUpdateTime)
}

@Composable
fun LogScreenContent(
    entries: List<BattleLogEntry>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    names: GameNameMap = GameNameMap(),
    onExport: () -> Unit = {},
    lastUpdateTime: Long? = null,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val sorted = entries.sortedByDescending { it.endedAt }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
        val twoPanes = sizeClass.widthSizeClass != WindowWidthSizeClass.Compact

        if (twoPanes) {
            // Wide screens always show a detail: the selection, or the newest entry as the fallback.
            val selected = sorted.find { it.id == selectedId } ?: sorted.firstOrNull()
            CappedWidth {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.4f).fillMaxHeight()) {
                        LogList(sorted, selected?.id, { selectedId = it }, { deleteId = it }, { confirmClear = true }, showEmptyText = false, onExport = onExport, lastUpdateTime = lastUpdateTime)
                    }
                    Column(Modifier.weight(0.6f).fillMaxHeight()) {
                        if (selected == null) {
                            Text(stringResource(R.string.battle_log_empty), Modifier.padding(16.dp))
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                battleLogDetailItems(selected, names)
                            }
                        }
                    }
                }
            }
        } else {
            val selected = sorted.find { it.id == selectedId }
            BackHandler(enabled = selected != null) { selectedId = null }
            if (selected != null) {
                BattleLogDetail(selected, onBack = { selectedId = null }, names = names)
            } else {
                CappedWidth {
                    Column(Modifier.fillMaxSize()) {
                        LogList(sorted, null, { selectedId = it }, { deleteId = it }, { confirmClear = true }, onExport = onExport, lastUpdateTime = lastUpdateTime)
                    }
                }
            }
        }
    }

    if (deleteId != null || confirmClear) {
        AlertDialog(
            onDismissRequest = { deleteId = null; confirmClear = false },
            title = { Text(stringResource(if (confirmClear) R.string.battle_log_delete_all else R.string.battle_log_delete)) },
            text = {
                Text(stringResource(if (confirmClear) R.string.battle_log_confirm_clear else R.string.battle_log_confirm_delete))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmClear) onClear() else deleteId?.let(onDelete)
                    deleteId = null
                    confirmClear = false
                }) { Text(stringResource(R.string.battle_log_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null; confirmClear = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ColumnScope.LogList(
    sorted: List<BattleLogEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    showEmptyText: Boolean = true,
    onExport: () -> Unit,
    lastUpdateTime: Long?,
) {
    Heading(
        stringResource(R.string.p_logs),
        trailing = {
            if (sorted.isNotEmpty()) TextButton(onClick = onClear) {
                Text(stringResource(R.string.battle_log_delete_all))
            }
        }
    )
    Column(Modifier.padding(horizontal = 16.dp)) { ChaldeaExportAction(lastUpdateTime, onExport) }
    if (sorted.isEmpty()) {
        if (showEmptyText) {
            Text(stringResource(R.string.battle_log_empty), Modifier.padding(16.dp))
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sorted, key = { it.id }) { entry ->
                LogCard(entry, entry.id == selectedId, { onSelect(entry.id) }, { onDelete(entry.id) })
            }
            item { Spacer(Modifier.padding(bottom = 4.dp)) }
        }
    }
}

@Composable
private fun LogCard(entry: BattleLogEntry, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val runsText = stringResource(R.string.battle_log_card_runs, entry.timesRan, entry.averageTurnsPerRun)
    // The exit reason of a finished run only repeats the run count; show it when it adds something.
    val reasonDuplicatesRuns = entry.reasonText == stringResource(R.string.times_ran, entry.timesRan)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.configName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(localDateTime(entry.endedAt), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(runsText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (!reasonDuplicatesRuns) {
                        Text(
                            entry.reasonText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (entry.gains != null) Text(
                    stringResource(R.string.capture_gains_tracked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.battle_log_delete))
            }
        }
    }
}

private fun LazyListScope.battleLogDetailItems(entry: BattleLogEntry, names: GameNameMap) {
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(entry.configName, style = MaterialTheme.typography.titleMedium)
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

private fun localDateTime(endedAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(endedAt))
