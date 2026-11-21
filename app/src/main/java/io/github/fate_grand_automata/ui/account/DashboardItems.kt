package io.github.fate_grand_automata.ui.account

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.log.ResourceIcon
import io.github.fate_grand_automata.ui.log.resourceName
import io.github.fate_grand_automata.util.grouped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Items section. Its content and helpers stay in the Items files so nothing else in the dashboard is touched. */
@Composable
internal fun DashboardItemsSection(items: List<DashboardItem>, names: GameNameMap) {
    DashboardBlock(DashboardBlockId.Items, stringResource(R.string.dashboard_items)) { Inventory(items, names) }
}

private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

@Composable private fun Inventory(items: List<DashboardItem>, names: GameNameMap) {
    var groups by remember { mutableStateOf(emptyMap<InventoryGroup, List<DashboardItem>>()) }
    LaunchedEffect(items) { groups = withContext(Dispatchers.Default) { items.groupBy { it.group } } }
    var query by rememberSaveable { mutableStateOf("") }
    // Kept here, not in the group: a group that does not match leaves the composition, and its own state would be lost
    // with it. Names of the groups whose open state the user flipped from the default, and of those shown in full.
    var flipped by rememberSaveable { mutableStateOf("") }
    var expandedAll by rememberSaveable { mutableStateOf("") }
    val views = remember(groups, query, names) { visibleItemGroups(groups, query, names::item) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ItemSearchField(query) { query = it }
            if (query.isNotBlank() && views.isEmpty()) {
                Text(
                    stringResource(R.string.dashboard_items_no_match), Modifier.padding(vertical = 12.dp).testTag("items-no-match"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            views.forEach { view -> key(view.group) {
                ItemGroupSection(
                    view, query, names, widthDp,
                    flipped = view.group.name in flipped.split(','),
                    onFlip = { flipped = toggleName(flipped, view.group.name) },
                    showAll = view.group.name in expandedAll.split(','),
                    onShowAll = { expandedAll = toggleName(expandedAll, view.group.name) },
                )
            } }
        }
    }
}

private fun toggleName(list: String, name: String): String =
    list.split(',').filter { it.isNotEmpty() }.let { if (name in it) it - name else it + name }.joinToString(",")

@Composable private fun ItemSearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(R.string.dashboard_items_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else ({
            IconButton(onClick = { onChange("") }, modifier = Modifier.testTag("items-search-clear")) {
                Icon(Icons.Default.Clear, stringResource(R.string.dashboard_items_search_clear))
            }
        }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag("items-search"),
    )
}

@Composable private fun ItemGroupSection(
    view: ItemGroupView, query: String, names: GameNameMap, widthDp: Float,
    flipped: Boolean, onFlip: () -> Unit, showAll: Boolean, onShowAll: () -> Unit,
) {
    val group = view.group
    val openByUser = (group !in ITEM_GROUPS_COLLAPSED_AT_START) != flipped
    val searching = query.isNotBlank()
    val open = isItemGroupOpen(query, openByUser)
    Column(Modifier.fillMaxWidth().testTag("items-group:${group.name}")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !searching, role = Role.Button) { onFlip() }
                .testTag("items-group-header:${group.name}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(group.label()), style = MaterialTheme.typography.titleSmall)
            Text(
                pluralStringResource(R.plurals.dashboard_items_count, view.matches.size, view.matches.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (!searching) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (open) when (group) {
            InventoryGroup.Gems -> ItemTableView(ItemTableKind.Gems, view, searching, names, widthDp)
            InventoryGroup.Pieces -> ItemTableView(ItemTableKind.Pieces, view, searching, names, widthDp)
            InventoryGroup.Grails -> ItemList(view.matches, 1, Int.MAX_VALUE, showAll, onShowAll, group, names)
            else -> ItemList(view.matches, itemListColumns(widthDp), itemListLimit(group), showAll, onShowAll, group, names)
        }
    }
}

@Composable private fun ItemList(
    rows: List<DashboardItem>, columns: Int, limit: Int, showAll: Boolean, onShowAll: () -> Unit, group: InventoryGroup, names: GameNameMap,
) {
    val shown = if (showAll) rows else rows.take(limit)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.chunked(columns).forEach { chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                chunk.forEach { ItemRow(it, names, Modifier.weight(1f)) }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (shown.size < rows.size) TextButton(onClick = onShowAll, modifier = Modifier.testTag("items-more:${group.name}")) {
            Text(stringResource(R.string.dashboard_items_show_more, rows.size - shown.size))
        }
    }
}

@Composable private fun ItemRow(item: DashboardItem, names: GameNameMap, modifier: Modifier) {
    val name = resourceName(2, item.id, names)
    Row(
        modifier.heightIn(min = 36.dp).testTag("item-row:${item.id}").semantics(mergeDescendants = true) {
            contentDescription = "$name, ${item.count.grouped()}"
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp)) { ResourceIcon(2, item.id, names) }
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.count.grouped(), style = MaterialTheme.typography.bodyLarge.tabular(), textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

private val CLASS_LABELS = listOf(
    R.string.p_support_class_saber, R.string.p_support_class_archer, R.string.p_support_class_lancer, R.string.p_support_class_rider,
    R.string.p_support_class_caster, R.string.p_support_class_assassin, R.string.p_support_class_berserker,
)
private val CLASS_SHORT_LABELS = listOf(
    R.string.dashboard_item_class_short_saber, R.string.dashboard_item_class_short_archer, R.string.dashboard_item_class_short_lancer,
    R.string.dashboard_item_class_short_rider, R.string.dashboard_item_class_short_caster, R.string.dashboard_item_class_short_assassin,
    R.string.dashboard_item_class_short_berserker,
)

@StringRes private fun rowLabel(base: Long) = when (base) {
    6000L -> R.string.dashboard_item_row_gem
    6100L -> R.string.dashboard_item_row_magic_gem
    6200L -> R.string.dashboard_item_row_secret_gem
    7000L -> R.string.dashboard_item_row_piece
    else -> R.string.dashboard_item_row_monument
}

/**
 * Classes across, one row per kind. Narrow widths use short class labels; the full name stays in the content
 * description. Counts are right-aligned under right-aligned headers so the digits line up.
 */
@Composable private fun ItemTableView(kind: ItemTableKind, view: ItemGroupView, searching: Boolean, names: GameNameMap, widthDp: Float) {
    val table = remember(kind, view.all) { buildItemTable(kind, view.all) }
    val matchIds = remember(view.matches, searching) { if (searching) view.matches.mapTo(mutableSetOf()) { it.id } else null }
    val compact = widthDp < ITEM_TABLE_SHORT_LABEL_BELOW_DP
    val labelWidth = if (compact) 56.dp else 88.dp
    val textStyle = (if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium).tabular()
    val extraName = kind.bases.firstNotNullOfOrNull { names.items[tableItemId(it, ITEM_EXTRA_COLUMN)] }
    val fulls = table.suffixes.map { suffix ->
        if (suffix <= ITEM_CLASS_COLUMNS) stringResource(CLASS_LABELS[suffix - 1]) else extraName ?: suffix.toString()
    }
    val none = stringResource(R.string.dashboard_items_none)
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(bottom = 8.dp).testTag("item-table:${kind.name}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(labelWidth))
            table.suffixes.forEachIndexed { index, suffix ->
                val full = fulls[index]
                val shown = when {
                    !compact -> full
                    suffix <= ITEM_CLASS_COLUMNS -> stringResource(CLASS_SHORT_LABELS[suffix - 1])
                    else -> full.take(2)
                }
                Text(
                    shown, Modifier.weight(1f).padding(end = 4.dp).semantics { contentDescription = full }.testTag("item-table-header:${kind.name}:$suffix"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val rowText = stringResource(rowLabel(row.base))
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rowText, Modifier.width(labelWidth), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                table.suffixes.forEachIndexed { index, suffix ->
                    val id = tableItemId(row.base, suffix)
                    ItemTableCell(
                        id, row.cells[index], matchIds?.contains(id) == true, textStyle, names, "$rowText ${fulls[index]}", none,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable private fun ItemTableCell(
    id: Long, count: Long?, highlighted: Boolean, style: TextStyle, names: GameNameMap, slotName: String, none: String, modifier: Modifier,
) {
    val name = if (count == null) slotName else names.item(id)
    Box(
        modifier.heightIn(min = 32.dp).padding(horizontal = 1.dp).testTag("item-cell:$id").semantics(mergeDescendants = true) {
            contentDescription = "$name, ${count?.grouped() ?: none}"
        }.then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraSmall) else Modifier),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (count == null) Text("–", Modifier.padding(end = 4.dp), style = style, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        else Text(
            count.grouped(), Modifier.padding(end = 4.dp), style = style, maxLines = 1, softWrap = false,
            color = if (isLowItemCount(count)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun InventoryGroup.label() = when (this) {
    InventoryGroup.Materials -> R.string.dashboard_group_materials
    InventoryGroup.Gems -> R.string.dashboard_group_gems
    InventoryGroup.Pieces -> R.string.dashboard_group_pieces
    InventoryGroup.Grails -> R.string.dashboard_group_grails
    InventoryGroup.Event -> R.string.dashboard_group_event
    InventoryGroup.Coins -> R.string.dashboard_group_coins
    InventoryGroup.Other -> R.string.dashboard_group_other
}
