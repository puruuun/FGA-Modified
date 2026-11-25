package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.util.grouped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal typealias QuestRecordsSource = RecordsSource<List<QuestRecord>>

private val questTexts = RecordTexts(
    tag = "quest",
    loading = R.string.dashboard_quest_loading,
    namesLoading = R.string.dashboard_quest_names_loading,
    namesWaiting = R.string.dashboard_quest_names_waiting,
    namesMissing = R.string.dashboard_quest_names_missing,
    damaged = R.string.dashboard_quest_damaged,
    search = R.string.dashboard_quest_search,
)

@Composable
internal fun QuestRecordsBlock(source: QuestRecordsSource) {
    val flow = remember(source) { source.records() }
    val state by flow.collectAsStateWithLifecycle(QuestRecordsState())
    DashboardBlock(DashboardBlockId.QuestRecords, stringResource(R.string.dashboard_quest_records)) {
        QuestRecordsContent(state, source.retry)
    }
}

@Composable
internal fun ColumnScope.QuestRecordsContent(state: QuestRecordsState, onRetry: () -> Unit) {
    val records = state.records
    if (records == null) {
        RecordsLoading(questTexts)
        return
    }
    var filter by rememberSaveable { mutableStateOf(QuestFilter.All) }
    var sort by rememberSaveable { mutableStateOf(QuestSort.Clears) }
    var query by rememberSaveable { mutableStateOf("") }
    // A new filter, search or sort starts again from the first rows.
    var shown by rememberSaveable(filter, query, sort) { mutableIntStateOf(RECORDS_FIRST_ROWS) }
    var view by remember { mutableStateOf<QuestView?>(null) }
    LaunchedEffect(records, filter, query, sort) {
        view = withContext(Dispatchers.Default) { questView(records, filter, query, sort) }
    }

    RecordNotices(state, questTexts, onRetry)
    view?.let { current ->
        Text(
            stringResource(R.string.dashboard_quest_summary, current.totalClears.grouped(), current.clearedQuests.toLong().grouped()),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("quest-records-summary"),
        )
    }
    RecordsSearchField(query, questTexts) { query = it }
    ChoiceChips(QuestFilter.entries, filter, { "quest-filter:${it.name}" }, { stringResource(it.label()) }, { filter = it },
        Modifier.testTag("quest-filter-chips"))
    ChoiceChips(QuestSort.entries, sort, { "quest-sort:${it.name}" }, { stringResource(it.label()) }, { sort = it })
    val current = view ?: return
    if (current.rows.isEmpty()) {
        Text(stringResource(R.string.dashboard_quest_none), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("quest-records-empty"))
        return
    }
    PagedRecords(current.rows, { it.id }, shown, { shown = it }, "quest") { QuestRow(it) }
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
                        RecordTag(level, "quest-level:${record.id}", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    if (place.isNotEmpty()) {
                        Text(place, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.dashboard_quest_clears, record.clears.grouped()),
                style = MaterialTheme.typography.bodyMedium.merge(recordNumeric), maxLines = 1, softWrap = false,
                modifier = Modifier.testTag("quest-clears:${record.id}"))
            // The two counts only differ for quests that were started and not cleared; what counts as a challenge is not verified (#730).
            if (record.challenges != record.clears) {
                Text(stringResource(R.string.dashboard_quest_challenges, record.challenges.grouped()),
                    style = MaterialTheme.typography.bodySmall.merge(recordNumeric), color = muted, maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("quest-challenges:${record.id}"))
            }
            record.lastStartedAt?.let {
                Text(formatEpochDate(it), style = MaterialTheme.typography.bodySmall.merge(recordNumeric), color = muted, maxLines = 1, softWrap = false)
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
