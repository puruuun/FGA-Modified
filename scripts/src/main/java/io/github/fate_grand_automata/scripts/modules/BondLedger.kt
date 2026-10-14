package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.models.*

/** One entry per completed quest and original party position, independent of screen retries. */
class BondLedger {
    private data class Cell(var gain: Long? = null, var max: Boolean = false)
    private val quests = linkedMapOf<Int, MutableMap<Int, Cell>>()
    private val unmapped = mutableSetOf<Int>()

    fun ensure(run: Int, party: BondParty) {
        quests.getOrPut(run) { party.ownedPositions.associateWith { Cell() }.toMutableMap() }
    }

    fun normal(run: Int, party: BondParty, values: List<BondValue>) {
        ensure(run, party)
        // Unconfirmed layouts must not shift a MAX onto a different party position.
        if (values.size < party.ownedPositions.size || values.drop(party.ownedPositions.size).any { it != BondValue.Unknown }) return
        party.ownedPositions.zip(values).forEach { (position, value) ->
            val cell = quests.getValue(run).getValue(position)
            when (value) {
                is BondValue.Gain -> if (cell.gain == null) cell.gain = value.total
                BondValue.Max -> { cell.max = true; if (cell.gain == null) cell.gain = 0 }
                BondValue.Unknown -> Unit
            }
        }
    }

    fun levelUp(run: Int, party: BondParty, reached: Int?, index: Int?) {
        ensure(run, party)
        val position = index?.let { party.ownedPositions.getOrNull(it) }
        if (position == null || reached == null) {
            // User policy: skip the obscured popup and recheck normal MAX next quest.
            unmapped += run
            quests.getValue(run).values.filter { it.max && it.gain == 0L }.forEach { it.gain = null }
            return
        }
        if (reached >= 10) {
            val cell = quests.getValue(run).getValue(position)
            // A newly capped quest's hidden gain is unknown, never an invented zero.
            if (cell.gain == 0L) cell.gain = null
            cell.max = true
        }
    }

    fun maxPositions(run: Int): Set<Int> = quests[run].orEmpty().filterValues { it.max }.keys

    fun summary(): BondSummary {
        val positions = quests.values.flatMap { it.keys }.distinct().sorted()
        return BondSummary(positions.map { position ->
            val cells = quests.values.mapNotNull { it[position] }
            BondSlotSummary(position, cells.sumOf { it.gain ?: 0 }, cells.count { it.gain != null },
                cells.count { it.gain == null }, cells.any { it.max })
        }, unmapped.size)
    }
}
