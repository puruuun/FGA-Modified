package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.SellStep
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** D-36: dry run cancels the final confirmation; only real selling can reach Convert. */
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
        if (step == SellStep.Flow) {
            if (!buttons.awaitSignal(FpSellButtons.BUTTON_POLLS, test)) stop("screen not reached after 2s: $what")
            return
        }
        repeat(40) { if (useSameSnapIn(test)) return; 0.5.seconds.wait() }
        stop("screen not reached: $what")
    }
    private fun click(region: Region, image: Images) {
        buttons.click(region, image) { stop("button not found after 2s: $image") }
        0.5.seconds.wait()
    }

    fun run(): Result {
        val options = FpSellPolicy.options(prefs, reader.hasLockTemplate)
        step = SellStep.Entry
        dryRun = options.dryRun
        progress = FpSellProgress(loc.rowPitch)
        return selectAndFinish(options)
    }

    private fun selectAndFinish(options: FpSellPolicy.Options): Result {
        val tracker = checkNotNull(progress)
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
        var ended = false
        var requestedScroll = 0
        for (page in 0 until 500) {
            if (!useSameSnapIn { reader.onSellScreen() }) stop("sell screen lost on page $page")
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
                0.7.seconds.wait()
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
            val end = tracker.finishPage()
            log("page=$page summary selected=${tracker.selected} rejected=${tracker.rejected} " +
                "stationary=${tracker.stationary} idle=${tracker.idle}")
            if (ended || end != null) {
                log("end of list reason=${end ?: "selection-cap"} selected=${tracker.selected}")
                ended = true
                break
            }
            requestedScroll = distance
            swipe.scroll(loc.swipeStart, loc.swipeEnd(distance))
            log("page=$page swiped distance=$distance rows=$evaluatedRows")
            0.7.seconds.wait()
        }
        if (!ended) stop("page limit reached without the list ending")
        val summary = tracker.summary()
        messages.log(ScriptLog.FpSellSelection(summary))
        if (summary.total > 0) finishSale(dryRun)
        return Result(summary, dryRun)
    }

    /** Final dialog is verified in both modes. The dry branch returns before any Convert access. */
    private fun finishSale(cancel: Boolean) {
        step = SellStep.Flow
        click(loc.confirm, Images.FpSellConfirm)
        awaitScreen("final confirmation") {
            loc.convertHeading.exists(images[Images.FpSellConvertHeading], similarity = 0.9) &&
                loc.cancel.exists(images[Images.FpSellCancel], similarity = 0.9) &&
                loc.convert.exists(images[Images.FpSellConvert], similarity = 0.9)
        }
        if (cancel) {
            click(loc.cancel, Images.FpSellCancel)
            awaitScreen("sell screen after cancel") { reader.onSellScreen() }
            log("flow dry-run-cancel verified selected=$selectedSoFar")
            return
        }
        click(loc.convert, Images.FpSellConvert)
        awaitScreen("done") { loc.done.exists(images[Images.FpSellDone], similarity = 0.9) }
        click(loc.doneClose, Images.FpSellClose)
        awaitScreen("sell heading after close") { reader.onSellHeading() }
    }
}
