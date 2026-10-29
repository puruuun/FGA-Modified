package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations

/** Upstream end templates, with a threshold measured independently for the weekly list. */
object ListScrollbar {
    // Rail-sized windows (beta.6, #90): end minimum .817; non-end maximum .381. Midpoint, >=.21 margin.
    const val SIMILARITY = 0.60
    data class State(val top: Boolean, val bottom: Boolean, val topScore: Double? = null, val bottomScore: Double? = null)
    fun read(top: Double?, bottom: Double?) = State(top != null && top >= SIMILARITY,
        bottom != null && bottom >= SIMILARITY, top, bottom)

    fun read(api: IFgoAutomataApi, loc: WeeklyRouteLocations): State = with(api) {
        useSameSnapIn {
            read(loc.topScrollbarRegion.find(images[Images.SupportScrollBarTop], 0.01)?.score,
                loc.bottomScrollbarRegion.find(images[Images.SupportScrollBarBottom], 0.01)?.score)
        }
    }
}

data class ListScanFrame<T>(val valid: Boolean, val match: T?, val scrollbar: ListScrollbar.State)

/** Search lower content first, then upper; only template ends, screen departure and the cap stop scanning. */
object WeeklyListScan {
    /** Scrolls up until the top end template shows; false when the cap is reached first (#103). */
    fun toTop(maxScrolls: Int, bar: () -> ListScrollbar.State, scroll: (ListSearchDirection, Int) -> Unit,
        log: (String) -> Unit = {}): Boolean {
        if (bar().top) return true
        for (index in 1..maxScrolls) {
            scroll(ListSearchDirection.Up, index)
            if (bar().top) { log("vault step=top-confirmed scrolls=$index"); return true }
        }
        return false
    }

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
