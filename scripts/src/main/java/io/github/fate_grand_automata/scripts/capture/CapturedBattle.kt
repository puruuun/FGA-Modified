package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*

data class CapturedBond(
    val slot: Int,
    val svtId: Long?,
    val oldRank: Int?,
    val newRank: Int?,
    val cap: Int?,
    val gained: Long?,
)

/** Only the values needed by automation. Unknown sections stay unknown, never zero. */
data class CapturedBattle(val drops: Map<Long, Long>?, val bond: List<CapturedBond>?) {
    companion object {
        fun parse(body: JsonObject, previousState: AccountState?): CapturedBattle? {
            val success = (body["response"] as? JsonArray)?.firstOrNull().obj()?.get("success").obj() ?: return null
            val dropRows = success["resultDropInfos"] as? JsonArray
            val drops = dropRows?.let { rows ->
                val counts = mutableMapOf<Long, Long>()
                for (element in rows) {
                    val row = element.obj() ?: return@let null
                    val type = row.number("type") ?: return@let null
                    if (type != 2L) continue
                    val id = row.number("objectId") ?: return@let null
                    val num = row.number("num")?.takeIf { it >= 0 } ?: return@let null
                    val total = counts.getOrDefault(id, 0)
                    if (num > Long.MAX_VALUE - total) return@let null
                    counts[id] = total + num
                }
                counts
            }
            val updated = body["cache"].obj()?.get("updated").obj()
            val old = previousState?.tables?.get("userSvtCollection").orEmpty().mapNotNull { it.obj() }
                .associateBy { it.number("svtId") }.toMutableMap()
            success.rows("oldUserSvtCollection").forEach { old[it.number("svtId")] = it }
            val new = updated.rows("userSvtCollection").associateBy { it.number("svtId") }
            val setup = previousState?.tables?.get("battle")?.lastOrNull().obj()
                ?.get("battleInfo").obj()?.get("myDeck").obj()?.rows("svts").orEmpty()
            val userSvts = previousState?.tables?.get("userSvt").orEmpty().mapNotNull { it.obj() } + success.rows("oldUserSvt")
            // KR result svts contains only own servants, in deck order; id is a battle-local id.
            // Setup has initPos, svtId and isFollowerSvt. Never interpret result.id as svtId.
            val deck = success["myDeck"].obj()?.get("svts") as? JsonArray
            val own = deck?.filterNot { element -> element.obj().isSupport() }
            val bond = own?.takeIf { it.size in 1..5 }?.mapIndexed { index, element ->
                val row = element.obj()
                val setupRow = setup.firstOrNull { it.number("id") == row.number("id") &&
                    row.number("userSvtId") != null && it.number("userSvtId") == row.number("userSvtId") && !it.isSupport() }
                val id = row.number("svtId") ?: setupRow.number("svtId") ?: userSvts.firstOrNull {
                    row.number("userSvtId") != null && it.number("id") == row.number("userSvtId")
                }.number("svtId")
                val before = old[id]
                val after = new[id]
                val oldPoints = before.number("friendship")
                val newPoints = after.number("friendship")
                val exceed = (after.number("friendshipExceedCount") ?: before.number("friendshipExceedCount"))
                    ?.takeIf { it in 0..(Int.MAX_VALUE - 10).toLong() }
                CapturedBond(index + 1, id, before.rank(), after.rank(), exceed?.toInt()?.plus(10),
                    if (oldPoints != null && newPoints != null && newPoints >= oldPoints) newPoints - oldPoints else null)
            }
            return CapturedBattle(drops, bond)
        }

        private fun JsonObject?.rank() = number("friendshipRank")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        private fun JsonObject?.isSupport() = (this?.get("isFollowerSvt") as? JsonPrimitive)?.booleanOrNull == true ||
            (number("followerType") ?: 0) != 0L || (number("npcFollowerSvtId") ?: 0) != 0L
    }
}

object CaptureBondStop {
    /** null means a watched slot cannot be decided safely. Slots never shift around missing rows. */
    fun check(battle: CapturedBattle, ignored: Set<Int>): List<Int>? {
        if ((1..5).all { it in ignored }) return emptyList()
        val rows = battle.bond ?: return null
        val watched = rows.filter { it.slot !in ignored }
        val hits = watched.filter { row ->
            val rank = row.newRank ?: return@filter false
            (row.cap != null && rank >= row.cap) || (row.oldRank != null && rank >= 10 && rank > row.oldRank)
        }.map { it.slot }
        if (hits.isNotEmpty()) return hits.sorted()
        if (watched.any { it.svtId == null || it.oldRank == null || it.newRank == null || it.cap == null }) return null
        return emptyList()
    }
}
