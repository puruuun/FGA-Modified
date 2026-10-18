package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IPreferences

/** Pure decisions for the FP auto-sell loop. No screen access here, so every rule is unit-tested. */
object FpSellPolicy {
    enum class Kind { Servant, Exp }

    /** Lock state of one tile. Locked is a positive signal; Unknown means the lock template could not be checked. */
    enum class Lock { Locked, Unlocked, Unknown }

    enum class Category { Servant1, Servant2, Servant3, Servant4, Exp4, Exp5 }

    /**
     * One tile as read from a frame. [excluded] is set when any positive exclusion signal is seen
     * (padlock, locked label, PARTY, unselectable, Grand Servant overlay). Excluded tiles are never selected.
     */
    data class Cell(
        val kind: Kind?,
        val stars: Int?,
        val new: Boolean?,
        val lock: Lock,
        val selected: Boolean?,
        val excluded: Boolean = false,
    ) {
        val category: Category?
            get() = when (kind) {
                Kind.Servant -> when (stars) {
                    1 -> Category.Servant1; 2 -> Category.Servant2; 3 -> Category.Servant3; 4 -> Category.Servant4; else -> null
                }
                Kind.Exp -> when (stars) { 4 -> Category.Exp4; 5 -> Category.Exp5; else -> null }
                null -> null
            }
    }

    data class Options(val enabled: Set<Category>, val newOnly: Boolean, val dryRun: Boolean)

    data class Summary(val counts: Map<Category, Int>, val unknownLock: Int) {
        val total get() = counts.values.sum()
    }

    /**
     * D-2, D-10, D-12. Dry run may select a tile whose lock state is unknown (it never presses Confirm),
     * but a tile with any exclusion signal is never selected, and real selling needs a proven unlocked tile.
     */
    fun select(cell: Cell, options: Options): Boolean {
        if (cell.excluded || cell.lock == Lock.Locked) return false
        if (cell.category == null || cell.category !in options.enabled) return false
        if (cell.selected != false || cell.new == null) return false
        if (options.newOnly && cell.new != true) return false
        return options.dryRun || cell.lock == Lock.Unlocked
    }

    /** The reason [select] says no, or "select". Written out for the logcat line so a skipped tile explains itself. */
    fun decision(cell: Cell, options: Options): String = when {
        select(cell, options) -> "select"
        cell.excluded -> "skip:excluded"
        cell.lock == Lock.Locked -> "skip:locked"
        cell.category == null -> if (cell.kind == null) "skip:kind-unknown" else "skip:stars-unknown"
        cell.category !in options.enabled -> "skip:category-off"
        cell.selected == null -> "skip:selection-unknown"
        cell.selected -> "skip:already-selected"
        cell.new == null -> "skip:new-unknown"
        options.newOnly && cell.new != true -> "skip:not-new"
        else -> "skip:lock-unknown"
    }

    private fun score(value: Double) = (Math.round(value * 100) / 100.0).toString()

    /**
     * One logcat line for one evaluated tile (tag FpSell). [strip] holds the two strongest strip-template star scores,
     * [slots] the per-slot star scores, [newScore] the NEW! tag score.
     */
    fun tileLine(
        page: Int, row: Int, column: Int, cell: Cell?, skip: String?,
        strip: List<Pair<Int, Double>>, slots: List<Double>, newScore: Double, decision: String
    ): String {
        val position = "page=$page r${row + 1}c${column + 1}"
        if (cell == null) return "$position decision=skip:${skip ?: "unread"}"
        val stripText = strip.take(2).joinToString("/") { "${it.first}:${score(it.second)}" }
        val slotText = slots.joinToString(",") { score(it) }
        return "$position kind=${cell.kind ?: "?"} stars=${cell.stars ?: "?"}(strip $stripText slots $slotText) " +
            "new=${cell.new ?: "?"}(${score(newScore)}) lock=${cell.lock} excluded=${cell.excluded} " +
            "selected=${cell.selected ?: "?"} decision=$decision"
    }

    /**
     * D-13: real selling is allowed once the device QA (fpSell suite, 142 fixtures) proved that no locked, unselectable,
     * Grand Servant or PARTY tile is a candidate, even in dry run.
     */
    const val REAL_SELL_VALIDATED = true

    /**
     * Dry run is on by default. Real selling only happens after the owner turns dry run off explicitly,
     * because the live confirm, convert and close flow has not yet run in the game.
     */
    const val DEFAULT_DRY_RUN = true

    /** Real selling needs a validated server and a lock template. Otherwise the run is dry-run only. */
    fun realSellAllowed(server: GameServer, hasLockTemplate: Boolean): Boolean =
        REAL_SELL_VALIDATED && hasLockTemplate && (server is GameServer.Kr || server is GameServer.Jp)

    fun options(prefs: IPreferences, hasLockTemplate: Boolean): Options = Options(
        listOf(prefs.fpSellServant1, prefs.fpSellServant2, prefs.fpSellServant3,
            prefs.fpSellServant4, prefs.fpSellExp4, prefs.fpSellExp5)
            .zip(Category.entries).filter { it.first }.map { it.second }.toSet(),
        prefs.fpSellNewOnly,
        prefs.fpSellDryRun || !realSellAllowed(prefs.gameServer, hasLockTemplate))

    const val STAR_SLOTS = 5
    /** A star slot scoring at least this is a star (KR 2-4 star tiles: 0.58-0.93, JP 1-star: 0.6-0.93). */
    const val STAR_PRESENT = 0.55
    /** The slot after the last star must score at most this (observed 0.02-0.46; 0.5 for the fifth slot of a 4-star tile). */
    const val STAR_ABSENT = 0.40
    const val STAR_ABSENT_FIFTH = 0.50

    /**
     * Servant stars counted slot by slot from the right. Null unless every counted slot is clearly a star and the next
     * one is clearly empty, so a borderline read never becomes a category. Five stars is not a category: null.
     */
    fun starsFromSlots(scores: List<Double>): Int? {
        if (scores.size < STAR_SLOTS) return null
        val count = scores.takeWhile { it >= STAR_PRESENT }.size
        if (count !in 1..4) return null
        val limit = if (count == 4) STAR_ABSENT_FIFTH else STAR_ABSENT
        // The slot after the last star must be clearly empty, and no later slot may look like a star.
        return count.takeIf { scores[count] <= limit && scores.drop(count + 1).all { it < STAR_PRESENT } }
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
