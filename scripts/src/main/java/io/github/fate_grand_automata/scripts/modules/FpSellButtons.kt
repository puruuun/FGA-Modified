package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.Match
import io.github.lib_automata.Region
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/** D-37: fresh-frame polling. Only menu navigation may retry a tap. */
class FpSellButtons(private val api: IFgoAutomataApi, private val clock: TimeSource = TimeSource.Monotonic) : IFgoAutomataApi by api {
    companion object {
        const val BUTTON_POLLS = 20
        const val NEXT_SCREEN_POLLS = 30
        val POLL = 100.milliseconds
    }

    fun awaitSignal(polls: Int, signal: () -> Boolean): Boolean {
        val started = clock.markNow()
        val timeout = POLL * polls
        var waited = Duration.ZERO
        while (true) {
            if (useSameSnapIn(signal)) return true
            // Include capture/matching time, and also bound tests whose mocked wait returns immediately.
            val remaining = timeout - maxOf(started.elapsedNow(), waited)
            if (remaining <= Duration.ZERO) return false
            val delay = minOf(POLL, remaining)
            delay.wait()
            waited += delay
        }
    }

    fun visible(region: Region, image: Images): Boolean =
        runCatching { images[image] }.getOrNull()?.let { region.exists(it, similarity = 0.9) } == true

    fun awaitMatch(find: () -> Match?): Match? {
        var match: Match? = null
        awaitSignal(BUTTON_POLLS) { match = find(); match != null }
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
        repeat(2) {
            click(region, image, missing)
            if (awaitSignal(NEXT_SCREEN_POLLS, next)) return
        }
        missing()
    }
}
