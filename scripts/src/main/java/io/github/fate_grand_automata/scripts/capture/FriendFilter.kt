package io.github.fate_grand_automata.scripts.capture

enum class FriendLogin { All, Inactive }

enum class LoginUnit(val seconds: Long) { Minutes(60), Hours(3_600), Days(86_400) }

const val FRIEND_INACTIVE_MIN = 0
const val FRIEND_INACTIVE_MAX = 99
const val FRIEND_INACTIVE_DEFAULT = 3

/** The six CEs that give 20% bond to a borrowed support; Teatime (15%) is pinned separately. */
val FRIEND_BOND_20_CE_IDS = setOf(9407480L, 9407740L, 9407850L, 9408060L, 9408220L, 9408390L)
const val FRIEND_TEATIME_CE_ID = 9403520L

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
data class FriendRow(val friend: Friend, val slot: FriendSlot, val event: Boolean, val deckIndex: Int?,
    val grandSlot: FriendGrandSlot? = null) {
    val ces: List<FriendCe> get() = listOfNotNull(
        FriendCe(slot.ceId, slot.ceLimitCount, slot.ceLv).takeIf { it.id != 0L },
        grandSlot?.ce3?.takeIf { it.id != 0L },
    )
}

enum class FriendFormation { Main, Event, Grand }
enum class FriendCeLimit { All, Max, NotMax }

data class FriendFilter(
    val formation: FriendFormation = FriendFormation.Main,
    val servantId: Long? = null,
    val ceIds: Set<Long> = emptySet(),
    val ceLimit: FriendCeLimit = FriendCeLimit.All,
    val login: FriendLogin = FriendLogin.All,
    /** Clamped on use so restored amounts from older screens remain safe. */
    val loginAmount: Int = FRIEND_INACTIVE_DEFAULT,
    val loginUnit: LoginUnit = LoginUnit.Days,
    val friendName: String = "",
    val minLevel: Int = 0,
) {
    val inactiveAmount get() = loginAmount.coerceIn(FRIEND_INACTIVE_MIN, FRIEND_INACTIVE_MAX)
}

object FriendList {
    const val MAX_LIMIT_COUNT = 4

    /** Every slot of the chosen deck type, before the servant/CE/limit-break/login filters. */
    fun rows(roster: FriendRoster, event: Boolean): List<FriendRow> = roster.friends.values.flatMap { friend ->
        friend.slots.filter { friend.isEvent(it) == event }.map { slot ->
            val decks = if (event) friend.eventDeckIds else friend.mainDeckIds
            FriendRow(friend, slot, event, decks.indexOf(slot.deckId).takeIf { it >= 0 }?.plus(1))
        }
    }

    fun rows(roster: FriendRoster, formation: FriendFormation): List<FriendRow> = when (formation) {
        FriendFormation.Main -> rows(roster, false)
        FriendFormation.Event -> rows(roster, true)
        FriendFormation.Grand -> roster.friends.values.flatMap { friend ->
            friend.grandSlots.map { FriendRow(friend, it.supportSlot(), false, null, it) }
        }
    }

    /** Servants (or CEs) present in the chosen deck type with their slot counts. */
    fun servantCounts(rows: List<FriendRow>): Map<Long, Int> = rows.groupingBy { it.slot.svtId }.eachCount()
    fun ceCounts(rows: List<FriendRow>): Map<Long, Int> = rows
        .flatMap { row -> row.ces.map { it.id }.distinct() }
        .groupingBy { it }.eachCount()

    /** Newest login first, then friend and deck/class (Grand seat for the Grand formation). */
    fun select(roster: FriendRoster, filter: FriendFilter, now: Long): List<FriendRow> = rows(roster, filter.formation)
        .filter { it.friend.name.contains(filter.friendName, ignoreCase = true) }
        .filter { filter.servantId == null || it.slot.svtId == filter.servantId }
        .filter { it.slot.lv >= filter.minLevel }
        .filter { row ->
            val candidates = row.ces.filter { filter.ceIds.isEmpty() || it.id in filter.ceIds }
            (filter.ceIds.isEmpty() || candidates.isNotEmpty()) && when (filter.ceLimit) {
                FriendCeLimit.All -> true
                FriendCeLimit.Max -> candidates.any { it.limitCount == MAX_LIMIT_COUNT }
                FriendCeLimit.NotMax -> candidates.any { it.limitCount != MAX_LIMIT_COUNT }
            }
        }
        .filter {
            val last = it.friend.lastLogin
            when (filter.login) {
                FriendLogin.All -> true
                FriendLogin.Inactive -> last > 0 && now - last >= filter.inactiveAmount * filter.loginUnit.seconds
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
