package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class GainedItem(val type: Int, val objectId: Long, val gained: Long, val holdings: Long? = null)

/** Only gains, never account identifiers or raw server responses. Time is Unix seconds. */
@Serializable
data class BattleGains(
    val time: Long,
    val questId: Long? = null,
    val items: List<GainedItem> = emptyList(),
    val qp: Long? = null,
    val masterExp: Long? = null,
    val bond: Map<Long, Long> = emptyMap(),
) {
    companion object {
        /** Tolerant of missing/malformed rows. A missing success object is not a battle. */
        fun parse(body: JsonObject, previousState: AccountState?, now: Long = System.currentTimeMillis() / 1000): BattleGains? {
            val success = (body["response"] as? JsonArray)?.firstOrNull().obj()?.get("success").obj() ?: return null
            val updated = body["cache"].obj()?.get("updated").obj()
            val drops = success.rows("resultDropInfos").mapNotNull { row ->
                val type = row.number("type")?.toInt() ?: return@mapNotNull null
                val id = row.number("objectId") ?: return@mapNotNull null
                val num = row.number("num")?.takeIf { it >= 0 } ?: return@mapNotNull null
                GainedItem(type, id, num)
            }
            val qpParts = listOfNotNull(success.number("phaseClearQp"), success.number("firstClearRewardQp")) +
                drops.filter { it.type == 2 && it.objectId == 1L }.map { it.gained }
            val oldGame = success.rows("oldUserGame").firstOrNull()
                ?: previousState?.tables?.get("userGame")?.firstOrNull().obj()
                ?: previousState?.tables?.get("tblUserGame")?.firstOrNull().obj()
            val newGame = updated.rows("userGame").firstOrNull() ?: updated.rows("tblUserGame").firstOrNull()
            val oldBond = previousState?.tables?.get("userSvtCollection").orEmpty().mapNotNull { it.obj() }
                .associateBy { it.number("svtId") }.toMutableMap()
            // The result's old rows take precedence over an earlier account snapshot.
            success.rows("oldUserSvtCollection").forEach { oldBond[it.number("svtId")] = it }
            val bond = updated.rows("userSvtCollection").mapNotNull { row ->
                val id = row.number("svtId") ?: return@mapNotNull null
                delta(row.number("friendship"), oldBond[id]?.number("friendship"))?.let { id to it }
            }.toMap()
            val questId = updated.rows("userQuest").firstOrNull()?.number("questId")
                ?: updated.rows("battle").firstOrNull()?.number("questId")
                ?: updated.rows("userBattle").firstOrNull()?.number("questId")
                ?: previousState?.tables?.get("battle")?.lastOrNull().obj()?.number("questId")
                ?: previousState?.tables?.get("userBattle")?.lastOrNull().obj()?.number("questId")
                ?: previousState?.tables?.get("userQuest")?.singleOrNull().obj()?.number("questId")
            return BattleGains(
                time = now, questId = questId,
                items = sumItems(drops.filterNot { it.type == 2 && it.objectId == 1L }),
                qp = qpParts.takeIf { it.isNotEmpty() }?.sum(),
                masterExp = delta(newGame?.number("exp"), oldGame?.number("exp")), bond = bond,
            )
        }

        private fun delta(new: Long?, old: Long?): Long? =
            if (new != null && old != null && new >= old) new - old else null
    }
}

internal fun JsonElement?.obj() = this as? JsonObject
internal fun JsonObject?.number(key: String) = (this?.get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject?.rows(key: String) = (this?.get(key) as? JsonArray).orEmpty().mapNotNull { it.obj() }
internal fun sumItems(items: List<GainedItem>) = items.groupBy { it.type to it.objectId }.map { (key, rows) ->
    GainedItem(key.first, key.second, rows.sumOf { it.gained })
}

internal fun AccountState?.itemHoldings(): Map<Long, Long>? = this?.tables?.get("userItem")?.let { rows ->
    rows.mapNotNull { element ->
        val row = element.obj()
        val id = row.number("itemId") ?: return@mapNotNull null
        row.number("num")?.let { id to it }
    }.toMap()
}
