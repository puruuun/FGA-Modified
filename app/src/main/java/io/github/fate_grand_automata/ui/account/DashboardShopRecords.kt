package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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

internal typealias ShopRecordsSource = RecordsSource<ShopRecords>

private val shopTexts = RecordTexts(
    tag = "shop",
    loading = R.string.dashboard_shop_loading,
    namesLoading = R.string.dashboard_shop_names_loading,
    namesWaiting = R.string.dashboard_shop_names_waiting,
    namesMissing = R.string.dashboard_shop_names_missing,
    damaged = R.string.dashboard_shop_damaged,
    search = R.string.dashboard_shop_search,
)

@Composable
internal fun ShopRecordsBlock(source: ShopRecordsSource) {
    val flow = remember(source) { source.records() }
    val state by flow.collectAsStateWithLifecycle(ShopRecordsState())
    val search = rememberRecordsSearch()
    val actions: (@Composable RowScope.() -> Unit)? = if (state.records != null) { { RecordsSearchButton(search, shopTexts) } } else null
    DashboardBlock(DashboardBlockId.ShopRecords, stringResource(R.string.dashboard_shop_records), actions = actions) {
        ShopRecordsContent(state, source.retry, search)
    }
}

@Composable
internal fun ColumnScope.ShopRecordsContent(state: ShopRecordsState, onRetry: () -> Unit, search: RecordsSearch) {
    val records = state.records
    if (records == null) {
        RecordsLoading(shopTexts)
        return
    }
    var chosen by rememberSaveable { mutableStateOf<ShopFilter?>(null) }
    val filter = shopFilterFor(chosen, state.names)
    var sort by rememberSaveable { mutableStateOf(ShopSort.LastPurchase) }
    val query = search.query
    // A new filter, search or sort starts again from the first rows.
    var shown by rememberSaveable(filter, query, sort) { mutableIntStateOf(RECORDS_FIRST_ROWS) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var rows by remember { mutableStateOf<List<ShopRow>?>(null) }
    LaunchedEffect(records, filter, query, sort) {
        rows = withContext(Dispatchers.Default) { shopView(records, filter, query, sort) }
    }

    RecordNotices(state, shopTexts, onRetry)
    PrismSummary(records.prisms)
    if (search.visible) RecordsSearchField(search, shopTexts)
    ChoiceChips(ShopFilter.entries, filter, { "shop-filter:${it.name}" }, { stringResource(it.label()) }, { chosen = it })
    ChoiceChips(ShopSort.entries, sort, { "shop-sort:${it.name}" }, { stringResource(it.label()) }, { sort = it })
    val current = rows ?: return
    if (current.isEmpty()) {
        Text(stringResource(R.string.dashboard_shop_none), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("shop-records-empty"))
        return
    }
    PagedRecords(current, { it.key }, shown, { shown = it }, "shop") { row ->
        when (row) {
            is ShopRow.Item -> ShopItemRow(row.item)
            is ShopRow.Event -> ShopEventRow(row, row.event.id in expanded) {
                expanded = if (row.event.id in expanded) expanded - row.event.id else expanded + row.event.id
            }
        }
    }
}

/**
 * Purchases × price per prism over every shop; "≈" because a price may have changed and reset totals are left out
 * (owner, #730). The prism chips list only shops outside events, so a total with event items says so.
 */
@Composable
private fun PrismSummary(prisms: List<PrismTotal>) {
    if (prisms.isEmpty()) return
    val parts = prisms.map {
        val text = if (it.includesEvents) R.string.dashboard_shop_prism_total_events else R.string.dashboard_shop_prism_total
        stringResource(text, stringResource(prismName(it.itemId)), it.amount.grouped())
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("shop-records-summary"))
    val excluded = prisms.sumOf { it.excluded }
    if (excluded > 0) {
        Text(stringResource(R.string.dashboard_shop_prism_excluded, excluded), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("shop-records-excluded"))
    }
}

private fun prismName(itemId: Long) = when (itemId) {
    MANA_PRISM -> R.string.dashboard_shop_filter_mana
    RARE_PRISM -> R.string.dashboard_shop_filter_rare
    else -> R.string.dashboard_shop_filter_pure
}

@Composable
private fun ShopEventRow(row: ShopRow.Event, open: Boolean, onToggle: () -> Unit) {
    val event = row.event
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag("shop-event:${event.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("shop-event-toggle:${event.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.name ?: "#${event.id}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (event.startedAt > 0 && event.endedAt > 0) {
                    Text("${formatEpochDate(event.startedAt)} ~ ${formatEpochDate(event.endedAt)}", style = MaterialTheme.typography.bodySmall.merge(recordNumeric), color = muted)
                }
            }
            Text(stringResource(R.string.dashboard_shop_progress, event.completed, event.limited), style = MaterialTheme.typography.bodyMedium.merge(recordNumeric),
                maxLines = 1, softWrap = false, modifier = Modifier.testTag("shop-event-progress:${event.id}"))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = muted)
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row.items.sortedByDescending { it.updatedAt ?: 0 }.forEach { ShopItemRow(it) }
            }
        }
    }
}

@Composable
private fun ShopItemRow(item: ShopItem) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().testTag("shop:${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name ?: "#${item.id}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.costItem > 0) {
                Text(stringResource(R.string.dashboard_shop_price, item.costName ?: "#${item.costItem}", item.cost.grouped()),
                    style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.complete) {
                    RecordTag(stringResource(R.string.dashboard_shop_complete), "shop-complete:${item.id}",
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                val limit = item.limit
                Text(if (limit != null && limit > 0) "${item.num.grouped()} / ${limit.grouped()}" else item.num.grouped(),
                    style = MaterialTheme.typography.bodyMedium.merge(recordNumeric), maxLines = 1, softWrap = false,
                    modifier = Modifier.testTag("shop-count:${item.id}"))
            }
            item.updatedAt?.let {
                Text(formatEpochDate(it), style = MaterialTheme.typography.bodySmall.merge(recordNumeric), color = muted, maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun ShopFilter.label() = when (this) {
    ShopFilter.Event -> R.string.dashboard_shop_filter_event
    ShopFilter.Mana -> R.string.dashboard_shop_filter_mana
    ShopFilter.Rare -> R.string.dashboard_shop_filter_rare
    ShopFilter.Pure -> R.string.dashboard_shop_filter_pure
    ShopFilter.Other -> R.string.dashboard_shop_filter_other
}

private fun ShopSort.label() = when (this) {
    ShopSort.LastPurchase -> R.string.dashboard_shop_sort_last
    ShopSort.Amount -> R.string.dashboard_shop_sort_amount
    ShopSort.Name -> R.string.dashboard_shop_sort_name
}
