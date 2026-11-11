package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*

/** One of the owner's own support decks: its 1-based position in the game's deck list and one slot per class. */
data class OwnDeck(val deckId: Long, val index: Int, val slots: List<FriendSlot>)

/**
 * The owner's own support and Grand formations, read from the stored account state. Display only:
 * shares slot data with the friend manager, while the dashboard supplies its own compact rows.
 */
data class OwnFormations(
    val main: List<OwnDeck> = emptyList(),
    val event: List<OwnDeck> = emptyList(),
    /** In seat order. Empty while the account has no Grand formation (KR today). */
    val grand: List<FriendGrandSlot> = emptyList(),
) {
    val hasSupport get() = main.isNotEmpty() || event.isNotEmpty()

    companion object {
        /** Class slot order inside a deck: all classes first, then the seven classes, then Extra. */
        private fun classOrder(classId: Int) = if (classId in 1..8) classId else 0

        /** Never throws: unreadable tables and rows are skipped, and a failure returns null. */
        fun from(state: AccountState, game: JsonObject? = null): OwnFormations? = runCatching {
            val userGame = game ?: rows(state, "userGame").firstOrNull()
            val slots = rows(state, "userSvtLeader").mapNotNull { row ->
                runCatching { FriendRoster.slot(row) }.getOrNull()?.takeIf { it.deckId != 0L }
            }.distinctBy { it.deckId to it.classId }
            val byDeck = slots.groupBy { it.deckId }
            val mainIds = userGame?.longs("fixMainSupportDeckIds").orEmpty()
            val eventIds = userGame?.longs("fixEventSupportDeckIds").orEmpty()
            fun decks(ids: List<Long>) = ids.mapIndexedNotNull { position, id ->
                byDeck[id]?.let { OwnDeck(id, position + 1, it.sortedBy { slot -> classOrder(slot.classId) }) }
            }
            val (main, event) = if (mainIds.isEmpty() && eventIds.isEmpty()) {
                // The deck lists are missing: every deck is listed under the normal formation.
                byDeck.keys.sorted().mapIndexed { position, id ->
                    OwnDeck(id, position + 1, byDeck.getValue(id).sortedBy { classOrder(it.classId) })
                } to emptyList()
            } else decks(mainIds) to decks(eventIds)
            val grand = rows(state, "userSvtGrand").mapNotNull { row -> runCatching { FriendRoster.grandSlot(row) }.getOrNull() }
                .distinctBy { it.seat }.sortedBy { it.seat }
            OwnFormations(main, event, grand).takeIf { it.hasSupport || it.grand.isNotEmpty() }
        }.getOrNull()

        private fun rows(state: AccountState, table: String): List<JsonObject> =
            runCatching { state.tables[table]?.mapNotNull { it as? JsonObject } }.getOrNull().orEmpty()

        private fun JsonObject.longs(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }
}

/** Grand graph ids count up from 10000, so 10001 is seat 1. Smaller ids are shown as they are. */
val FriendGrandSlot.seatNumber: Int get() = if (seat > 10000) seat - 10000 else seat
