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
) {
    val isEmpty get() = items.values.all { it == 0L } &&
        qp == 0L && mana == 0L && rarePri == 0L && freeStone == 0L && chargeStone == 0L

    operator fun plus(other: AccountDelta) = AccountDelta(
        (items.keys + other.items.keys).associateWith { (items[it] ?: 0) + (other.items[it] ?: 0) }
            .filterValues { it != 0L },
        qp + other.qp, mana + other.mana, rarePri + other.rarePri,
        freeStone + other.freeStone, chargeStone + other.chargeStone,
    )

    companion object {
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
                fun change(field: String) = ((newGame?.get(field) as? JsonPrimitive)?.longOrNull ?: 0L) -
                    ((oldGame?.get(field) as? JsonPrimitive)?.longOrNull ?: 0L)
                AccountDelta(
                    (oldItems.keys + newItems.keys).associateWith { (newItems[it] ?: 0L) - (oldItems[it] ?: 0L) }
                        .filterValues { it != 0L },
                    change("qp"), change("mana"), change("rarePri"), change("freeStone"), change("chargeStone"),
                )
            }.getOrDefault(AccountDelta())
        }
    }
}
