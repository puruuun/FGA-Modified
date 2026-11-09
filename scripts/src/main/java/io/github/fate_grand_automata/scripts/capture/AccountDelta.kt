package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Signed changes in absolute holdings, independent of the API response shape. */
@Serializable
data class AccountDelta(
    val items: Map<Long, Long> = emptyMap(),
    val qp: Long = 0,
    val mana: Long = 0,
    val rarePri: Long = 0,
    val freeStone: Long = 0,
    val chargeStone: Long = 0,
    val svts: Map<Long, Long> = emptyMap(),
    val commandCodes: Map<Long, Long> = emptyMap(),
    val friendPoint: Long = 0,
) {
    val isEmpty get() = items.values.all { it == 0L } &&
        svts.values.all { it == 0L } && commandCodes.values.all { it == 0L } &&
        qp == 0L && mana == 0L && rarePri == 0L && freeStone == 0L && chargeStone == 0L && friendPoint == 0L

    operator fun plus(other: AccountDelta) = AccountDelta(
        (items.keys + other.items.keys).associateWith { (items[it] ?: 0) + (other.items[it] ?: 0) }
            .filterValues { it != 0L },
        qp + other.qp, mana + other.mana, rarePri + other.rarePri,
        freeStone + other.freeStone, chargeStone + other.chargeStone,
        (svts.keys + other.svts.keys).associateWith { (svts[it] ?: 0L) + (other.svts[it] ?: 0L) }
            .filterValues { it != 0L },
        (commandCodes.keys + other.commandCodes.keys).associateWith { (commandCodes[it] ?: 0L) + (other.commandCodes[it] ?: 0L) }
            .filterValues { it != 0L },
        friendPoint = friendPoint + other.friendPoint,
    )

    companion object {
        /** The real shape keeps friendPoint in tblUserGame; userGame is only a fallback. */
        private fun friendPoint(state: AccountState): Long? =
            listOf("tblUserGame", "userGame").firstNotNullOfOrNull { table ->
                ((state.tables[table]?.firstOrNull() as? JsonObject)?.get("friendPoint") as? JsonPrimitive)?.longOrNull
            }

        private fun friendPointChange(previous: AccountState, next: AccountState): Long {
            val old = friendPoint(previous) ?: return 0L
            val new = friendPoint(next) ?: return 0L
            return new - old
        }

        /** A missing snapshot is not a baseline. Malformed rows/fields contribute zero. */
        fun between(previous: AccountState?, next: AccountState?): AccountDelta {
            if (previous == null || next == null) return AccountDelta()
            return runCatching {
                fun items(state: AccountState) = state.tables["userItem"].orEmpty().mapNotNull { element ->
                    val row = element as? JsonObject ?: return@mapNotNull null
                    val id = (row["itemId"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                    id to ((row["num"] as? JsonPrimitive)?.longOrNull ?: 0L)
                }.toMap()
                fun game(state: AccountState) =
                    (state.tables["userGame"] ?: state.tables["tblUserGame"])?.firstOrNull() as? JsonObject
                val oldItems = items(previous)
                val newItems = items(next)
                val oldGame = game(previous)
                val newGame = game(next)
                // Each card is one row. Recount both complete states, including second storage.
                fun cards(state: AccountState, tables: List<String>, field: String): Map<Long, Long> = buildMap {
                    for (table in tables) for (element in state.tables[table].orEmpty()) {
                        val row = element as? JsonObject ?: continue
                        val id = (row[field] as? JsonPrimitive)?.longOrNull ?: continue
                        put(id, (get(id) ?: 0L) + 1L)
                    }
                }
                fun cardChanges(tables: List<String>, field: String): Map<Long, Long> {
                    val old = cards(previous, tables, field)
                    val new = cards(next, tables, field)
                    return (old.keys + new.keys).associateWith { (new[it] ?: 0L) - (old[it] ?: 0L) }
                        .filterValues { it != 0L }
                }
                fun change(field: String) = ((newGame?.get(field) as? JsonPrimitive)?.longOrNull ?: 0L) -
                    ((oldGame?.get(field) as? JsonPrimitive)?.longOrNull ?: 0L)
                AccountDelta(
                    (oldItems.keys + newItems.keys).associateWith { (newItems[it] ?: 0L) - (oldItems[it] ?: 0L) }
                        .filterValues { it != 0L },
                    change("qp"), change("mana"), change("rarePri"), change("freeStone"), change("chargeStone"),
                    svts = cardChanges(listOf("userSvt", "userSvtStorage"), "svtId"),
                    commandCodes = cardChanges(listOf("userCommandCode"), "commandCodeId"),
                    friendPoint = friendPointChange(previous, next),
                )
            }.getOrDefault(AccountDelta())
        }
    }
}
