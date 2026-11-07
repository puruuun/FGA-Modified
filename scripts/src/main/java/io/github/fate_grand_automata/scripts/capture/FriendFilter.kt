package io.github.fate_grand_automata.scripts.capture

enum class FriendLogin { All, Inactive }

enum class LoginUnit(val seconds: Long) { Minutes(60), Hours(3_600), Days(86_400) }

const val FRIEND_INACTIVE_MIN = 1
const val FRIEND_INACTIVE_MAX = 999
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
data class FriendRow(val friend: Friend, val slot: FriendSlot, val event: Boolean, val deckIndex: Int?)

data class FriendFilter(
    val event: Boolean = false,
    val servantId: Long? = null,
    val ceId: Long? = null,
    val maxLimitBreak: Boolean = false,
    val login: FriendLogin = FriendLogin.All,
    /** Used with [FriendLogin.Inactive]: not logged in for at least this many [loginUnit]s. */
    val loginAmount: Int = FRIEND_INACTIVE_DEFAULT,
    val loginUnit: LoginUnit = LoginUnit.Days,
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
                FriendLogin.Inactive -> last > 0 && now - last >= filter.loginAmount * filter.loginUnit.seconds
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
