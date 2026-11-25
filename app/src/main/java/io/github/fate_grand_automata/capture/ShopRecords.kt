package io.github.fate_grand_automata.capture

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.Locale

/**
 * One shop item of the public `nice_shop` or an event's `shop` list, reduced to what the shop records block shows.
 * [limit] is Atlas's `limitNum`; 0 is "no limit" (it is what the unlimited exchanges carry: anonymous servant exchange,
 * unlimited material exchanges, lottery shops). [costItem] 0 means the export names no price. Never holds user state.
 */
@Serializable
data class ShopSummary(
    val id: Long,
    val name: String? = null,
    val costItem: Long = 0,
    val costName: String? = null,
    val cost: Long = 0,
    val limit: Long = 0,
    val openedAt: Long = 0,
    val closedAt: Long = 0,
)

/** An event of the public `nice_event` export that has a shop, with only its name, period and shop items. */
@Serializable
data class EventShopSummary(
    val id: Long,
    val name: String? = null,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val shops: List<ShopSummary> = emptyList(),
)

/** The account's own server lists: `nice_shop` holds no event shop (all its rows have `eventId` 0), `nice_event` holds them all. */
data class ShopNames(val shops: List<ShopSummary> = emptyList(), val events: List<EventShopSummary> = emptyList()) {
    val complete get() = shops.isNotEmpty() && events.isNotEmpty()
}

/** The fields of a stored `userShop` row the block reads. [num] is the purchase total of one shop item, not a log. */
@Serializable
internal data class UserShopRow(
    val shopId: Long = 0,
    val num: Long = 0,
    val resetNum: Long = 0,
    val updatedAt: Long = 0,
)

internal fun readUserShop(raw: String): TableRead<UserShopRow> = raw.byteInputStream().use(::readUserShop)

internal fun readUserShop(stream: InputStream): TableRead<UserShopRow> = readTable(stream, UserShopRow.serializer()) { it.shopId > 0 }

/** Atlas item ids of the prisms whose spending the block estimates (checked in the KR and JP `nice_shop` cost items). */
internal const val MANA_PRISM = 3L
internal const val RARE_PRISM = 18L
internal const val PURE_PRISM = 46L
internal val PRISMS = listOf(MANA_PRISM, RARE_PRISM, PURE_PRISM)

/** Single choice; [Event] is the default, see [shopFilterFor]. */
enum class ShopFilter { Event, Mana, Rare, Pure, Other }

/**
 * The chip shown: the user's [chosen] one, otherwise [ShopFilter.Event], except while the name lists are missing or
 * waiting for a script to end: event shops cannot be told apart then, every item is a number under [ShopFilter.Other],
 * so the block starts there and goes back to the default once the names arrive.
 */
internal fun shopFilterFor(chosen: ShopFilter?, names: RecordNameStatus): ShopFilter = chosen ?: when (names) {
    RecordNameStatus.Missing, RecordNameStatus.Waiting -> ShopFilter.Other
    else -> ShopFilter.Event
}
enum class ShopSort { LastPurchase, Amount, Name }

/**
 * One shop item the account bought. [name] and the price are null or 0 when the account's own server lists do not know
 * the item. [limit] is null when unknown and 0 when the item has no limit.
 */
data class ShopItem(
    val id: Long,
    val name: String?,
    val costItem: Long,
    val costName: String?,
    val cost: Long,
    val num: Long,
    val limit: Long?,
    val resetNum: Long,
    /** Epoch seconds of the last change of the total; null when the row has none. */
    val updatedAt: Long?,
    val openedAt: Long?,
    val closedAt: Long?,
    /** The event whose shop sells the item; null for the other shops and for unknown items. */
    val eventId: Long?,
    internal val searchText: String,
) {
    val complete get() = limit != null && limit > 0 && num >= limit
    fun buyable(now: Long) = openedAt != null && closedAt != null && openedAt <= now && now < closedAt && (limit == 0L || (limit != null && num < limit))
}

/**
 * One event shop. [limited] counts the event's limited items in the public list, [completed] those the account bought up
 * to their limit; [items] are only the items the account bought.
 */
data class ShopEvent(
    val id: Long,
    val name: String?,
    val startedAt: Long,
    val endedAt: Long,
    val items: List<ShopItem>,
    val limited: Int,
    val completed: Int,
    /** The earliest opening and latest closing of the event's shop items: exchanges often stay open after the event ends. */
    val shopOpenedAt: Long,
    val shopClosedAt: Long,
    val hasUnlimited: Boolean,
    internal val searchText: String,
) {
    val lastPurchase get() = items.mapNotNull { it.updatedAt }.maxOrNull()
    val amount get() = items.sumOf { it.num }
    fun buyable(now: Long) = shopOpenedAt <= now && now < shopClosedAt && (hasUnlimited || completed < limited)
}

/**
 * [amount] is purchases × price over every item paid with [itemId], event shop items included ([includesEvents] says
 * whether any of them adds to it; the prism chips list only the other shops). An item whose total was reset
 * (`resetNum` > 0) is left out and counted in [excluded]: what its total means after a reset is not verified, so it is
 * not guessed.
 */
data class PrismTotal(val itemId: Long, val amount: Long, val excluded: Int, val includesEvents: Boolean = false)

/** [prisms] is empty when the `nice_shop` list, which holds the prism shops, is not available: the totals would read as 0. */
data class ShopRecords(val items: List<ShopItem>, val events: List<ShopEvent>, val prisms: List<PrismTotal>)

