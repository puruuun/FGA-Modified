package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.util.grouped

/** Collection and summons section: command code collection, class score and summon history. */
@Composable
internal fun DashboardCollectionSection(data: DashboardData, names: GameNameMap) {
    data.commandCodes?.let { codes ->
        DashboardBlock(DashboardBlockId.CommandCodes, stringResource(R.string.dashboard_command_code_collection)) {
            CommandCodeCollection(codes, names)
        }
    }
    if (!data.classBoards.isNullOrEmpty()) {
        DashboardBlock(DashboardBlockId.ClassScore, stringResource(R.string.dashboard_class_score)) { ClassScore(data.classBoards) }
    }
    data.summons?.let { summons ->
        DashboardBlock(DashboardBlockId.Summons, stringResource(R.string.dashboard_summon_history)) { SummonHistory(summons) }
    }
}

@Composable private fun CommandCodeCollection(codes: DashboardCommandCodes, names: GameNameMap) {
    val ownedCount = codes.ownedIds.size
    val header = if (codes.total != null) {
        stringResource(R.string.dashboard_command_codes_progress, ownedCount, codes.total)
    } else {
        stringResource(R.string.dashboard_command_codes_owned_only, ownedCount)
    }
    Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("dashboard-cc-header"))
    if (codes.ownedIds.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-cc-toggle")) {
            Text("${if (expanded) "−" else "+"} ${stringResource(R.string.dashboard_command_codes_owned_group)} ($ownedCount)")
        }
        if (expanded) {
            LazyVerticalGrid(
                GridCells.Adaptive(144.dp),
                Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp).testTag("dashboard-cc-grid"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(codes.ownedIds, key = { it }) { id ->
                    val atlas = names.commandCodeData[id]
                    CompactResourceTile(
                        kind = "commandCodes",
                        id = id,
                        name = atlas?.name?.takeIf { it.isNotBlank() } ?: "#$id",
                        iconUrl = atlas?.icon,
                        countText = null,
                        tag = "command-code:$id",
                    )
                }
            }
        }
    }
}

@Composable private fun ClassScore(boards: List<DashboardClassBoard>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) boards else boards.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (board in shown) {
            Row(
                Modifier.fillMaxWidth().testTag("class-board:${board.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AtlasIcon("classBoards", board.id, board.icon, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        board.name?.takeIf { it.isNotBlank() } ?: "#${board.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val counts = if (board.squareTotal != null && board.lockTotal != null) {
                        stringResource(
                            R.string.dashboard_class_score_squares_locks,
                            board.acquired,
                            board.squareTotal,
                            board.unlockedLocks,
                            board.lockTotal,
                        )
                    } else {
                        stringResource(
                            R.string.dashboard_class_score_counts_only,
                            board.acquired,
                            board.unlockedLocks,
                        )
                    }
                    Text(
                        counts,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (board.isComplete) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.testTag("class-board-complete:${board.id}"),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                stringResource(R.string.dashboard_class_score_complete),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
    if (expanded || boards.size > 3) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("dashboard-class-score-toggle"),
        ) {
            Text(
                if (expanded) stringResource(R.string.friends2_collapse)
                else stringResource(R.string.dashboard_show_all, boards.size),
            )
        }
    }
}

@Composable private fun SummonHistory(summons: DashboardSummons) {
    if (summons.friendPointTotal != null) {
        Text(
            stringResource(R.string.dashboard_friend_point_summons, summons.friendPointTotal.grouped()),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("dashboard-summons-fp"),
        )
    }
    if (summons.otherSummons.isNotEmpty()) {
        var expandedOthers by rememberSaveable { mutableStateOf(false) }
        val shown = if (expandedOthers) summons.otherSummons else summons.otherSummons.take(10)
        Text(stringResource(R.string.dashboard_other_summons), style = MaterialTheme.typography.labelLarge)
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = if (expandedOthers) 380.dp else 360.dp)
                .testTag("dashboard-summons-others"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().testTag("summon:${entry.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.name?.takeIf { it.isNotBlank() } ?: "#${entry.id}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        formatSummonPeriod(entry.openedAt, entry.closedAt)?.let { period ->
                            Text(
                                period,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.dashboard_summon_count, entry.count.grouped()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (expandedOthers || summons.otherSummons.size > 10) {
            TextButton(onClick = { expandedOthers = !expandedOthers }, modifier = Modifier.testTag("dashboard-summons-others-toggle")) {
                Text(if (expandedOthers) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, summons.otherSummons.size))
            }
        }
    }
    if (summons.recentLog.isNotEmpty()) {
        var logExpanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { logExpanded = !logExpanded }, modifier = Modifier.testTag("dashboard-summons-log-toggle")) {
            Text("${if (logExpanded) "−" else "+"} ${stringResource(R.string.dashboard_recent_summon_log)} (${summons.recentLog.size})")
        }
        if (logExpanded) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp).testTag("dashboard-summons-log"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(summons.recentLog, key = { "${it.date}:${it.gachaId}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().testTag("summon-log:${entry.date}:${entry.gachaId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            entry.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            entry.name?.takeIf { it.isNotBlank() } ?: "#${entry.gachaId}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.dashboard_summon_log_count, entry.count),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSummonPeriod(openedAt: Long?, closedAt: Long?): String? {
    val start = openedAt?.let { formatEpochDate(it) }
    val end = closedAt?.let { formatEpochDate(it) }
    return when {
        start != null && end != null -> "$start ~ $end"
        start != null -> "$start ~"
        end != null -> "~ $end"
        else -> null
    }
}
