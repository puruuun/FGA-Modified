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
    /** Own servants whose bond rank went from below the cap to the cap in this battle. */
    val bondMax: Set<Long> = emptySet(),
) {
    /** Bond rows to show: points above zero, with the MAX flag. Also cleans up old saved logs. */
    fun displayBond(): List<BondDisplay> = bond.displayBond(bondMax)

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
            val newRows = updated.rows("userSvtCollection")
            val ownIds = ownDeckSvtIds(success, previousState)
            val bond = mutableMapOf<Long, Long>()
            val bondMax = mutableSetOf<Long>()
            newRows.forEach { row ->
                val id = row.number("svtId") ?: return@forEach
                if (ownIds != null && id !in ownIds) return@forEach
                val before = oldBond[id]
                val points = delta(row.number("friendship"), before?.number("friendship")) ?: return@forEach
                bond[id] = points
                val oldRank = before.number("friendshipRank")
                val newRank = row.number("friendshipRank")
                val exceed = (row.number("friendshipExceedCount") ?: before.number("friendshipExceedCount"))
                    ?.takeIf { it in 0..1000 }
                if (oldRank != null && newRank != null && exceed != null) {
                    val cap = 10 + exceed
                    if (oldRank < cap && newRank >= cap) bondMax += id
                }
            }
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
                masterExp = delta(newGame?.number("exp"), oldGame?.number("exp")), bond = bond, bondMax = bondMax,
            )
        }

        /**
         * Servant ids of the owner's own deck in this battle (same support rule as [CapturedBattle]), or null when the
         * deck is unknown, in which case every returned row is used.
         */
        private fun ownDeckSvtIds(success: JsonObject, previousState: AccountState?): Set<Long>? {
            val deck = (success["myDeck"].obj()?.get("svts") as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
            val setup = previousState?.tables?.get("battle")?.lastOrNull().obj()
                ?.get("battleInfo").obj()?.get("myDeck").obj()?.rows("svts").orEmpty()
            val userSvts = previousState?.tables?.get("userSvt").orEmpty().mapNotNull { it.obj() } + success.rows("oldUserSvt")
            val ids = deck.mapNotNull { element ->
                val row = element.obj()
                if (row.isSupport()) return@mapNotNull null
                val setupRow = setup.firstOrNull { it.number("id") == row.number("id") &&
                    row.number("userSvtId") != null && it.number("userSvtId") == row.number("userSvtId") && !it.isSupport() }
                row.number("svtId") ?: setupRow.number("svtId") ?: userSvts.firstOrNull {
                    row.number("userSvtId") != null && it.number("id") == row.number("userSvtId")
                }.number("svtId")
            }.toSet()
            return ids.takeIf { it.isNotEmpty() }
        }

        private fun JsonObject?.isSupport() = (this?.get("isFollowerSvt") as? JsonPrimitive)?.booleanOrNull == true ||
            (number("followerType") ?: 0) != 0L || (number("npcFollowerSvtId") ?: 0) != 0L

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

/** One bond line in the log: [points] is always above zero. */
data class BondDisplay(val svtId: Long, val points: Long, val max: Boolean)

/** Rows with zero points are dropped (older logs stored them). The MAX flag is kept only for shown servants. */
fun Map<Long, Long>.displayBond(max: Set<Long>): List<BondDisplay> =
    filterValues { it > 0L }.map { (id, points) -> BondDisplay(id, points, id in max) }
