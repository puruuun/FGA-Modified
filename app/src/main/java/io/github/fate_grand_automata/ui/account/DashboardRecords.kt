package io.github.fate_grand_automata.ui.account

import androidx.annotation.StringRes
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.RecordNameStatus
import io.github.fate_grand_automata.capture.RecordsState
import kotlinx.coroutines.flow.Flow

/** What the quest and shop records blocks have in common: their data source, notices, search field and paging (#730). */

/** Where a records block gets its data; [records] is collected only while the block is composed. */
internal class RecordsSource<T>(val records: () -> Flow<RecordsState<T>>, val retry: () -> Unit)

/** Rows shown before "show all"; each later tap adds a page. A list never composes every row at once (#730). */
internal const val RECORDS_FIRST_ROWS = 10
internal const val RECORDS_PAGE = 50

internal val recordNumeric = TextStyle(fontFeatureSettings = "tnum")

/** The texts that differ between the blocks; [tag] prefixes every test tag (`quest`, `shop`). */
internal class RecordTexts(
    val tag: String,
    @StringRes val loading: Int,
    @StringRes val namesLoading: Int,
    @StringRes val namesWaiting: Int,
    @StringRes val namesMissing: Int,
    @StringRes val damaged: Int,
    @StringRes val search: Int,
)

@Composable
internal fun RecordsLoading(texts: RecordTexts) {
    Row(Modifier.testTag("${texts.tag}-records-loading"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(texts.loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun RecordNotices(state: RecordsState<*>, texts: RecordTexts, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun notice(text: Int, tag: String) =
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag(tag))
    val t = texts.tag
    when (state.names) {
        RecordNameStatus.Ready -> Unit
        RecordNameStatus.Loading -> notice(texts.namesLoading, "$t-names-loading")
        RecordNameStatus.Waiting -> notice(texts.namesWaiting, "$t-names-waiting")
        RecordNameStatus.Missing -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(texts.namesMissing), style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.weight(1f).testTag("$t-names-missing"))
            TextButton(onClick = onRetry, modifier = Modifier.testTag("$t-names-retry")) {
                Text(stringResource(R.string.dashboard_records_retry))
            }
        }
    }
    if (state.paused) notice(R.string.dashboard_records_paused, "$t-records-paused")
    if (state.damaged) notice(texts.damaged, "$t-records-damaged")
}

@Composable
internal fun RecordsSearchField(query: String, texts: RecordTexts, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(texts.search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }, modifier = Modifier.testTag("${texts.tag}-search-clear")) {
                    Icon(Icons.Default.Clear, stringResource(R.string.dashboard_items_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag("${texts.tag}-search"),
    )
}

/** A row of single-choice chips that scrolls sideways. */
@Composable
internal fun <E> ChoiceChips(options: List<E>, selected: E, tag: (E) -> String, label: @Composable (E) -> String, onSelect: (E) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelect(option) }, modifier = Modifier.testTag(tag(option)),
                label = { Text(label(option)) })
        }
    }
}

/**
 * The first [RECORDS_FIRST_ROWS] rows, then a capped lazy list growing by [RECORDS_PAGE] with "show all", "show more"
 * and "collapse". [shown] is owned by the caller so that a new filter can start again from the first rows.
 */
@Composable
internal fun <R> PagedRecords(rows: List<R>, key: (R) -> Any, shown: Int, onShown: (Int) -> Unit, tag: String, row: @Composable (R) -> Unit) {
    val visible = rows.take(shown)
    if (shown <= RECORDS_FIRST_ROWS) {
        Column(Modifier.fillMaxWidth().testTag("$tag-records-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { row(it) }
        }
    } else {
        // Inside the section's scroll a lazy list needs a fixed cap, as the other blocks' long lists have.
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 480.dp).testTag("$tag-records-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = key) { row(it) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shown <= RECORDS_FIRST_ROWS && rows.size > RECORDS_FIRST_ROWS) {
            TextButton(onClick = { onShown(RECORDS_PAGE) }, modifier = Modifier.testTag("$tag-records-show-all")) {
                Text(stringResource(R.string.dashboard_show_all, rows.size))
            }
        }
        if (shown > RECORDS_FIRST_ROWS && rows.size > shown) {
            val more = minOf(RECORDS_PAGE, rows.size - shown)
            TextButton(onClick = { onShown(shown + RECORDS_PAGE) }, modifier = Modifier.testTag("$tag-records-more")) {
                Text(stringResource(R.string.dashboard_records_more, more, rows.size - shown))
            }
        }
        if (shown > RECORDS_FIRST_ROWS) {
            TextButton(onClick = { onShown(RECORDS_FIRST_ROWS) }, modifier = Modifier.testTag("$tag-records-collapse")) {
                Text(stringResource(R.string.friends2_collapse))
            }
        }
    }
}

/** A small tag next to a row's second line (90+, 완료). */
@Composable
internal fun RecordTag(text: String, tag: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container, modifier = Modifier.testTag(tag)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
            color = content, maxLines = 1, softWrap = false)
    }
}
