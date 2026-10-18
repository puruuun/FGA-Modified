package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Pattern
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

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
    private fun stop(): Nothing = throw ExitException(ExitReason.BannerNotFound)
    fun release() { title?.close(); title = null }
    fun remember() { if (title == null) title = loc.bannerTitle.getPattern() }
    private fun awaitScreen(test: () -> Boolean) {
        repeat(40) { if (useSameSnapIn(test)) return; 0.5.seconds.wait() }
        stop()
    }

    fun matches(savedTitle: Pattern): Boolean =
        loc.bannerTitle.exists(savedTitle, similarity = 0.85) &&
            (findImage(locations.fp.summonCheck, Images.FriendSummon) ||
                findImage(locations.fp.initialSummonCheck, Images.FriendSummon))

    fun restore() {
        val savedTitle = title ?: stop()
        loc.close.click()
        awaitScreen { loc.shop.exists(images[Images.FpSellShop], similarity = 0.9) }
        (loc.menu.find(images[Images.FpSellMenu], similarity = 0.9) ?: stop()).region.click()
        0.5.seconds.wait()
        (loc.summonMenu.find(images[Images.FpSellSummonMenu], similarity = 0.9) ?: stop()).region.click()
        0.8.seconds.wait()
        if (!search({ useSameSnapIn { matches(savedTitle) } }, {
            loc.leftArrow.click()
            0.7.seconds.wait()
        })) stop()
    }
}