internal fun shopRecords(read: TableRead<UserShopRow>, names: ShopNames): ShopRecords {
    val byId = HashMap<Long, UserShopRow>(read.rows.size * 2)
    for (row in read.rows) {
        val old = byId[row.shopId]
        if (old == null || row.updatedAt > old.updatedAt) byId[row.shopId] = row
    }
    val plain = HashMap<Long, ShopSummary>()
    for (shop in names.shops) if (shop.id in byId) plain.putIfAbsent(shop.id, shop)
    val eventOf = HashMap<Long, Pair<EventShopSummary, ShopSummary>>()
    for (event in names.events) for (shop in event.shops) {
        if (shop.id in byId && shop.id !in plain) eventOf.putIfAbsent(shop.id, event to shop)
    }
    val items = byId.values.map { row ->
        val hit = eventOf[row.shopId]
        val summary = plain[row.shopId] ?: hit?.second
        ShopItem(
            id = row.shopId,
            name = summary?.name?.takeIf { it.isNotBlank() },
            costItem = summary?.costItem ?: 0,
            costName = summary?.costName?.takeIf { it.isNotBlank() },
            cost = summary?.cost ?: 0,
            num = row.num,
            limit = summary?.limit,
            resetNum = row.resetNum,
            updatedAt = row.updatedAt.takeIf { it > 0 },
            openedAt = summary?.openedAt,
            closedAt = summary?.closedAt,
            eventId = hit?.first?.id,
            searchText = listOfNotNull("#${row.shopId}", summary?.name, summary?.costName).joinToString("\u0000").lowercase(Locale.ROOT),
        )
    }
    val eventItems = items.filter { it.eventId != null }.groupBy { it.eventId!! }
    val events = names.events.filter { it.id in eventItems }.map { event ->
        val bought = eventItems.getValue(event.id)
        val numById = bought.associate { it.id to it.num }
        val limitedShops = event.shops.filter { it.limit > 0 }
        ShopEvent(
            id = event.id,
            name = event.name?.takeIf { it.isNotBlank() },
            startedAt = event.startedAt,
            endedAt = event.endedAt,
            items = bought,
            limited = limitedShops.size,
            completed = limitedShops.count { (numById[it.id] ?: 0) >= it.limit },
            shopOpenedAt = event.shops.minOfOrNull { it.openedAt } ?: 0,
            shopClosedAt = event.shops.maxOfOrNull { it.closedAt } ?: 0,
            hasUnlimited = event.shops.any { it.limit == 0L },
            searchText = listOfNotNull("#${event.id}", event.name).joinToString("\u0000").lowercase(Locale.ROOT),
        )
    }
    val prisms = if (names.shops.isEmpty()) emptyList() else PRISMS.map { prism ->
        val paid = items.filter { it.costItem == prism }
        val counted = paid.filter { it.resetNum <= 0 }
        PrismTotal(prism, counted.sumOf { it.num * it.cost }, paid.count { it.resetNum > 0 },
            includesEvents = counted.any { it.eventId != null && it.num > 0 })
    }
    return ShopRecords(items.filter { it.eventId == null }, events, prisms)
}

sealed interface ShopRow {
    val key: String

    data class Item(val item: ShopItem) : ShopRow {
        override val key get() = "item:${item.id}"
    }

    /** [items] are the event's items that pass the search and the toggle. */
    data class Event(val event: ShopEvent, val items: List<ShopItem>) : ShopRow {
        override val key get() = "event:${event.id}"
    }
}

/** [now] is epoch seconds, for "buyable only": open now and below its limit; release conditions are not evaluated. */
internal fun shopView(records: ShopRecords, filter: ShopFilter, query: String, sort: ShopSort, buyableOnly: Boolean, now: Long): List<ShopRow> {
    val needle = query.trim().lowercase(Locale.ROOT)
    fun matches(item: ShopItem) = needle.isEmpty() || needle in item.searchText
    fun passes(item: ShopItem) = matches(item) && (!buyableOnly || item.buyable(now))
    if (filter == ShopFilter.Event) {
        val rows = records.events.mapNotNull { event ->
            if (buyableOnly && !event.buyable(now)) return@mapNotNull null
            val nameHit = needle.isNotEmpty() && needle in event.searchText
            val items = event.items.filter { (nameHit || matches(it)) && (!buyableOnly || it.buyable(now)) }
            if (needle.isNotEmpty() && !nameHit && items.isEmpty()) null else ShopRow.Event(event, items)
        }
        val comparator = when (sort) {
            ShopSort.LastPurchase -> compareByDescending<ShopRow.Event> { it.event.lastPurchase ?: 0 }.thenBy { it.event.id }
            ShopSort.Amount -> compareByDescending<ShopRow.Event> { it.event.amount }.thenBy { it.event.id }
            ShopSort.Name -> compareBy<ShopRow.Event> { it.event.name == null }.thenBy { it.event.name.orEmpty() }.thenBy { it.event.id }
        }
        return rows.sortedWith(comparator)
    }
    val items = records.items.filter { item ->
        val inGroup = when (filter) {
            ShopFilter.Mana -> item.costItem == MANA_PRISM
            ShopFilter.Rare -> item.costItem == RARE_PRISM
            ShopFilter.Pure -> item.costItem == PURE_PRISM
            else -> item.costItem !in PRISMS
        }
        inGroup && passes(item)
    }
    val comparator = when (sort) {
        ShopSort.LastPurchase -> compareByDescending<ShopItem> { it.updatedAt ?: 0 }.thenBy { it.id }
        ShopSort.Amount -> compareByDescending<ShopItem> { it.num }.thenBy { it.id }
        // An item without a name sorts after the named ones, by its number.
        ShopSort.Name -> compareBy<ShopItem> { it.name == null }.thenBy { it.name.orEmpty() }.thenBy { it.id }
    }
    return items.sortedWith(comparator).map { ShopRow.Item(it) }
}

/** The shop records block's state; null records while the stored table is read. */
typealias ShopRecordsState = RecordsState<ShopRecords>
