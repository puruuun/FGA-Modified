package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.util.grouped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Where the quest records block gets its data; [records] is collected only while the block is composed. */
internal class QuestRecordsSource(val records: () -> Flow<QuestRecordsState>, val retry: () -> Unit)

/** Rows shown before "show all"; each later tap adds a page. The list never composes every row at once (#730). */
internal const val QUEST_FIRST_ROWS = 10
internal const val QUEST_PAGE = 50

@Composable
internal fun QuestRecordsBlock(source: QuestRecordsSource) {
    val flow = remember(source) { source.records() }
    val state by flow.collectAsStateWithLifecycle(QuestRecordsState())
    DashboardBlock(DashboardBlockId.QuestRecords, stringResource(R.string.dashboard_quest_records)) {
        QuestRecordsContent(state, source.retry)
    }
}

private val numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun ColumnScope.QuestRecordsContent(state: QuestRecordsState, onRetry: () -> Unit) {
    val records = state.records
    if (records == null) {
        Row(Modifier.testTag("quest-records-loading"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.dashboard_quest_loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    var filter by rememberSaveable { mutableStateOf(QuestFilter.All) }
    var sort by rememberSaveable { mutableStateOf(QuestSort.Clears) }
    var query by rememberSaveable { mutableStateOf("") }
    // A new filter, search or sort starts again from the first rows.
    var shown by rememberSaveable(filter, query, sort) { mutableIntStateOf(QUEST_FIRST_ROWS) }
    var view by remember { mutableStateOf<QuestView?>(null) }
    LaunchedEffect(records, filter, query, sort) {
        view = withContext(Dispatchers.Default) { questView(records, filter, query, sort) }
    }

    QuestNotices(state, onRetry)
    view?.let { current ->
        Text(
            stringResource(R.string.dashboard_quest_summary, current.totalClears.grouped(), current.clearedQuests.toLong().grouped()),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("quest-records-summary"),
        )
    }
    QuestSearchField(query) { query = it }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("quest-filter-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuestFilter.entries.forEach { option ->
            FilterChip(selected = filter == option, onClick = { filter = option }, modifier = Modifier.testTag("quest-filter:${option.name}"),
                label = { Text(stringResource(option.label())) })
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuestSort.entries.forEach { option ->
            FilterChip(selected = sort == option, onClick = { sort = option }, modifier = Modifier.testTag("quest-sort:${option.name}"),
                label = { Text(stringResource(option.label())) })
        }
    }
    val current = view ?: return
    if (current.rows.isEmpty()) {
        Text(stringResource(R.string.dashboard_quest_none), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("quest-records-empty"))
        return
    }
    val visible = current.rows.take(shown)
    if (shown <= QUEST_FIRST_ROWS) {
        Column(Modifier.fillMaxWidth().testTag("quest-records-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { QuestRow(it) }
        }
    } else {
        // Inside the section's scroll a lazy list needs a fixed cap, as the other blocks' long lists have.
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 480.dp).testTag("quest-records-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { QuestRow(it) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shown <= QUEST_FIRST_ROWS && current.rows.size > QUEST_FIRST_ROWS) {
            TextButton(onClick = { shown = QUEST_PAGE }, modifier = Modifier.testTag("quest-records-show-all")) {
                Text(stringResource(R.string.dashboard_show_all, current.rows.size))
            }
        }
        if (shown > QUEST_FIRST_ROWS && current.rows.size > shown) {
            val more = minOf(QUEST_PAGE, current.rows.size - shown)
            TextButton(onClick = { shown += QUEST_PAGE }, modifier = Modifier.testTag("quest-records-more")) {
                Text(stringResource(R.string.dashboard_quest_more, more, current.rows.size - shown))
            }
        }
        if (shown > QUEST_FIRST_ROWS) {
            TextButton(onClick = { shown = QUEST_FIRST_ROWS }, modifier = Modifier.testTag("quest-records-collapse")) {
                Text(stringResource(R.string.friends2_collapse))
            }
        }
    }
}

@Composable
private fun QuestNotices(state: QuestRecordsState, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun notice(text: Int, tag: String) =
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag(tag))
    when (state.names) {
        QuestNameStatus.Ready -> Unit
        QuestNameStatus.Loading -> notice(R.string.dashboard_quest_names_loading, "quest-names-loading")
        QuestNameStatus.Waiting -> notice(R.string.dashboard_quest_names_waiting, "quest-names-waiting")
        QuestNameStatus.Missing -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dashboard_quest_names_missing), style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.weight(1f).testTag("quest-names-missing"))
            TextButton(onClick = onRetry, modifier = Modifier.testTag("quest-names-retry")) {
                Text(stringResource(R.string.dashboard_quest_names_retry))
            }
        }
    }
    if (state.paused) notice(R.string.dashboard_quest_paused, "quest-records-paused")
    if (state.damaged) notice(R.string.dashboard_quest_damaged, "quest-records-damaged")
}

@Composable
private fun QuestSearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(R.string.dashboard_quest_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }, modifier = Modifier.testTag("quest-search-clear")) {
                    Icon(Icons.Default.Clear, stringResource(R.string.dashboard_items_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag("quest-search"),
    )
}

@Composable
private fun QuestRow(record: QuestRecord) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().testTag("quest:${record.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(record.name ?: "#${record.id}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val place = listOfNotNull(record.war, record.spot).joinToString(" · ")
            if (place.isNotEmpty() || record.level != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    record.level?.let { level ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.testTag("quest-level:${record.id}")) {
                            Text(level, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, softWrap = false)
                        }
                    }
                    if (place.isNotEmpty()) {
                        Text(place, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.dashboard_quest_clears, record.clears.grouped()),
                style = MaterialTheme.typography.bodyMedium.merge(numeric), maxLines = 1, softWrap = false,
                modifier = Modifier.testTag("quest-clears:${record.id}"))
            // The two counts only differ for quests that were started and not cleared; what counts as a challenge is not verified (#730).
            if (record.challenges != record.clears) {
                Text(stringResource(R.string.dashboard_quest_challenges, record.challenges.grouped()),
                    style = MaterialTheme.typography.bodySmall.merge(numeric), color = muted, maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("quest-challenges:${record.id}"))
            }
            record.lastStartedAt?.let {
                Text(formatEpochDate(it), style = MaterialTheme.typography.bodySmall.merge(numeric), color = muted, maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun QuestFilter.label() = when (this) {
    QuestFilter.All -> R.string.dashboard_filter_all
    QuestFilter.Free -> R.string.dashboard_quest_filter_free
    QuestFilter.Event -> R.string.dashboard_quest_filter_event
    QuestFilter.HighLevel -> R.string.dashboard_quest_filter_high_level
    QuestFilter.InterludeRankUp -> R.string.dashboard_quest_filter_interlude
    QuestFilter.Daily -> R.string.dashboard_quest_filter_daily
    QuestFilter.Raid -> R.string.dashboard_quest_filter_raid
    QuestFilter.Story -> R.string.dashboard_quest_filter_story
}

private fun QuestSort.label() = when (this) {
    QuestSort.Clears -> R.string.dashboard_quest_sort_clears
    QuestSort.LastRun -> R.string.dashboard_quest_sort_last_run
    QuestSort.Name -> R.string.dashboard_quest_sort_name
}
