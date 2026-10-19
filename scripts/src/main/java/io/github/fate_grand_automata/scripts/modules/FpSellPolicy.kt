package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IPreferences

/** D-29: the owner configures the sell filter. FGA checks only presence and safety signals. */
object FpSellPolicy {
    enum class Lock { Locked, Unlocked, Unknown }
    data class Cell(val present: Boolean, val lock: Lock, val selected: Boolean?, val excluded: Boolean = false)
    data class Options(val dryRun: Boolean, val allowed: Boolean = true)
    data class Summary(val total: Int, val unknownLock: Int)

    fun select(cell: Cell, options: Options): Boolean =
        options.allowed && cell.present && !cell.excluded && cell.lock != Lock.Locked && cell.selected == false &&
            (options.dryRun || cell.lock == Lock.Unlocked)

    fun decision(cell: Cell, options: Options): String = when {
        !cell.present -> "skip:empty"
        cell.lock == Lock.Locked -> "skip:locked"
        cell.excluded -> "skip:excluded"
        cell.selected == true -> "skip:already-selected"
        cell.selected == null -> "skip:selection-unknown"
        !select(cell, options) -> "skip:lock-unknown"
        else -> "select"
    }

    fun tileLine(page: Int, row: Int, column: Int, cell: Cell, decision: String): String =
        "page=$page r${row + 1}c${column + 1} present=${cell.present} lock=${cell.lock} " +
            "excluded=${cell.excluded} selected=${cell.selected ?: "?"} decision=$decision"

    const val REAL_SELL_VALIDATED = true
    const val DEFAULT_DRY_RUN = true
    fun realSellAllowed(server: GameServer, hasLockTemplate: Boolean): Boolean =
        REAL_SELL_VALIDATED && hasLockTemplate && (server is GameServer.Kr || server is GameServer.Jp)
    fun options(prefs: IPreferences, hasLockTemplate: Boolean): Options {
        val realAllowed = realSellAllowed(prefs.gameServer, hasLockTemplate)
        // Stable ignores the stored switch and cannot fall back to dry run (D-47).
        return if (prefs.fpSellStableBuild) Options(dryRun = false, allowed = realAllowed)
            else Options(prefs.fpSellDryRun || !realAllowed)
    }

    /** Distinct fully visible grid rows, tolerating the few pixels of label antialiasing within one row (1440p). */
    fun evaluatedRows(tiles: List<FpSellReader.PageTile>): List<Int> = tiles.filter { it.skip == null }
        .map { it.region.y }.sorted().fold(mutableListOf()) { rows, y ->
            if (rows.none { kotlin.math.abs(it - y) < 24 }) rows.add(y)
            rows
        }

    /** D-23: retain the last evaluated row as overlap. With just one row, move at most one pitch. */
    fun swipeDistance(rows: List<Int>): Int? {
        if (rows.isEmpty()) return null
        val pitch = rows.zipWithNext { a, b -> b - a }.minOrNull()?.coerceAtMost(284) ?: 284
        return minOf(560, if (rows.size == 1) pitch else rows.last() - rows.first(), pitch * maxOf(1, rows.size - 1))
            .takeIf { it > 0 }
    }

    /** D-17: the size toggle cycles 5 -> 6 -> 7 -> 5, so the number of presses needed to reach 7 columns. */
    fun sizeTogglesToSeven(columns: Int): Int? = when (columns) {
        7 -> 0
        6 -> 1
        5 -> 2
        else -> null
    }

    /** D-11: the warning dialog is a servant-slot warning only when its first line contains the servant word. */
    fun isServantFullWarning(servantWordFound: Boolean): Boolean = servantWordFound
}
