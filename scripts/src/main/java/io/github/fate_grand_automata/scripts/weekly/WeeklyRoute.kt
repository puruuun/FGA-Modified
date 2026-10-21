package io.github.fate_grand_automata.scripts.weekly

import kotlinx.serialization.Serializable

/** Server-local Atlas data. Enemy entries represent one enemy-deck entity, never player/support decks. */
@Serializable
data class RouteEnemy(val id: Int, val classId: Int, val traits: Set<Int>, val summoned: Boolean = false)
@Serializable
data class RouteQuest(
    val id: Int, val warId: Int, val warOrder: Int, val chapter: String, val spot: String,
    val name: String, val ap: Int, val type: String, val afterClear: String,
    val traits: Set<Int> = emptySet(), val enemies: List<RouteEnemy> = emptyList(),
    val enemyDataAvailable: Boolean = true, val repeatPhase: Int = 1,
    /** Where the quest sits on the chapter map (Atlas spot id, map-pixel coordinates and the map it belongs to). */
    val spotId: Int = 0, val spotX: Double = 0.0, val spotY: Double = 0.0, val mapId: Int = 0,
    /** All Atlas free quests at the spot, before enabled/route filters. Zero means unknown. */
    val spotFreeQuests: Int = 0
) {
    fun repeatable(excludedWars: Set<Int>): Boolean = warId !in excludedWars && ap > 0 &&
        type == "free" && afterClear == "repeatLast"
}

/** One map image of a war. [width] and [height] are the display size in map pixels; the PNG itself is a power-of-two texture. */
@Serializable
data class WarMap(val id: Int, val image: String, val width: Int, val height: Int)

/** Chapter data the navigation needs: the list button art and the map images. */
@Serializable
data class WarInfo(val id: Int, val name: String, val banner: String, val maps: List<WarMap>, val spots: Map<Int, String> = emptyMap())

enum class MissionKind { EnemyTraitAny, EnemyTraitAll, EnemyClass, ServantClass, EnemyId, QuestClear, QuestTrait, Unsupported }
data class RouteMission(
    val id: Int, val name: String, val target: Int, val kind: MissionKind,
    val ids: Set<Int> = emptySet(), val excludeSummoned: Boolean = true,
    val questTraits: Set<Int> = emptySet(),
    /** The "clear every mission" mission: it completes by itself once the others are done, so it is neither routed nor manual. */
    val meta: Boolean = false
) {
    fun contribution(q: RouteQuest): Int {
        if (!q.traits.containsAll(questTraits)) return 0
        return when (kind) {
            MissionKind.QuestClear -> if (ids.isEmpty() || 0 in ids || q.id in ids) 1 else 0
            MissionKind.QuestTrait -> if (q.traits.any { it in ids }) 1 else 0
            MissionKind.Unsupported -> 0
            else -> if (!q.enemyDataAvailable) 0 else q.enemies.count { e ->
                (!excludeSummoned || !e.summoned) && when (kind) {
                    MissionKind.EnemyTraitAny -> e.traits.any { it in ids }
                    MissionKind.EnemyTraitAll -> e.traits.containsAll(ids)
                    MissionKind.EnemyClass -> e.classId in ids
                    MissionKind.ServantClass -> e.classId in ids && 1000 in e.traits
                    MissionKind.EnemyId -> e.id in ids
                    else -> false
                }
            }
        }
    }
}

data class RouteItem(val quest: RouteQuest, val count: Int)
data class RoutePlan(val items: List<RouteItem>, val manual: List<RouteMission>) {
    val totalAp: Long get() = items.sumOf { it.quest.ap.toLong() * it.count }
}

/**
 * Exact integer covering: minimize sum(AP * x) subject to sum(contribution * x) >= target per mission, x >= 0 integer.
 *
 * Implemented from scratch as best-first branch and bound. Each node's LP relaxation (with the branching bounds on x)
 * is solved through its dual, max b.y - u.w s.t. A^T y - w <= c, which has a feasible origin because AP > 0, so a plain
 * Bland-rule tableau simplex is enough. The primal solution is read from the slack reduced costs. Every node also
 * contributes the ceiling of its LP solution as a feasible incumbent. Because AP is integral, a node is pruned when
 * ceil(bound) cannot beat the incumbent. Hitting [maxNodes] fails explicitly rather than reporting an unproven route.
 *
 * Quests with an identical or dominated (cheaper-or-equal and covering at least as much) contribution vector are
 * dropped first. Input order never changes the answer: quests are sorted by main-record order, war ID, quest ID.
 */
object WeeklyRouteSolver {
    val order: Comparator<RouteQuest> = compareBy<RouteQuest>({ it.warOrder }, { it.warId }, { it.id })
    private const val EPS = 1e-7

    /** Lostbelt No.7 Mictlan: its maps are switched with layer buttons, so it is never a candidate (design 0009 D-9). */
    const val MICTLAN_WAR = 311

    fun solve(missions: List<RouteMission>, quests: List<RouteQuest>, excludedWars: Set<Int>, maxNodes: Int = 200_000): RoutePlan {
        val candidates = quests.filter { it.repeatable(excludedWars) }.sortedWith(order)
        val (supported, manual) = missions.filter { it.target > 0 && !it.meta }.partition { m ->
            m.kind != MissionKind.Unsupported && candidates.any { m.contribution(it) > 0 }
        }
        if (supported.isEmpty()) return RoutePlan(emptyList(), manual)
        require(supported.all { it.target <= 1000 }) { "mission target exceeds solver safety limit" }
        val target = supported.map { it.target }
        val all = candidates.map { q -> q to supported.map { minOf(it.target, it.contribution(q)) } }
            .filter { (_, v) -> v.any { it > 0 } }
        val cols = all.filterIndexed { i, (q, v) ->
            all.indices.none { j ->
                j != i && all[j].first.ap <= q.ap && all[j].second.indices.all { all[j].second[it] >= v[it] } &&
                    (all[j].first.ap < q.ap || all[j].second != v || j < i)
            }
        }
        val counts = branchAndBound(target, cols.map { it.first.ap }, cols.map { it.second }, maxNodes)
        return RoutePlan(cols.indices.filter { counts[it] > 0 }.map { RouteItem(cols[it].first, counts[it]) }, manual)
    }

