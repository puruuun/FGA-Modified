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
    private data class Tap(val page: Int, val unknownLock: Boolean, var rejected: Boolean = false)
    private val history = mutableMapOf<TileId, Tap>()
    private var previousRows = emptyList<Int>()
    private var firstListRow = 0
    private var lastTapPosition: Pair<Int, Int>? = null
    private var pageTaps = 0
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
        stationary = if (same) stationary + 1 else 0
        if (previousRows.isNotEmpty() && !same) {
            val phaseShift = previousRows.first() - rows.first()
            firstListRow += ((requestedScroll - phaseShift).toDouble() / pitch).roundToInt().coerceAtLeast(0)
        }
        previousRows = rows.toList()
    }

    fun id(column: Int, y: Int): TileId =
        TileId(firstListRow + ((y - previousRows.first()).toDouble() / pitch).roundToInt(), column)

    /** No post-tap read: rejection is decided only by a later overlapping page. */
    fun seen(column: Int, y: Int, cell: FpSellPolicy.Cell): String? {
        val tap = history[id(column, y)] ?: return null
        if (tap.rejected) return "skip:tap-rejected"
        if (pages > tap.page && cell.present && cell.selected == false) {
            tap.rejected = true
            rejected++
            selected--
            if (tap.unknownLock) unknownLock--
            return "tap-rejected"
        }
        return if (cell.selected == true) "skip:already-selected" else "skip:already-tapped"
    }

    fun samePosition(column: Int, y: Int): Boolean = lastTapPosition?.let { (col, top) ->
        col == column && abs(top - y) <= ROW_TOLERANCE
    } == true

    fun tapped(column: Int, y: Int, lock: FpSellPolicy.Lock) {
        val key = id(column, y)
        check(key !in history && !samePosition(column, y)) { "A tile or screen position must not be tapped twice" }
        history[key] = Tap(pages, lock == FpSellPolicy.Lock.Unknown)
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
