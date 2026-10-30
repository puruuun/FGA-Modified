package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitException
import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha.ExitReason
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import javax.inject.Inject

/** #114: the summon screen reopens on the first banner; press left a user-set number of times, then check the screen. */
class FpBannerReturn @Inject constructor(api: IFgoAutomataApi, private val loc: FpSellLocations,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    private val buttons = FpSellButtons(api, addedTuning = addedTuning)
    private fun stop(reason: ExitReason.BannerReturnStopped): Nothing {
        messages.log(ScriptLog.FpSell("stop step=bannerReturn reason=${reason.label}"))
        throw ExitException(reason)
    }

    private fun fpScreen(): Boolean = fpLabel(locations.fp.summonCheck) || fpLabel(locations.fp.initialSummonCheck)

    private fun summonPatterns() = listOf(Images.FpSellSummonButton, Images.FpSellSummonButtonFree)
        .mapNotNull { runCatching { images[it] }.getOrNull() }

    /** The FP label and a summon button, in one frame. Returns the matched button or null. */
    private fun fpScreenButton(patterns: List<Pattern>): Match? =
        if (!fpScreen()) null else {
            patterns.firstNotNullOfOrNull { loc.summonButton.find(it, similarity = addedTuning.fp.similarity(0.9)) }
                ?: patterns.firstNotNullOfOrNull { loc.dailySummonButton.find(it, similarity = addedTuning.fp.similarity(0.9)) }
        }

    private fun fpLabel(region: Region): Boolean {
        if (addedTuning.fp.similarityOffsetPercent == 0) return findImage(region, Images.FriendSummon)
        val similarity = addedTuning.fp.similarity(prefs.platformPrefs.minSimilarity)
        return region.exists(images[Images.FriendSummon], similarity = similarity) ||
            (prefs.gameServer is GameServer.Jp && region.exists(images[Images.FriendSummon, GameServers.default], similarity = similarity))
    }

    /** Guard the original FGA summon taps with the FP label and a visible button. */
    fun verifySummon() {
        val patterns = summonPatterns()
        buttons.awaitMatch { fpScreenButton(patterns) } ?: stop(ExitReason.SummonMenuFailed)
    }

    fun restore() {
        loc.close.click()
        addedTuning.fp.delay(FpSellTiming.AFTER_CLOSE).wait()
        buttons.menu(loc.menu, Images.FpSellMenu,
            { buttons.visible(loc.summonMenu, Images.FpSellSummonMenu) },
            { stop(ExitReason.WorkshopMenuFailed) })
        // D-40: the summon screen loads behind a long black screen. Tap once, wait, poll, never re-tap.
        buttons.click(loc.summonMenu, Images.FpSellSummonMenu) { stop(ExitReason.SummonMenuFailed) }
        addedTuning.fp.delay(FpSellTiming.SUMMON_LOAD_WAIT).wait()
        if (!buttons.awaitSignal(FpSellTiming.SUMMON_LOAD_POLLS) {
                buttons.visible(loc.leftArrowRegion, Images.FpSellLeftArrow)
            }) stop(ExitReason.SummonMenuFailed)
        // The screen reopens on the first banner; left from the first banner wraps to the last.
        repeat(prefs.fpBannerPosition.coerceIn(1, 2)) {
            buttons.click(loc.leftArrowRegion, Images.FpSellLeftArrow) { stop(ExitReason.SummonMenuFailed) }
            addedTuning.fp.delay(FpSellTiming.AFTER_ARROW).wait()
        }
        val patterns = summonPatterns()
        buttons.awaitMatch { fpScreenButton(patterns) } ?: stop(ExitReason.FpBannerNotAtPosition)
    }
}
