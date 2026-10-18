package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.SellStep
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Sells the configured tiles from the sell screen of a servant-slot warning, then leaves the screen.
 * Returns the number of tiles sold. Dry run stops with [ExitReason.SellDryRunDone] before Confirm.
 */
class FpAutoSell @Inject constructor(
    api: IFgoAutomataApi,
    private val reader: FpSellReader,
    private val swipe: Swiper
) : IFgoAutomataApi by api {
    companion object {
        const val SELECTION_POLLS = 10
        val SELECTION_POLL = 0.15.seconds
    }

    private val loc get() = reader.loc
    // Which step a stop belongs to, the dry-run state and the tiles selected so far, for the exit message (D-21).
    private var step = SellStep.Entry
    private var dryRun = true
    private var selectedSoFar = 0
    private fun log(message: String) = messages.log(ScriptLog.FpSell(message))

    /** Every stop names its step and the reason in logcat (tag FpSell) before the exit message is shown. */
    private fun stop(reason: String): Nothing {
        log("stop step=$step reason=$reason selected=$selectedSoFar dryRun=$dryRun")
        throw ExitException(AutoFriendGacha.sellStop(step, dryRun, selectedSoFar))
    }

    /** D-20: poll fresh frames for about 1.5 s. The tile is tapped once by the caller and never again. */
    private fun awaitSelected(card: Region): Boolean {
        repeat(SELECTION_POLLS) {
            SELECTION_POLL.wait()
            if (reader.selection(card) == true) return true
        }
        return false
    }

    private fun awaitScreen(what: String, test: () -> Boolean) {
        repeat(40) { if (useSameSnapIn(test)) return; 0.5.seconds.wait() }
        stop("screen not reached: $what")
    }

    private fun click(region: Region, image: Images) {
        val match = region.find(images[image], similarity = 0.9) ?: stop("button not found: $image")
        match.region.click()
        0.5.seconds.wait()
    }

    fun run(): Int {
        val options = FpSellPolicy.options(prefs, reader.hasLockTemplate)
        step = SellStep.Entry
        dryRun = options.dryRun
        selectedSoFar = 0
        log("start dryRun=${options.dryRun} enabled=${options.enabled.joinToString(",")} newOnly=${options.newOnly}")
        click(loc.fullSell, Images.FpSellSell)
        awaitScreen("sell heading") { reader.onSellHeading() }
        // The sell screen may open on the Craft Essence tab. Only the servant tab is read (D-17).
        if (!useSameSnapIn { reader.onServantTab() }) {
            loc.servantTabClick.click()
            0.5.seconds.wait()
        }
        awaitScreen("servant tab") { reader.onSellScreen() }
        step = SellStep.Columns
        // The size toggle cycles 5 -> 6 -> 7 -> 5, so reaching 7 columns takes at most two presses (D-17).
        var presses = 0
        while (!useSameSnapIn { reader.isSevenColumns() }) {
            if (presses++ == 2) stop("seven columns not reached after 2 presses")
            loc.sizeSwitch.click()
            0.5.seconds.wait()
            awaitScreen("sell screen after size switch") { reader.onSellScreen() }
        }
        step = SellStep.Selection
        val counts = mutableMapOf<FpSellPolicy.Category, Int>()
        var unknown = 0
        var unchanged = 0
        var ended = false
        // Bounded even when screenshots or a scroll gesture stop making progress.
        for (page in 0 until 500) {
            if (!useSameSnapIn { reader.onSellScreen() && reader.isSevenColumns() }) stop("sell screen or seven columns lost on page $page")
            val tiles = reader.readPage()
            // Row numbers are the distinct label rows of this page, counted from the top.
            val rowYs = tiles.map { it.region.y }.sorted().fold(mutableListOf<Int>()) { rows, y ->
                if (rows.none { kotlin.math.abs(it - y) < 24 }) rows.add(y)
                rows
            }
            fun row(tile: FpSellReader.PageTile) = rowYs.indexOfFirst { kotlin.math.abs(it - tile.region.y) < 24 }
            log("page=$page tiles=${tiles.size} evaluated=${tiles.count { it.skip == null }}")
            for (tile in tiles) {
                if (tile.skip != null) {
                    log(FpSellPolicy.tileLine(page, row(tile), tile.column, null, tile.skip, emptyList(), emptyList(), 0.0, "skip:${tile.skip}"))
                    continue
                }
                if (counts.values.sum() >= 999) { ended = true; break }
                val cell = reader.confirmed(tile.region)
                val decision = FpSellPolicy.decision(cell, options)
                log(FpSellPolicy.tileLine(page, row(tile), tile.column, cell, null, tile.stars, tile.slots, tile.newScore, decision))
                if (decision != "select") continue
                tile.region.click()
                // Count only a visibly acknowledged selection; never retry a possibly toggled tap (D-20).
                if (!awaitSelected(tile.region)) stop("tapped tile page=$page r${row(tile) + 1}c${tile.column + 1} did not read as selected")
                val category = cell.category ?: stop("selected tile has no category")
                counts[category] = (counts[category] ?: 0) + 1
                selectedSoFar = counts.values.sum()
                if (cell.lock == FpSellPolicy.Lock.Unknown) unknown++
            }
            if (ended) break
            loc.grid.getPattern().use { before ->
                swipe(loc.swipeStart, loc.swipeEnd)
                0.7.seconds.wait()
                val same = loc.grid.exists(before, similarity = 0.995)
                unchanged = if (same) unchanged + 1 else 0
                log("page=$page swiped unchanged=$unchanged selected=$selectedSoFar")
            }
            if (unchanged >= 2) { ended = true; break }
        }
        if (!ended) stop("page limit reached without the list ending")
        log("end of list selected=$selectedSoFar")
        val summary = FpSellPolicy.Summary(counts.toMap(), unknown)
        messages.log(ScriptLog.FpSellSelection(summary))
        // This guard precedes EVERY path to the confirmation button. Dry run and not-yet-validated servers stop here.
        if (options.dryRun) log("dry run done selected=${summary.total}")
        if (options.dryRun) throw ExitException(ExitReason.SellDryRunDone(summary))
        if (summary.total == 0) return 0
        step = SellStep.Flow
        click(loc.confirm, Images.FpSellConfirm)
        awaitScreen("convert heading") { loc.convertHeading.exists(images[Images.FpSellConvertHeading], similarity = 0.9) }
        click(loc.convert, Images.FpSellConvert)
        awaitScreen("done") { loc.done.exists(images[Images.FpSellDone], similarity = 0.9) }
        click(loc.doneClose, Images.FpSellClose)
        awaitScreen("sell heading after close") { reader.onSellHeading() }
        return summary.total
    }
}
