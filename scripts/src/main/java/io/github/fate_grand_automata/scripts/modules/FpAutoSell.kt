package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.SellStep
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import javax.inject.Inject

/** A dry run stops at Confirm; only real selling can reach Convert. */
class FpAutoSell @Inject constructor(
    api: IFgoAutomataApi,
    private val reader: FpSellReader,
    private val swipe: Swiper
) : IFgoAutomataApi by api {
    data class Result(val summary: FpSellPolicy.Summary, val dryRun: Boolean)
    private val loc get() = reader.loc
    private var step = SellStep.Entry
    private var dryRun = true
    private var progress: FpSellProgress? = null
    private val buttons = FpSellButtons(api)
    private var summaryLogged = false
    private val selectedSoFar get() = progress?.selected ?: 0
    private val unknownLockSoFar get() = progress?.unknownLock ?: 0
    private fun log(message: String) = messages.log(ScriptLog.FpSell(message))

    fun logFinalSummary() {
        progress?.takeUnless { summaryLogged }?.let {
            summaryLogged = true
            log("final-summary pages=${it.pages} taps=${it.taps} rejected=${it.rejected} " +
                "selected=${it.selected} unknownLock=${it.unknownLock} dryRun=$dryRun")
        }
    }

    private fun stop(reason: String): Nothing {
        log("stop step=$step reason=$reason selected=$selectedSoFar unknownLock=$unknownLockSoFar dryRun=$dryRun")
        throw ExitException(AutoFriendGacha.sellStop(step, dryRun, selectedSoFar, unknownLockSoFar))
    }
    private fun awaitScreen(what: String, test: () -> Boolean) {
        repeat(FpSellTiming.SCREEN_POLLS) {
            if (useSameSnapIn(test)) return
            FpSellTiming.SCREEN_POLL.wait()
        }
        stop("screen not reached: $what")
    }
    private fun click(region: Region, image: Images) {
        buttons.click(region, image) { stop("button not found after 2s: $image") }
        FpSellTiming.AFTER_ACTION.wait()
    }

    fun run(): Result {
        val options = FpSellPolicy.options(prefs, reader.hasLockTemplate)
        step = SellStep.Entry
        dryRun = options.dryRun
        progress = FpSellProgress(loc.rowPitch)
        if (!options.allowed) stop("real sale unavailable for this server or missing lock template")
        return selectAndFinish(options)
    }

    private fun selectAndFinish(options: FpSellPolicy.Options): Result {
        val tracker = checkNotNull(progress)
        log("start dryRun=$dryRun")
        click(loc.fullSell, Images.FpSellSell)
        FpSellTiming.SELL_SCREEN_LOAD.wait()
        awaitScreen("card grid") { reader.gridReadable() }
        // The sell screen may open on the Craft Essence tab. Only the servant tab is read (D-17).
        if (!useSameSnapIn { reader.onServantTab() }) {
            FpSellTiming.BUTTON_SETTLE.wait()
            loc.servantTabClick.click()
            FpSellTiming.AFTER_ACTION.wait()
            if (!useSameSnapIn { reader.onServantTab() }) stop("servant tab not reached")
        }
        step = SellStep.Columns
        // The size toggle cycles 5 -> 6 -> 7 -> 5, so reaching 7 columns takes at most two presses (D-17).
        var presses = 0
        while (!useSameSnapIn { reader.isSevenColumns() }) {
            if (presses++ == 2) stop("seven columns not reached after 2 presses")
            FpSellTiming.BUTTON_SETTLE.wait()
            loc.sizeSwitch.click()
            FpSellTiming.AFTER_ACTION.wait()
            awaitScreen("card grid after size switch") { reader.gridReadable() }
        }
        step = SellStep.Selection
        var anchorRetry = false
        var ended = false
        var requestedScroll = 0
        for (page in 0 until 500) {
            val tiles = reader.readPage()
            val rowYs = tiles.map { it.region.y }.distinct().sorted()
            fun row(tile: FpSellReader.PageTile) = rowYs.indexOf(tile.region.y)
            val evaluatedRows = FpSellPolicy.evaluatedRows(tiles)
            tracker.beginPage(evaluatedRows, requestedScroll)
            val distance = FpSellPolicy.swipeDistance(evaluatedRows)
            if (distance == null) {
                if (anchorRetry) stop("grid rows unavailable after reanchor and overlap scroll on page $page")
                anchorRetry = true
                requestedScroll += loc.rowPitch
                log("page=$page rows=$evaluatedRows swipe=$requestedScroll reanchor=true")
                swipe.scroll(loc.swipeStart, loc.swipeEnd(loc.rowPitch))
                FpSellTiming.SELECTION_SCROLL.wait()
                continue
            }
            anchorRetry = false
            log("page=$page tiles=${tiles.size} rows=$evaluatedRows swipe=$distance")
            for (tile in tiles) {
                val cell = if (tile.skip == null) reader.readCard(tile.region)
                    else FpSellPolicy.Cell(false, FpSellPolicy.Lock.Unknown, null)
                val decision = if (tile.skip != null) "skip:${tile.skip}" else {
                    tracker.seen(tile.column, tile.region.y, cell) ?: FpSellPolicy.decision(cell, options).let {
                        if (it == "select" && tracker.samePosition(tile.column, tile.region.y)) "skip:same-position" else it
                    }
                }
                log(FpSellPolicy.tileLine(page, row(tile), tile.column, cell, decision))
                if (decision != "select") continue
                if (tracker.selected >= 999) { ended = true; break }
                tile.region.click()
                tracker.tapped(tile.column, tile.region.y, cell.lock)
            }
            val bar = reader.scrollbar()
            val end = tracker.finishPage(bar.bottom)
            log("page=$page summary selected=${tracker.selected} rejected=${tracker.rejected} " +
                "stationary=${tracker.stationary} idle=${tracker.idle} scrollbar=$bar")
            if (ended || end != null) {
                log("end of list reason=${end ?: "selection-cap"} selected=${tracker.selected}")
                ended = true
                break
            }
            requestedScroll = distance
            swipe.scroll(loc.swipeStart, loc.swipeEnd(distance))
            log("page=$page swiped distance=$distance rows=$evaluatedRows")
            FpSellTiming.SELECTION_SCROLL.wait()
        }
        if (!ended) stop("page limit reached without the list ending")
        val summary = tracker.summary()
        messages.log(ScriptLog.FpSellSelection(summary))
        if (summary.total == 0) throw ExitException(AutoFriendGacha.ExitReason.InventoryFullNothingToSell)
        finishSale(summary)
        return Result(summary, dryRun)
    }

    /** Fixed taps and delays; a dry run leaves the confirmation visible for owner review. */
    private fun finishSale(summary: FpSellPolicy.Summary) {
        step = SellStep.Flow
        loc.confirm.click()
        if (dryRun) throw ExitException(AutoFriendGacha.ExitReason.SellDryRunDone(summary))
        FpSellTiming.AFTER_ACTION.wait()
        loc.convert.click()
        FpSellTiming.AFTER_ACTION.wait()
        loc.doneClose.click()
        FpSellTiming.AFTER_CLOSE.wait()
    }
}
