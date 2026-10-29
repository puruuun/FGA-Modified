package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning

/** Upstream end templates, with a threshold measured independently for the weekly list. */
object ListScrollbar {
    // Rail-sized windows (beta.6, #90): end minimum .817; non-end maximum .381. Midpoint, >=.21 margin.
    const val SIMILARITY = 0.60
    data class State(val top: Boolean, val bottom: Boolean, val topScore: Double? = null, val bottomScore: Double? = null)
    fun read(top: Double?, bottom: Double?, tuning: FeatureTuning = FeatureTuning()) = State(top != null && top >= tuning.similarity(SIMILARITY),
        bottom != null && bottom >= tuning.similarity(SIMILARITY), top, bottom)

    fun read(api: IFgoAutomataApi, loc: WeeklyRouteLocations, tuning: FeatureTuning = FeatureTuning()): State = with(api) {
        useSameSnapIn {
            read(loc.topScrollbarRegion.find(images[Images.SupportScrollBarTop], 0.01)?.score,
                loc.bottomScrollbarRegion.find(images[Images.SupportScrollBarBottom], 0.01)?.score, tuning)
        }
    }
}

data class ListScanFrame<T>(val valid: Boolean, val match: T?, val scrollbar: ListScrollbar.State)

/** Search lower content first, then upper; only template ends, screen departure and the cap stop scanning. */
object WeeklyListScan {
    fun atEnd(state: ListScrollbar.State, direction: ListSearchDirection) =
        if (direction == ListSearchDirection.Down) state.bottom else state.top

    fun <T> run(maxScrolls: Int, capture: () -> ListScanFrame<T>, scroll: (ListSearchDirection, Int) -> Unit,
        onEnd: (ListSearchDirection) -> Unit = {}, log: (String) -> Unit = {}): T? {
        var frame = capture()
        log("scroll frame scrollbar=${frame.scrollbar}")
        if (!frame.valid) return null
        frame.match?.let { return it }
        val reached = ListSearchDirection.entries.filter { atEnd(frame.scrollbar, it) }.toMutableSet()
        for (direction in ListSearchDirection.entries) {
            if (direction in reached) {
                log("scroll skip direction=$direction reason=scrollbar-end scrollbar=${frame.scrollbar}")
                onEnd(direction)
                continue
            }
            log("scroll direction=$direction reason=search scrollbar=${frame.scrollbar}")
            for (index in 1..maxScrolls) {
                scroll(direction, index)
                frame = capture()
                log("scroll direction=$direction count=$index scrollbar=${frame.scrollbar}")
                if (!frame.valid) return null
                frame.match?.let { return it }
                val reason = when {
                    atEnd(frame.scrollbar, direction) -> "scrollbar-end"
                    index == maxScrolls -> "safety-cap"
                    else -> null
                }
                if (reason != null) {
                    if (reason == "scrollbar-end") reached += direction
                    log("scroll end direction=$direction reason=$reason; next-direction")
                    break
                }
            }
            onEnd(direction)
        }
        return null
    }
}
