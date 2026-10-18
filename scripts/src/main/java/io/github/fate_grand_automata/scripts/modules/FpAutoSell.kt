package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
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
    private val loc get() = reader.loc
    private fun stop(): Nothing = throw ExitException(ExitReason.SellScreenUnknown)

    private fun awaitScreen(test: () -> Boolean) {
        repeat(40) { if (useSameSnapIn(test)) return; 0.5.seconds.wait() }
        stop()
    }

    private fun click(region: Region, image: Images) {
        val match = region.find(images[image], similarity = 0.9) ?: stop()
        match.region.click()
        0.5.seconds.wait()
    }

    fun run(): Int {
        val options = FpSellPolicy.options(prefs, reader.hasLockTemplate)
        click(loc.fullSell, Images.FpSellSell)
        awaitScreen { reader.onSellHeading() }
        // The sell screen may open on the Craft Essence tab. Only the servant tab is read (D-17).
        if (!useSameSnapIn { reader.onServantTab() }) {
            loc.servantTabClick.click()
            0.5.seconds.wait()
        }
        awaitScreen { reader.onSellScreen() }
        // The size toggle cycles 5 -> 6 -> 7 -> 5, so reaching 7 columns takes at most two presses (D-17).
        var presses = 0
        while (!useSameSnapIn { reader.isSevenColumns() }) {
            if (presses++ == 2) stop()
            loc.sizeSwitch.click()
            0.5.seconds.wait()
            awaitScreen { reader.onSellScreen() }
        }
        val counts = mutableMapOf<FpSellPolicy.Category, Int>()
        var unknown = 0
        var unchanged = 0
        var ended = false
        // Bounded even when screenshots or a scroll gesture stop making progress.
        for (page in 0 until 500) {
            if (!useSameSnapIn { reader.onSellScreen() && reader.isSevenColumns() }) stop()
            for (candidate in reader.read()) {
                if (counts.values.sum() >= 999) { ended = true; break }
                val cell = reader.confirmed(candidate.region)
                if (!FpSellPolicy.select(cell, options)) continue
                candidate.region.click()
                0.15.seconds.wait()
                // Count only a visibly acknowledged selection; never retry a possibly toggled tap.
                val after = useSameSnapIn { reader.readCard(candidate.region) }
                if (after.selected != true) stop()
                val category = cell.category ?: stop()
                counts[category] = (counts[category] ?: 0) + 1
                if (cell.lock == FpSellPolicy.Lock.Unknown) unknown++
            }
            if (ended) break
            loc.grid.getPattern().use { before ->
                swipe(loc.swipeStart, loc.swipeEnd)
                0.7.seconds.wait()
                val same = loc.grid.exists(before, similarity = 0.995)
                unchanged = if (same) unchanged + 1 else 0
            }
            if (unchanged >= 2) { ended = true; break }
        }
        if (!ended) stop()
        val summary = FpSellPolicy.Summary(counts.toMap(), unknown)
        messages.log(ScriptLog.FpSellSelection(summary))
        // This guard precedes EVERY path to the confirmation button. Dry run and not-yet-validated servers stop here.
        if (options.dryRun) throw ExitException(ExitReason.SellDryRunDone(summary))
        if (summary.total == 0) return 0
        click(loc.confirm, Images.FpSellConfirm)
        awaitScreen { loc.convertHeading.exists(images[Images.FpSellConvertHeading], similarity = 0.9) }
        click(loc.convert, Images.FpSellConvert)
        awaitScreen { loc.done.exists(images[Images.FpSellDone], similarity = 0.9) }
        click(loc.doneClose, Images.FpSellClose)
        awaitScreen { reader.onSellHeading() }
        return summary.total
    }
}
