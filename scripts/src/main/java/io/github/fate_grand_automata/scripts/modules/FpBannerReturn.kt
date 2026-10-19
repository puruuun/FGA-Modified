package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Pattern
import javax.inject.Inject

/** D-31: remember only the title; compare in place and after each of at most ten left presses. */
class FpBannerReturn @Inject constructor(api: IFgoAutomataApi, private val loc: FpSellLocations) : IFgoAutomataApi by api {
    companion object {
        const val MAX_STEPS = 10
        fun search(matches: () -> Boolean, left: () -> Unit): Boolean {
            if (matches()) return true
            repeat(MAX_STEPS) { left(); if (matches()) return true }
            return false
        }
    }
    private var title: Pattern? = null
    private val buttons = FpSellButtons(api)
    private fun stop(reason: ExitReason.BannerReturnStopped = ExitReason.BannerTitleNotFound): Nothing {
        messages.log(ScriptLog.FpSell("stop step=bannerReturn reason=${reason.label}"))
        throw ExitException(reason)
    }
    fun release() { title?.close(); title = null }
    fun remember() { if (title == null) title = loc.bannerTitle.getPattern() }
    fun matches(savedTitle: Pattern): Boolean =
        loc.bannerTitle.exists(savedTitle, similarity = 0.85) &&
            (findImage(locations.fp.summonCheck, Images.FriendSummon) ||
                findImage(locations.fp.initialSummonCheck, Images.FriendSummon))

    /** D-36: title and FP cost label guard the summon button itself, which must also match its template. */
    fun summonAgain() {
        val saved = title ?: stop()
        if (!useSameSnapIn { matches(saved) }) stop()
        val patterns = listOf(Images.FpSellSummonButton, Images.FpSellSummonButtonFree)
            .mapNotNull { runCatching { images[it] }.getOrNull() }
        val button = buttons.awaitMatch {
            if (!matches(saved)) null else {
                patterns.firstNotNullOfOrNull { loc.summonButton.find(it, similarity = 0.9) }
                    ?: patterns.firstNotNullOfOrNull { loc.dailySummonButton.find(it, similarity = 0.9) }
            }
        } ?: stop(ExitReason.SummonMenuFailed)
        button.region.click()
        FpSellTiming.AFTER_ACTION.wait()
    }

    fun restore() {
        val savedTitle = title ?: stop()
        buttons.closeSell(loc.screenClose,
            { buttons.visible(loc.shop, Images.FpSellShop) },
            { stop(ExitReason.WorkshopNotReached) })
        buttons.menu(loc.menu, Images.FpSellMenu,
            { buttons.visible(loc.summonMenu, Images.FpSellSummonMenu) },
            { stop(ExitReason.WorkshopMenuFailed) })
        // D-40: the summon screen loads behind a long black screen. Tap once, wait, poll, never re-tap.
        buttons.click(loc.summonMenu, Images.FpSellSummonMenu) { stop(ExitReason.SummonMenuFailed) }
        FpSellTiming.SUMMON_LOAD_WAIT.wait()
        if (!buttons.awaitSignal(FpSellTiming.SUMMON_LOAD_POLLS) {
                buttons.visible(loc.leftArrowRegion, Images.FpSellLeftArrow)
            }) stop(ExitReason.SummonMenuFailed)
        if (!search({ useSameSnapIn { matches(savedTitle) } }, {
            buttons.click(loc.leftArrowRegion, Images.FpSellLeftArrow) { stop() }
            FpSellTiming.AFTER_ARROW.wait()
        })) stop()
    }
}
