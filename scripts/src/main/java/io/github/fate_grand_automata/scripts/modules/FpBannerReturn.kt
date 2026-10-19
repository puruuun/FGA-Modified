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

    private fun click(region: io.github.lib_automata.Region, image: Images) {
        val pattern = runCatching { images[image] }.getOrNull() ?: stop()
        (region.find(pattern, similarity = 0.9) ?: stop()).region.click()
    }

    /** D-36: title and FP cost label guard the summon button itself, which must also match its template. */
    fun summonAgain() {
        val saved = title ?: stop()
        if (!useSameSnapIn { matches(saved) }) stop()
        val patterns = listOf(Images.FpSellSummonButton, Images.FpSellSummonButtonFree)
            .mapNotNull { runCatching { images[it] }.getOrNull() }
        val button = useSameSnapIn {
            patterns.firstNotNullOfOrNull { loc.summonButton.find(it, similarity = 0.9) }
                ?: patterns.firstNotNullOfOrNull { loc.dailySummonButton.find(it, similarity = 0.9) }
        } ?: stop()
        button.region.click()
        0.5.seconds.wait()
    }

    fun restore() {
        val savedTitle = title ?: stop()
        click(loc.screenClose, Images.FpSellScreenClose)
        awaitScreen { loc.shop.exists(images[Images.FpSellShop], similarity = 0.9) }
        (loc.menu.find(images[Images.FpSellMenu], similarity = 0.9) ?: stop()).region.click()
        0.5.seconds.wait()
        (loc.summonMenu.find(images[Images.FpSellSummonMenu], similarity = 0.9) ?: stop()).region.click()
        0.8.seconds.wait()
        if (!search({ useSameSnapIn { matches(savedTitle) } }, {
            click(loc.leftArrowRegion, Images.FpSellLeftArrow)
            0.7.seconds.wait()
        })) stop()
    }
}
