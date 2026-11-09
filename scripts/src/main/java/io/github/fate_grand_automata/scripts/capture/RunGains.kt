package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable

/** Compact persisted run summary. Starting holdings are cleared by finish, before storage. */
@Serializable
data class RunGains(
    val battles: List<BattleGains> = emptyList(),
    val items: List<GainedItem> = emptyList(),
    val qp: Long? = null,
    val masterExp: Long? = null,
    val bond: Map<Long, Long> = emptyMap(),
    val fruitUsed: Map<Long, Long?> = emptyMap(),
    /** Every item spent in the run (start - end + gained), positive values only. Empty when unknown. */
    val itemsUsed: Map<Long, Long> = emptyMap(),
    val missingBattles: Int = 0,
    /** Set only on the stored summary, whose per-battle rows live in a separate file (issue #280). */
    val storedBattleCount: Int = 0,
    /** Quest id of the newest battle, kept in the stored summary so the dashboard needs no battle rows. */
    val lastQuestId: Long? = null,
    private val startHoldings: Map<Long, Long>? = null,
) {
    val capturedBattles: Int get() = if (battles.isEmpty()) storedBattleCount else battles.size

    /** The summary kept in the log index: every total, but none of the per-battle rows. */
    fun summarized(): RunGains = copy(
        battles = emptyList(), storedBattleCount = capturedBattles,
        lastQuestId = battles.maxByOrNull { it.time }?.questId ?: lastQuestId,
    )

    fun add(battle: BattleGains): RunGains = copy(battles = battles + battle)

    fun finish(endState: AccountState?): RunGains {
        val end = endState.itemHoldings()
        val totals = sumItems(battles.flatMap { it.items }).map { item ->
            item.copy(holdings = if (item.type == 2) end?.get(item.objectId) ?: end?.let { 0L } else null)
        }
        return copy(
            items = totals,
            qp = battles.mapNotNull { it.qp }.takeIf { it.isNotEmpty() }?.sum(),
            masterExp = battles.mapNotNull { it.masterExp }.takeIf { it.isNotEmpty() }?.sum(),
            bond = battles.flatMap { it.bond.entries }.groupBy { it.key }.mapValues { (_, rows) -> rows.sumOf { it.value } },
            fruitUsed = FRUIT_IDS.associateWith { id ->
                if (startHoldings != null && end != null)
                    (startHoldings[id] ?: 0L) - (end[id] ?: 0L) +
                        totals.filter { it.type == 2 && it.objectId == id }.sumOf { it.gained }
                else null
            },
            itemsUsed = if (startHoldings != null && end != null) {
                val gained = totals.filter { it.type == 2 }.groupBy { it.objectId }.mapValues { (_, rows) -> rows.sumOf { it.gained } }
                (startHoldings.keys + end.keys).associateWith { id ->
                    (startHoldings[id] ?: 0L) - (end[id] ?: 0L) + (gained[id] ?: 0L)
                }.filterValues { it > 0L }
            } else emptyMap(),
            startHoldings = null,
        )
    }

    fun withRunCount(timesRan: Int) = copy(missingBattles = (timesRan - capturedBattles).coerceAtLeast(0))

    companion object {
        val FRUIT_IDS = listOf(100L, 101L, 102L, 104L)
        fun start(state: AccountState?) = RunGains(startHoldings = state.itemHoldings())
    }
}
