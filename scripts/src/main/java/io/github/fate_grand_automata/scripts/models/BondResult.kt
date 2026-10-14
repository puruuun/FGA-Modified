package io.github.fate_grand_automata.scripts.models

sealed interface BondValue {
    data class Gain(val base: Int, val bonus: Int) : BondValue {
        val total: Long get() = base.toLong() + bonus
    }
    data object Max : BondValue
    data object Unknown : BondValue
}

sealed interface BondFrame {
    data class Normal(val values: List<BondValue>) : BondFrame
    data class LevelUp(val reachedLevel: Int?, val resultIndex: Int?) : BondFrame
    data object Other : BondFrame
}

data class BondSlotSummary(val position: Int, val total: Long, val readRuns: Int, val missedRuns: Int, val maxObserved: Boolean)
data class BondSummary(val slots: List<BondSlotSummary>, val unmappedLevelUpRuns: Int)