    private class Lp(val value: Double, val x: DoubleArray)

    private fun branchAndBound(target: List<Int>, cost: List<Int>, vec: List<List<Int>>, maxNodes: Int): IntArray {
        val n = cost.size
        val m = target.size
        val noUpper = Int.MAX_VALUE

        fun covered(x: IntArray) = (0 until m).all { k -> (0 until n).sumOf { x[it].toLong() * vec[it][k] } >= target[k] }
        fun priceOf(x: IntArray) = (0 until n).sumOf { x[it].toLong() * cost[it] }

        fun lp(lower: IntArray, upper: IntArray): Lp? {
            val need = DoubleArray(m) { k -> maxOf(0.0, target[k] - (0 until n).sumOf { lower[it].toLong() * vec[it][k] }.toDouble()) }
            val rows = (0 until n).filter { upper[it] == noUpper || upper[it] > lower[it] }
            val ys = (0 until m).filter { need[it] > 0 }
            val x = DoubleArray(n) { lower[it].toDouble() }
            val offset = (0 until n).sumOf { lower[it].toLong() * cost[it] }.toDouble()
            if (ys.isEmpty()) return Lp(offset, x)
            val bounded = rows.filter { upper[it] != noUpper }
            val yBase = 0
            val wBase = ys.size
            val sBase = wBase + bounded.size
            val width = sBase + rows.size + 1
            val t = Array(rows.size + 1) { DoubleArray(width) }
            for ((r, j) in rows.withIndex()) {
                for ((a, k) in ys.withIndex()) t[r][yBase + a] = vec[j][k].toDouble()
                val b = bounded.indexOf(j)
                if (b >= 0) t[r][wBase + b] = -1.0
                t[r][sBase + r] = 1.0
                t[r][width - 1] = cost[j].toDouble()
            }
            val z = rows.size
            for ((a, k) in ys.withIndex()) t[z][yBase + a] = -need[k]
            for ((b, j) in bounded.withIndex()) t[z][wBase + b] = (upper[j] - lower[j]).toDouble()
            val basis = IntArray(rows.size) { sBase + it }
            var guard = 0
            while (true) {
                check(++guard < 100_000) { "simplex did not converge" }
                val enter = (0 until width - 1).firstOrNull { t[z][it] < -EPS } ?: break
                var leave = -1
                var best = Double.MAX_VALUE
                for (r in rows.indices) {
                    if (t[r][enter] > EPS) {
                        val ratio = t[r][width - 1] / t[r][enter]
                        if (ratio < best - EPS || (ratio < best + EPS && leave >= 0 && basis[r] < basis[leave])) { best = ratio; leave = r }
                    }
                }
                if (leave < 0) return null // dual unbounded: demand cannot be met under these bounds
                val pivot = t[leave][enter]
                for (c in 0 until width) t[leave][c] /= pivot
                for (r in 0..rows.size) if (r != leave) {
                    val f = t[r][enter]
                    if (f != 0.0) for (c in 0 until width) t[r][c] -= f * t[leave][c]
                }
                basis[leave] = enter
            }
            for ((r, j) in rows.withIndex()) x[j] = lower[j] + maxOf(0.0, t[z][sBase + r])
            return Lp(offset + t[z][width - 1], x)
        }

        var incumbent: IntArray? = null
        var incumbentCost = Long.MAX_VALUE
        fun offer(x: DoubleArray) {
            val r = IntArray(n) { Math.ceil(x[it] - EPS).toInt() }
            if (covered(r)) {
                val p = priceOf(r)
                if (p < incumbentCost) { incumbentCost = p; incumbent = r }
            }
        }

        class Node(val lower: IntArray, val upper: IntArray, val lp: Lp, val seq: Int) {
            val bound: Long = Math.ceil(lp.value - EPS).toLong()
        }
        val queue = java.util.PriorityQueue<Node>(compareBy<Node>({ it.bound }, { it.seq }))
        var seq = 0
        var nodes = 0
        fun push(lower: IntArray, upper: IntArray) {
            check(++nodes <= maxNodes) { "weekly solver node limit reached; no proven route" }
            val relaxed = lp(lower, upper) ?: return
            offer(relaxed.x)
            queue.add(Node(lower, upper, relaxed, seq++))
        }
        push(IntArray(n), IntArray(n) { noUpper })
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            if (node.bound >= incumbentCost) break
            val x = node.lp.x
            val branch = (0 until n).filter { Math.abs(x[it] - Math.rint(x[it])) > EPS }
                .maxByOrNull { 0.5 - Math.abs(x[it] - Math.floor(x[it]) - 0.5) } ?: continue
            val down = Math.floor(x[branch]).toInt()
            val upperDown = node.upper.copyOf().also { it[branch] = down }
            val lowerUp = node.lower.copyOf().also { it[branch] = down + 1 }
            push(node.lower, upperDown)
            push(lowerUp, node.upper)
        }
        return checkNotNull(incumbent) { "infeasible weekly route" }
    }
}
