package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.abs

/** Bright thumb on a dark rail. Inputs are grayscale pixels from the measured rail core, never a full screen. */
object ListScrollbar {
    fun position(pixels: ByteArray?, width: Int, height: Int): Double? {
        if (pixels == null || width <= 0 || height < 40 || pixels.size != width * height) return null
        val profile = DoubleArray(height) { y ->
            (0 until width).sumOf { x -> pixels[y * width + x].toInt() and 255 }.toDouble() / width
        }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (y in 0..height) {
            if (y < height && profile[y] >= 210) {
                if (start < 0) start = y
            } else if (start >= 0) {
                if (y - start >= 20) runs += start until y
                start = -1
            }
        }
        val thumb = runs.singleOrNull() ?: return null
        val travel = height - thumb.count()
        // Reject missing groove/full-height highlights and bright scenery outside a putative thumb.
        if (travel < 20 || profile.indices.count { it !in thumb && profile[it] < 190 } < travel * 0.9) return null
        val top = thumb.first
        val bottom = height - 1 - thumb.last
        return when {
            top <= 8 -> 0.0
            bottom <= 8 -> 1.0
            else -> top.toDouble() / travel
        }
    }

    /** Ignore small compression/lighting changes; at least 1% of banner pixels must change by >12 gray levels. */
    fun moved(before: ByteArray?, after: ByteArray?): Boolean? {
        if (before == null || after == null || before.isEmpty() || before.size != after.size) return null
        val changed = before.indices.count { abs((before[it].toInt() and 255) - (after[it].toInt() and 255)) > 12 }
        return changed > before.size * 0.01
    }
}

data class ListScanFrame<T>(val valid: Boolean, val match: T?, val position: Double?, val pixels: ByteArray?)

/** Bounded, screenshot-independent scan policy shared by the driver and unit tests. */
object WeeklyListScan {
    fun atEnd(position: Double?, direction: ListSearchDirection) = position != null &&
        if (direction == ListSearchDirection.Down) position >= 0.99 else position <= 0.01

    fun <T> run(maxScrolls: Int, capture: () -> ListScanFrame<T>, scroll: (ListSearchDirection, Int) -> Unit,
        onEnd: (ListSearchDirection) -> Unit = {}, log: (String) -> Unit = {}): T? {
        var frame = capture()
        if (!frame.valid) return null
        frame.match?.let { return it }
        val reached = ListSearchDirection.entries.filter { atEnd(frame.position, it) }.toMutableSet()
        for (direction in ListSearchDirection.entries) {
            if (direction in reached) {
                log("scroll skip direction=$direction reason=scrollbar-end scrollbar=${label(frame.position)}")
                onEnd(direction)
                continue
            }
            log("scroll direction=$direction reason=search scrollbar=${label(frame.position)}")
            for (index in 1..maxScrolls) {
                val before = frame
                scroll(direction, index)
                frame = capture()
                if (!frame.valid) return null
                val moved = ListScrollbar.moved(before.pixels, frame.pixels)
                val contradicted = moved == true && atEnd(frame.position, direction)
                log("scroll direction=$direction count=$index scrollbar=${label(frame.position)} moved=${moved ?: "unknown"}")
                if (contradicted) log("scroll distrust direction=$direction reason=scrollbar-end-but-frame-moved")
                // The final frame is searched exactly once before ending the direction.
                frame.match?.let { return it }
                val reason = when {
                    moved == false -> "stationary"
                    !contradicted && atEnd(frame.position, direction) -> "scrollbar-end"
                    index == maxScrolls -> "safety-cap"
                    else -> null
                }
                if (reason != null) {
                    if (reason != "safety-cap") reached += direction
                    log("scroll end direction=$direction reason=$reason; next-direction")
                    break
                }
            }
            onEnd(direction)
        }
        return null
    }

    private fun label(position: Double?) = position?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "unknown"
}
