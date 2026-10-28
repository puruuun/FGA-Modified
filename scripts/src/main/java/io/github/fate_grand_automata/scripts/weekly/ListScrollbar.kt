package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.modules.ScrollbarBoxes

/** Weekly-list end caps and a separate rail check, measured on JP/KR 16:9, wide and tablet frames. */
object ListScrollbar {
    // End cores: 250..254; other end cores <=150. Rail row coverage: >=.917, absent <=.25.
    val boxes = ScrollbarBoxes(thumbMinimum = 220.0, endContrast = 30.0, railContrast = 20.0, railMinimumRows = 0.6)
    data class State(val railPresent: Boolean, val top: Boolean, val bottom: Boolean,
        val topBox: ScrollbarBoxes.Sample? = null, val bottomBox: ScrollbarBoxes.Sample? = null,
        val railBox: ScrollbarBoxes.Sample? = null)

    fun read(top: ScrollbarBoxes.Sample?, bottom: ScrollbarBoxes.Sample?, rail: ScrollbarBoxes.Sample?) =
        State(boxes.rail(rail), boxes.thumb(top), boxes.thumb(bottom), top, bottom, rail)
}

data class ListScanFrame<T>(val valid: Boolean, val match: T?, val scrollbar: ListScrollbar.State)

/** Search lower content first, then upper; only end boxes, no rail, screen departure and the cap stop scanning. */
object WeeklyListScan {
    fun atEnd(state: ListScrollbar.State, direction: ListSearchDirection) =
        if (direction == ListSearchDirection.Down) state.bottom else state.top

    fun <T> run(maxScrolls: Int, capture: () -> ListScanFrame<T>, scroll: (ListSearchDirection, Int) -> Unit,
        onEnd: (ListSearchDirection) -> Unit = {}, log: (String) -> Unit = {}): T? {
        var frame = capture()
        log("scroll frame scrollbar=${frame.scrollbar}")
        if (!frame.valid) return null
        frame.match?.let { return it }
        if (!frame.scrollbar.railPresent) {
            for (direction in ListSearchDirection.entries) {
                log("scroll skip direction=$direction reason=no-scrollbar scrollbar=${frame.scrollbar}")
                onEnd(direction)
            }
            return null
        }
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
                    !frame.scrollbar.railPresent -> "no-scrollbar"
                    atEnd(frame.scrollbar, direction) -> "scrollbar-end"
                    index == maxScrolls -> "safety-cap"
                    else -> null
                }
                if (reason != null) {
                    if (reason == "scrollbar-end") reached += direction
                    log("scroll end direction=$direction reason=$reason; next-direction")
                    if (reason == "no-scrollbar") { onEnd(direction); return null }
                    break
                }
            }
            onEnd(direction)
        }
        return null
    }
}
