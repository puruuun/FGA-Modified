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
 * Selects all visible safe tiles from the sell screen of a servant-slot warning, then leaves the screen.
 * Returns the number of tiles sold. Dry run stops with [ExitReason.SellDryRunDone] before Confirm.
 */
class FpAutoSell @Inject constructor(
    api: IFgoAutomataApi,
    private val reader: FpSellReader,
    private val swipe: Swiper
) : IFgoAutomataApi by api {
    private val loc get() = reader.loc
    // Which step a stop belongs to, the dry-run state and the tiles selected so far, for the exit message (D-21).
    private var step = SellStep.Entry
    private var dryRun = true
    private var selectedSoFar = 0
    private var unknownLockSoFar = 0
    private fun log(message: String) = messages.log(ScriptLog.FpSell(message))

    /** Every stop names its step and the reason in logcat (tag FpSell) before the exit message is shown. */
    private fun stop(reason: String): Nothing {
        log("stop step=$step reason=$reason selected=$selectedSoFar unknownLock=$unknownLockSoFar dryRun=$dryRun")
        throw ExitException(AutoFriendGacha.sellStop(step, dryRun, selectedSoFar, unknownLockSoFar))
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
        log("start dryRun=$dryRun")
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
        var anchorRetry = false
        unknownLockSoFar = 0
        var unchanged = 0
        var ended = false
        // Bounded even when screenshots or a scroll gesture stop making progress.
        for (page in 0 until 500) {
            if (!useSameSnapIn { reader.onSellScreen() }) stop("sell screen lost on page $page")
            val tiles = reader.readPage()
            // Row numbers are the distinct grid rows of this page, counted from the top.
            val rowYs = tiles.map { it.region.y }.sorted().fold(mutableListOf<Int>()) { rows, y ->
                if (rows.none { kotlin.math.abs(it - y) < 24 }) rows.add(y)
                rows
            }
            fun row(tile: FpSellReader.PageTile) = rowYs.indexOfFirst { kotlin.math.abs(it - tile.region.y) < 24 }
            val evaluatedRows = FpSellPolicy.evaluatedRows(tiles)
            val plannedDistance = FpSellPolicy.swipeDistance(evaluatedRows)
            if (plannedDistance == null) {
                if (anchorRetry) stop("grid rows unavailable after reanchor and overlap scroll on page $page")
                anchorRetry = true
                log("page=$page rows=$evaluatedRows swipe=284 reanchor=true")
                swipe.scroll(loc.swipeStart, loc.swipeEnd(284))
                0.7.seconds.wait()
                continue
            }
            val distance = plannedDistance
            anchorRetry = false
            log("page=$page tiles=${tiles.size} evaluated=${tiles.count { it.skip == null }} rows=$evaluatedRows swipe=$distance")
            for (tile in tiles) {
                val cell = if (tile.skip == null) reader.readCard(tile.region)
                    else FpSellPolicy.Cell(false, FpSellPolicy.Lock.Unknown, null)
                val decision = if (tile.skip != null) "skip:${tile.skip}" else FpSellPolicy.decision(cell, options)
                log(FpSellPolicy.tileLine(page, row(tile), tile.column, cell, decision))
                if (decision != "select") continue
                if (selectedSoFar >= 999) { ended = true; break }
                tile.region.click()
                selectedSoFar++
                if (cell.lock == FpSellPolicy.Lock.Unknown) unknownLockSoFar++
            }
            if (ended) break
            loc.grid.getPattern().use { before ->
                swipe.scroll(loc.swipeStart, loc.swipeEnd(distance))
                0.7.seconds.wait()
                val same = loc.grid.exists(before, similarity = 0.995)
                unchanged = if (same) unchanged + 1 else 0
                log("page=$page swiped distance=$distance rows=$evaluatedRows unchanged=$unchanged selected=$selectedSoFar")
            }
            if (unchanged >= 2) { ended = true; break }
        }
        if (!ended) stop("page limit reached without the list ending")
        log("end of list selected=$selectedSoFar")
        val summary = FpSellPolicy.Summary(selectedSoFar, unknownLockSoFar)
        messages.log(ScriptLog.FpSellSelection(summary))
        // This guard precedes EVERY path to the confirmation button. Dry run and not-yet-validated servers stop here.
        if (options.dryRun) log("stop step=$step reason=dry-run-complete selected=${summary.total} unknownLock=$unknownLockSoFar dryRun=true")
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
