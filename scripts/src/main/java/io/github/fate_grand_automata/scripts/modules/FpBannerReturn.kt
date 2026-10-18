package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Pattern
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * Remembers the friend-point banner that was open when the summon started (D-7), and brings it back after a sale.
 * The dot index is only a shortcut (D-15): the banner title image decides, and the search is bounded.
 */
class FpBannerReturn @Inject constructor(api: IFgoAutomataApi, private val loc: FpSellLocations) : IFgoAutomataApi by api {
    data class Dots(val active: Int, val count: Int)

    companion object {
        /** Upper bound for arrow presses while searching for the remembered banner. */
        const val MAX_STEPS = 60

        /** Signed steps: negative is left, positive right; ordinals are zero based. */
        fun steps(from: Dots, to: Dots): Int? = if (from.count == to.count &&
            from.active in 0 until from.count && to.active in 0 until to.count) to.active - from.active else null

        /**
         * D-15: row-major ordinal over one or two rows of dots. [rowCounts] holds the dots per row, top row first.
         * Returns null for any shape that is not one or two non-empty rows.
         */
        fun rowMajor(rowCounts: List<Int>, row: Int, column: Int): Dots? {
            if (rowCounts.isEmpty() || rowCounts.size > 2 || rowCounts.any { it <= 0 }) return null
            if (row !in rowCounts.indices || column !in 0 until rowCounts[row]) return null
            return Dots(rowCounts.take(row).sum() + column, rowCounts.sum())
        }
    }

    private var original: Dots? = null
    private var title: Pattern? = null
    private fun stop(): Nothing = throw ExitException(ExitReason.BannerNotFound)

    fun release() {
        title?.close()
        title = null
        original = null
    }

    fun remember() {
        if (title != null) return
        useSameSnapIn {
            original = dots()
            title = loc.bannerTitle.getPattern()
        }
    }

    fun readDots(): Dots? = useSameSnapIn { dots() }

    private val offImages get() = listOf(Images.FpSellDotOff, Images.FpSellDotOffLight, Images.FpSellDotOffA,
        Images.FpSellDotOffB, Images.FpSellDotOffC, Images.FpSellDotOffD)

    /** Missing dot templates (for example JP) make the index unknown, which is safe: the title search takes over. */
    private fun dots(): Dots? = runCatching { dotsOrNull() }.getOrNull()

    private fun dotsOrNull(): Dots? {
        val onMatches = loc.dots.findAll(images[Images.FpSellDotOn], similarity = 0.85)
        val offMatches = offImages.flatMap { loc.dots.findAll(images[it], similarity = 0.85) }
        val all = (offMatches + onMatches)
            .map { it.region.center.x to it.region.center.y }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .fold(mutableListOf<Pair<Int, Int>>()) { unique, center ->
                if (unique.none { abs(it.first - center.first) < 6 && abs(it.second - center.second) < 6 }) unique.add(center)
                unique
            }
        if (all.size !in 2..60) return null
        // Rows are clusters of equal y, top row first; each row is ordered left to right.
        val rows = mutableListOf<MutableList<Pair<Int, Int>>>()
        for (dot in all) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().second - dot.second) < 8) row.add(dot) else rows.add(mutableListOf(dot))
        }
        // The active dot is the strongest active-template match, and it must clearly beat the runner-up.
        // A light-background inactive dot can also match the inactive template, so inactive scores are not used.
        val ranked = all.map { it to bestNear(onMatches, it) }.filter { it.second >= 0.85 }.sortedByDescending { it.second }
        if (ranked.isEmpty()) return null
        if (ranked.size > 1 && ranked[0].second - ranked[1].second < 0.05) return null
        val activeCenter = ranked[0].first
        val rowIndex = rows.indexOfFirst { it.contains(activeCenter) }
        if (rowIndex < 0) return null
        val column = rows[rowIndex].indexOf(activeCenter)
        return rowMajor(rows.map { it.size }, rowIndex, column)
    }

    private fun bestNear(matches: List<io.github.lib_automata.Match>, center: Pair<Int, Int>): Double =
        matches.filter { abs(it.region.center.x - center.first) < 6 && abs(it.region.center.y - center.second) < 6 }
            .maxOfOrNull { it.score } ?: 0.0

    private fun awaitScreen(test: () -> Boolean) {
        repeat(40) { if (useSameSnapIn(test)) return; 0.5.seconds.wait() }
        stop()
    }

    private fun bannerMatches(savedTitle: Pattern): Boolean =
        loc.bannerTitle.exists(savedTitle, similarity = 0.85) &&
            (findImage(locations.fp.summonCheck, Images.FriendSummon) ||
                findImage(locations.fp.initialSummonCheck, Images.FriendSummon))

    fun restore() {
        val saved = original
        val savedTitle = title ?: stop()
        loc.close.click()
        awaitScreen { loc.shop.exists(images[Images.FpSellShop], similarity = 0.9) }
        (loc.menu.find(images[Images.FpSellMenu], similarity = 0.9) ?: stop()).region.click()
        0.5.seconds.wait()
        (loc.summonMenu.find(images[Images.FpSellSummonMenu], similarity = 0.9) ?: stop()).region.click()
        0.8.seconds.wait()
        if (saved != null) {
            val current = useSameSnapIn { dots() }
            val difference = current?.let { steps(it, saved) }
            if (difference != null && abs(difference) <= MAX_STEPS) repeat(abs(difference)) { arrow(difference > 0) }
        }
        // The title image decides. A wrong or missing dot index only costs more bounded steps.
        repeat(MAX_STEPS) {
            if (useSameSnapIn { bannerMatches(savedTitle) }) return
            arrow(true)
        }
        stop()
    }

    private fun arrow(right: Boolean) {
        (if (right) loc.rightArrow else loc.leftArrow).click()
        0.7.seconds.wait()
    }
}
