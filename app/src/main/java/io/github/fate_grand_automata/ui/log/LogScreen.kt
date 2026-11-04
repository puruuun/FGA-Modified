package io.github.fate_grand_automata.ui.log

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.exit.battleSummaryContent
import java.text.DateFormat
import java.util.Date

@Composable
fun LogScreen(viewModel: LogViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsState()
    LogScreenContent(entries, viewModel::delete, viewModel::clear)
}

@Composable
fun LogScreenContent(
    entries: List<BattleLogEntry>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val selected = entries.find { it.id == selectedId }

    BackHandler(enabled = selected != null) { selectedId = null }
    if (selected != null) {
        BattleLogDetail(selected, onBack = { selectedId = null })
    } else {
        CappedWidth {
            Column(Modifier.fillMaxSize()) {
                Heading(
                    stringResource(R.string.p_logs),
                    trailing = {
                        if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }) {
                            Text(stringResource(R.string.battle_log_delete_all))
                        }
                    }
                )
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.battle_log_empty), Modifier.padding(16.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(entries.sortedByDescending { it.endedAt }, key = { it.id }) { entry ->
                            Card(
                                onClick = { selectedId = entry.id },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(entry.configName, style = MaterialTheme.typography.titleMedium)
                                        Text(localDateTime(entry.endedAt), style = MaterialTheme.typography.bodySmall)
                                        Text(stringResource(R.string.times_ran, entry.timesRan))
                                        Text(stringResource(R.string.battle_log_average_turns, entry.averageTurnsPerRun))
                                        Text(entry.reasonText, color = MaterialTheme.colorScheme.secondary)
                                    }
                                    IconButton(onClick = { deleteId = entry.id }) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.battle_log_delete))
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.padding(bottom = 4.dp)) }
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
fun BattleLogDetail(entry: BattleLogEntry, onBack: () -> Unit) {
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Heading(
                stringResource(R.string.battle_log_detail),
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back))
                    }
                }
            )
            LazyColumn(Modifier.weight(1f)) {
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
            }
        }
    }
}

private fun localDateTime(endedAt: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(endedAt))
