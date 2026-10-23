package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.abs

/** Fixed origin target and observed finger-to-map gain. Each step needs a fresh localization. */
class VerticalMapDrag(val target: Double, private val width: Int, private val height: Int, initialRatio: Double = 1.0, private val maxTravel: Double = Double.POSITIVE_INFINITY) {
    var ratio = initialRatio.coerceIn(0.1, 2.0)
        private set
    var stationary = false
        private set
    private var previous: Double? = null
    private var requested = 0.0

    fun next(origin: Double): ScreenPoint? {
        previous?.let { before ->
            val moved = origin - before
            stationary = abs(moved) < 0.75
            if (!stationary && moved * requested > 0) ratio = abs(moved / requested).coerceIn(0.1, 2.0)
        }
        if (stationary || abs(target - origin) <= 2.0) return null
        val delta = ((target - origin) / ratio).coerceIn(-maxTravel, maxTravel)
        // Keep a useful gesture even on a device that moves only half the requested distance.
        val drag = ScreenPoint(0.0, if (delta > 0) maxOf(6.0, delta) else minOf(-6.0, delta))
        val (from, to) = MapView.dragPath(drag, width, height)
        previous = origin
        requested = to.y - from.y
        return ScreenPoint(0.0, requested)
    }
}
