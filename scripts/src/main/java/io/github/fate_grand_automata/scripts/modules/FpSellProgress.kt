package io.github.fate_grand_automata.scripts.modules

import kotlin.math.abs
import kotlin.math.roundToInt

/** D-34/35. All coordinates here are the 1440p script row coordinates, not screenshot pixels. */
class FpSellProgress(private val pitch: Int) {
    companion object {
        const val ROW_TOLERANCE = 12
        const val STATIONARY_LIMIT = 2
        const val IDLE_LIMIT = 5
        fun sameRows(a: List<Int>, b: List<Int>): Boolean =
            a.isNotEmpty() && a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) <= ROW_TOLERANCE }
    }
    data class TileId(val row: Int, val column: Int)
    private data class Tap(val page: Int, val column: Int, val y: Int, val unknownLock: Boolean, var rejected: Boolean = false)
    private val history = mutableMapOf<TileId, Tap>()
    private var previousRows = emptyList<Int>()
    private var firstListRow = 0
    private var lastTapPosition: Pair<Int, Int>? = null
    private var pageTaps = 0
    private var pageStationary = false
    var pages = 0; private set
    var taps = 0; private set
    var rejected = 0; private set
    var selected = 0; private set
    var unknownLock = 0; private set
    var stationary = 0; private set
    var idle = 0; private set

    /** Infer actual row movement from the requested scroll and the new row phase; bounce keeps row identities. */
    fun beginPage(rows: List<Int>, requestedScroll: Int) {
        pages++
        pageTaps = 0
        if (rows.isEmpty()) return
        val same = sameRows(previousRows, rows)
        pageStationary = same
        stationary = if (same) stationary + 1 else 0
        if (previousRows.isNotEmpty() && !same) {
            val phaseShift = previousRows.first() - rows.first()
            firstListRow += ((requestedScroll - phaseShift).toDouble() / pitch).roundToInt().coerceAtLeast(0)
        }
        previousRows = rows.toList()
    }

    fun id(column: Int, y: Int): TileId =
        TileId(firstListRow + ((y - previousRows.first()).toDouble() / pitch).roundToInt(), column)

    /**
     * D-43: a rejection is inferred only when the rows did not move since the previous page and the tile sits at the
     * same screen position as a tap made on that previous page. Across a scroll the identity is only an estimate
     * (the real scroll differs from the requested one), so nothing is inferred there. Locked tiles never count.
     */
    fun seen(column: Int, y: Int, cell: FpSellPolicy.Cell): String? {
        val tap = history[id(column, y)] ?: return null
        if (tap.rejected) return "skip:tap-rejected"
        if (cell.selected == true) return "skip:already-selected"
        if (cell.lock == FpSellPolicy.Lock.Locked || cell.excluded) return null
        if (cell.present && cell.selected == false) {
            val sameSpot = tap.column == column && abs(tap.y - y) <= ROW_TOLERANCE
            if (pageStationary && sameSpot && tap.page == pages - 1) {
                tap.rejected = true
                rejected++
                selected--
                if (tap.unknownLock) unknownLock--
                return "tap-rejected"
            }
            if (tap.page < pages) return null // identity is only an estimate across a scroll: judge the tile on its own
        }
        return "skip:already-tapped"
    }

    fun samePosition(column: Int, y: Int): Boolean = lastTapPosition?.let { (col, top) ->
        col == column && abs(top - y) <= ROW_TOLERANCE
    } == true

    fun tapped(column: Int, y: Int, lock: FpSellPolicy.Lock) {
        val key = id(column, y)
        check(history[key]?.rejected != true && !samePosition(column, y)) { "A tile or screen position must not be tapped twice" }
        history[key] = Tap(pages, column, y, lock == FpSellPolicy.Lock.Unknown)
        lastTapPosition = column to y
        taps++
        pageTaps++
        selected++
        if (lock == FpSellPolicy.Lock.Unknown) unknownLock++
    }

    fun finishPage(): String? {
        idle = if (pageTaps == 0) idle + 1 else 0
        return when {
            stationary >= STATIONARY_LIMIT -> "stationary-rows"
            idle >= IDLE_LIMIT -> "no-new-taps"
            else -> null
        }
    }
    fun summary() = FpSellPolicy.Summary(selected, unknownLock)
}
