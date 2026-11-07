package io.github.fate_grand_automata.scripts.capture

enum class FriendLogin { All, Within7Days, Over7Days }

/** One list row: a non-empty support slot of a friend. [deckIndex] is the 1-based "편성 N", or null if unknown. */
data class FriendRow(val friend: Friend, val slot: FriendSlot, val event: Boolean, val deckIndex: Int?)

data class FriendFilter(
    val event: Boolean = false,
    val servantId: Long? = null,
    val ceId: Long? = null,
    val maxLimitBreak: Boolean = false,
    val login: FriendLogin = FriendLogin.All,
)

object FriendList {
    const val WEEK_SECONDS = 7 * 24 * 3600L
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
                FriendLogin.Within7Days -> last > 0 && now - last <= WEEK_SECONDS
                FriendLogin.Over7Days -> last > 0 && now - last > WEEK_SECONDS
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
