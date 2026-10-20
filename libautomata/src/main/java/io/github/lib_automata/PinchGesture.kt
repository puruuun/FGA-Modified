package io.github.lib_automata

/** Where each of the two fingers of a pinch starts and ends. */
data class PinchPlan(val startA: Location, val endA: Location, val startB: Location, val endB: Location, val durationMs: Long) {
    val startSpan get() = span(startA, startB)
    val endSpan get() = span(endA, endB)

    private fun span(a: Location, b: Location) = Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}

/** Builds two-finger pinch plans. The fingers sit on a horizontal line through the center and move symmetrically. */
object PinchGesture {
    const val DEFAULT_DURATION_MS = 700L

    /**
     * Fingers [startSpan] apart move to [endSpan] apart around [center]. A span larger than the bounds allow is
     * clamped so that both fingers stay inside [bounds] on the start and end positions (zoom out: shrink [endSpan],
     * zoom in: grow it).
     */
    fun plan(center: Location, startSpan: Int, endSpan: Int, bounds: Region, durationMs: Long = DEFAULT_DURATION_MS): PinchPlan {
        require(startSpan > 0 && endSpan > 0) { "pinch spans must be positive" }
        require(durationMs in 1..60_000) { "pinch duration must be positive" }
        require(center.y >= bounds.y && center.y < bounds.bottom) { "pinch center outside vertical bounds" }
        require(startSpan >= 2 && endSpan >= 2) { "pinch fingers must stay distinct" }
        val half = minOf(center.x - bounds.x, bounds.right - 1 - center.x)
        require(half > 0) { "pinch center is outside the bounds" }
        val s = minOf(startSpan / 2, half)
        val e = minOf(endSpan / 2, half)
        return PinchPlan(
            Location(center.x - s, center.y), Location(center.x - e, center.y),
            Location(center.x + s, center.y), Location(center.x + e, center.y),
            durationMs
        )
    }

    /** Zoom out: the fingers start wide (about 60% of the bounds width) and meet near the center. */
    fun zoomOut(bounds: Region, durationMs: Long = DEFAULT_DURATION_MS): PinchPlan =
        plan(bounds.center, bounds.width * 6 / 10, maxOf(8, bounds.width / 40), bounds, durationMs)
}
