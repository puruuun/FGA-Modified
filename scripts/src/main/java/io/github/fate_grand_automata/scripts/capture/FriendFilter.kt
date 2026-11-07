package io.github.fate_grand_automata.scripts.capture

import java.util.Calendar
import java.util.TimeZone

enum class FriendLogin { All, After, Before }

/** The six CEs that give 20% bond to a borrowed support; Teatime (15%) is pinned separately. */
val FRIEND_BOND_20_CE_IDS = setOf(9407480L, 9407740L, 9407850L, 9408060L, 9408220L, 9408390L)
const val FRIEND_TEATIME_CE_ID = 9403520L

/** DatePicker uses UTC dates. Interpret its calendar date as midnight in the user's zone. */
fun friendLocalMidnight(pickerMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickerMillis }
    return Calendar.getInstance(zone).apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis / 1000
}

fun friendDefaultDate(nowSeconds: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = nowSeconds * 1000; add(Calendar.DAY_OF_MONTH, -7) }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

data class FriendOption(val id: Long, val name: String, val count: Int)

fun friendServantOptions(counts: Map<Long, Int>, names: Map<Long, String>, query: String = ""): List<FriendOption> =
    counts.map { (id, count) -> FriendOption(id, names[id] ?: "#$id", count) }
        .filter { it.name.contains(query, ignoreCase = true) }
        .sortedWith(compareBy({ it.name }, { it.id }))

fun friendCeOptions(counts: Map<Long, Int>, names: Map<Long, String>): List<FriendOption> =
    friendServantOptions(counts, names).sortedWith(compareBy<FriendOption> {
        when (it.id) { FRIEND_TEATIME_CE_ID -> 0; in FRIEND_BOND_20_CE_IDS -> 1; else -> 2 }
    }.thenBy { it.name }.thenBy { it.id })

/** One list row: a non-empty support slot of a friend. [deckIndex] is the 1-based "편성 N", or null if unknown. */
data class FriendRow(val friend: Friend, val slot: FriendSlot, val event: Boolean, val deckIndex: Int?)

data class FriendFilter(
    val event: Boolean = false,
    val servantId: Long? = null,
    val ceId: Long? = null,
    val maxLimitBreak: Boolean = false,
    val login: FriendLogin = FriendLogin.All,
    val loginDayStart: Long = 0,
)

object FriendList {
    const val MAX_LIMIT_COUNT = 4

    /** Every slot of the chosen deck type, before the servant/CE/limit-break/login filters. */
    fun rows(roster: FriendRoster, event: Boolean): List<FriendRow> = roster.friends.values.flatMap { friend ->
        friend.slots.filter { friend.isEvent(it) == event }.map { slot ->
            val decks = if (event) friend.eventDeckIds else friend.mainDeckIds
            FriendRow(friend, slot, event, decks.indexOf(slot.deckId).takeIf { it >= 0 }?.plus(1))
        }
    }

    /** Servants (or CEs) present in the chosen deck type with their slot counts. */
    fun servantCounts(rows: List<FriendRow>): Map<Long, Int> = rows.groupingBy { it.slot.svtId }.eachCount()
    fun ceCounts(rows: List<FriendRow>): Map<Long, Int> =
        rows.filter { it.slot.ceId != 0L }.groupingBy { it.slot.ceId }.eachCount()

    /** Newest login first, then friend, deck and class. */
    fun select(roster: FriendRoster, filter: FriendFilter, now: Long): List<FriendRow> = rows(roster, filter.event)
        .filter { filter.servantId == null || it.slot.svtId == filter.servantId }
        .filter { filter.ceId == null || it.slot.ceId == filter.ceId }
        .filter { !filter.maxLimitBreak || (it.slot.ceId != 0L && it.slot.ceLimitCount == MAX_LIMIT_COUNT) }
        .filter {
            val last = it.friend.lastLogin
            when (filter.login) {
                FriendLogin.All -> true
                FriendLogin.After -> last > 0 && last >= filter.loginDayStart
                FriendLogin.Before -> last > 0 && last < filter.loginDayStart
            }
        }
        .sortedWith(
            compareByDescending<FriendRow> { it.friend.lastLogin }
                .thenBy { it.friend.userId.toLongOrNull() ?: Long.MAX_VALUE }
                .thenBy { it.friend.userId }
                .thenBy { it.slot.deckId }
                .thenBy { it.slot.classId },
        )
}
