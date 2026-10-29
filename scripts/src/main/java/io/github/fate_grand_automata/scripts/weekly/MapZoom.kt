package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.abs

/**
 * Issue #111: the full zoom-out is confirmed without a map image. After each pinch the scale between the frame before and the
 * frame after is measured (feature matches of the map motion crop). A map already at its smallest scale cannot shrink any
 * further, so the pinch leaves the scale at 1.0; two pinches in a row that do so confirm the zoom. A pinch that did not
 * register at all also reads 1.0, which is why one reading is never enough.
 */
object MapZoom {
    const val TOLERANCE = 0.02
    const val CONFIRMATIONS = 2
    const val MAX_PINCHES = 6

    fun atMinimum(scale: Double?) = scale != null && scale.isFinite() && abs(scale - 1.0) <= TOLERANCE

    /** [history] holds the measured frame-to-frame scale of each pinch so far, null when it could not be measured. */
    fun confirmed(history: List<Double?>) = history.size >= CONFIRMATIONS && history.takeLast(CONFIRMATIONS).all(::atMinimum)
}
