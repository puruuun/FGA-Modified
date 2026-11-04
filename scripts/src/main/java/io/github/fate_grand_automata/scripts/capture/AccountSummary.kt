package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.math.ceil

data class AccountSummary(
    val masterName: String, val level: Long, val ap: Long, val apMax: Long,
    val carryOverActPoint: Long, val stone: Long, val freeStone: Long, val chargeStone: Long,
    val fragments: Long, val goldenApples: Long, val silverApples: Long, val bronzeApples: Long,
    val copperApples: Long, val bronzeSaplings: Long, val tickets: Long,
    val manaPrisms: Long, val rarePrisms: Long, val qp: Long,
    val snapshotTime: Long, val lastUpdateTime: Long,
) {
    companion object {
        fun from(state: AccountState, now: Long): AccountSummary {
            val game = (state.tables["userGame"] ?: state.tables["tblUserGame"])?.firstOrNull() as? JsonObject ?: JsonObject(emptyMap())
            fun n(field: String) = (game[field] as? JsonPrimitive)?.longOrNull ?: 0L
            val items = state.tables["userItem"].orEmpty().mapNotNull { it as? JsonObject }
                .associate { ((it["itemId"] as? JsonPrimitive)?.longOrNull ?: -1L) to ((it["num"] as? JsonPrimitive)?.longOrNull ?: 0L) }
            fun item(id: Long) = items[id] ?: 0L
            val max = n("actMax").coerceAtLeast(0)
            val recover = n("actRecoverAt")
            val ap = if (recover <= now) max else
                (max - ceil((recover.toDouble() - now.toDouble()) / 300.0).toLong()).coerceIn(0, max)
            return AccountSummary(
                (game["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), n("lv"), ap, max,
                n("carryOverActPoint"), n("stone"), n("freeStone"), n("chargeStone"),
                item(16), item(100), item(101), item(104), item(102), item(103), item(4001),
                n("mana"), n("rarePri"), n("qp"), state.snapshotTime, state.lastUpdateTime,
            )
        }
    }
}
