package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.DashboardItem
import io.github.fate_grand_automata.capture.InventoryGroup

/** Counts below this are drawn in the error colour in the gem and piece tables. The owner may change it. */
internal const val LOW_ITEM_COUNT = 10L

/** The materials list shows this many rows before "show more". */
internal const val ITEM_PREVIEW_COUNT = 12

/**
 * Event items, servant coins and "other" can hold hundreds of rows and start collapsed. Their lists are not lazy
 * (the pane scrolls as a whole), so they are cut here to keep expanding one from composing every icon at once.
 */
internal const val ITEM_COLLAPSED_PREVIEW_COUNT = 48

internal val ITEM_GROUPS_COLLAPSED_AT_START = setOf(InventoryGroup.Event, InventoryGroup.Coins, InventoryGroup.Other)

/** Item ids end in 1..7 for the seven basic classes. Suffix 8 only exists for some items. */
internal const val ITEM_CLASS_COLUMNS = 7
internal const val ITEM_EXTRA_COLUMN = 8

/** Row `base` + suffix is the item id: gems are 6001..6208, pieces and monuments 7001..7108. */
internal enum class ItemTableKind(val bases: List<Long>) {
    Gems(listOf(6000L, 6100L, 6200L)),
    Pieces(listOf(7000L, 7100L)),
}

internal fun tableItemId(base: Long, suffix: Int): Long = base + suffix

/** A row's cells follow [ItemTable.suffixes]; null is an item the account does not hold. */
internal data class ItemTableRow(val base: Long, val cells: List<Long?>)

internal data class ItemTable(val suffixes: List<Int>, val rows: List<ItemTableRow>)

/**
 * Suffix 8 gets a column only if the account holds a suffix 8 item in any row. Rows are always all there, so the
 * columns line up between the gem table and the piece table.
 */
internal fun buildItemTable(kind: ItemTableKind, items: List<DashboardItem>): ItemTable {
    val counts = items.associate { it.id to it.count }
    val hasExtra = kind.bases.any { counts.containsKey(tableItemId(it, ITEM_EXTRA_COLUMN)) }
    val suffixes = (1..ITEM_CLASS_COLUMNS).toList() + if (hasExtra) listOf(ITEM_EXTRA_COLUMN) else emptyList()
    return ItemTable(suffixes, kind.bases.map { base -> ItemTableRow(base, suffixes.map { counts[tableItemId(base, it)] }) })
}

internal fun isLowItemCount(count: Long) = count < LOW_ITEM_COUNT

/** A substring match that ignores case. A blank query matches everything. */
internal fun matchesItemQuery(name: String, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || name.contains(q, ignoreCase = true)
}

internal fun filterItems(items: List<DashboardItem>, query: String, nameOf: (Long) -> String): List<DashboardItem> =
    if (query.isBlank()) items else items.filter { matchesItemQuery(nameOf(it.id), query) }

/** [all] is the whole group (tables always draw every cell), [matches] what the query leaves of it. */
internal data class ItemGroupView(val group: InventoryGroup, val all: List<DashboardItem>, val matches: List<DashboardItem>)

/**
 * The groups to draw, in [InventoryGroup] order. Empty groups are left out, and so are groups without a match while a
 * query is set.
 */
internal fun visibleItemGroups(
    groups: Map<InventoryGroup, List<DashboardItem>>, query: String, nameOf: (Long) -> String,
): List<ItemGroupView> = InventoryGroup.entries.mapNotNull { group ->
    // Materials list in the reverse of the captured order (owner, 2026-11-22).
    val all = groups[group].orEmpty().let { if (group == InventoryGroup.Materials) it.asReversed() else it }
    val matches = filterItems(all, query, nameOf)
    if (all.isEmpty() || matches.isEmpty()) null else ItemGroupView(group, all, matches)
}

/** A set query opens the groups that match; without one, the stored open state decides. */
internal fun isItemGroupOpen(query: String, openByUser: Boolean) = query.isNotBlank() || openByUser

/** How many list rows to draw, and how many stay behind "show more". */
internal fun itemListLimit(group: InventoryGroup): Int =
    if (group in ITEM_GROUPS_COLLAPSED_AT_START) ITEM_COLLAPSED_PREVIEW_COUNT else ITEM_PREVIEW_COUNT

/** Columns of the plain item list for the width available to it, in dp. */
internal fun itemListColumns(widthDp: Float): Int = when {
    widthDp >= 720f -> 3
    widthDp >= 480f -> 2
    else -> 1
}

/** Below this width the tables use short class labels. */
internal const val ITEM_TABLE_SHORT_LABEL_BELOW_DP = 480f
