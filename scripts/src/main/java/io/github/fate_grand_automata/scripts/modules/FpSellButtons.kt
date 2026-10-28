package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.Match
import io.github.lib_automata.Region
import kotlin.time.Duration
import kotlin.time.TimeSource

/** D-39: settle before finding; only MENU and summon-menu may retry. */
class FpSellButtons(private val api: IFgoAutomataApi, private val clock: TimeSource = TimeSource.Monotonic) : IFgoAutomataApi by api {
    fun awaitSignal(polls: Int, signal: () -> Boolean): Boolean {
        val started = clock.markNow()
        val timeout = FpSellTiming.FIND_POLL * polls
        var waited = Duration.ZERO
        while (true) {
            if (useSameSnapIn(signal)) return true
            // Include capture/matching time, and also bound tests whose mocked wait returns immediately.
            val remaining = timeout - maxOf(started.elapsedNow(), waited)
            if (remaining <= Duration.ZERO) return false
            val delay = minOf(FpSellTiming.FIND_POLL, remaining)
            delay.wait()
            waited += delay
        }
    }

    fun visible(region: Region, image: Images): Boolean =
        runCatching { images[image] }.getOrNull()?.let { region.exists(it, similarity = 0.9) } == true

    fun awaitMatch(find: () -> Match?): Match? {
        FpSellTiming.BUTTON_SETTLE.wait()
        var match: Match? = null
        awaitSignal(FpSellTiming.FIND_POLLS) { match = find(); match != null }
        return match
    }

    /** No retry: FP summon and all sell buttons use this path. */
    fun click(region: Region, image: Images, missing: () -> Nothing) {
        val pattern = runCatching { images[image] }.getOrNull() ?: missing()
        val match = awaitMatch { region.find(pattern, similarity = 0.9) } ?: missing()
        match.region.click()
    }

    /** Only MENU and the summon menu icon are allowed to use this operation. */
    fun menu(region: Region, image: Images, next: () -> Boolean, missing: () -> Nothing) {
        require(image == Images.FpSellMenu || image == Images.FpSellSummonMenu)
        navigate(region, image, next, missing)
    }

    private fun navigate(region: Region, image: Images, next: () -> Boolean, missing: () -> Nothing) {
        repeat(2) {
            click(region, image, missing)
            if (awaitSignal(FpSellTiming.NEXT_SCREEN_POLLS, next)) return
        }
        missing()
    }
}
