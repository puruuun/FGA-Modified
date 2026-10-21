package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.isWide
import io.github.fate_grand_automata.scripts.locations.WIDE_EDGE_INSET
import io.github.lib_automata.Size
import kotlin.math.abs
import kotlin.math.roundToInt

/** A point or displacement on the 720p game area. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * Screen geometry of the chapter map: where a spot may be tapped and how far to drag the map to bring it there
 * (design 0009 D-10). Everything is in 720p game-area pixels; [width] is the game area width (1280 on 16:9).
 */
object MapView {
    /** Largest drag of one scroll step, as a fraction of the screen. */
    private const val MAX_STEP_X = 0.5
    private const val MAX_STEP_Y = 0.35

    /**
     * Wide screens keep the corner UI inside the safe area (owner 21:9 captures 02 and 03: terminal button, portrait,
     * gift box, MENU and the title all 85 px further in), so each zone grows by the inset on its own side.
     * 0 on 16:9 and on screens up to 18:9.
     */
    private fun inset(width: Int, height: Int): Double =
        if (Size(width, height).isWide()) WIDE_EDGE_INSET / 2.0 * height / 720.0 else 0.0

    /** The fixed buttons and banners of the map screen, by their 720p anchors: left, right and bottom. */
    private fun covered(x: Double, y: Double, width: Int, height: Int): Boolean {
        val s = height / 720.0
        val fromRight = width - x
        val inset = inset(width, height)
        return when {
            x < 190 * s + inset && y < 100 * s -> true                      // terminal / close button
            fromRight < 560 * s + inset && y < 140 * s -> true               // chapter title
            x < 225 * s + inset && y > 495 * s -> true                       // master portrait and status
            x < 380 * s + inset && y > 560 * s -> true                       // level and AP status
            x in 375 * s + inset..600 * s + inset && y > 560 * s -> true     // gift box and mission note
            fromRight < 250 * s + inset && y > 605 * s -> true               // menu button
            fromRight < 90 * s + inset && y in 100 * s..440 * s -> true      // quest list scroll bar column
            else -> false
        }
    }

    /** True when a tap at ([x], [y]) lands on the map and not on a fixed button, with a margin around the point. */
    fun isTappable(x: Double, y: Double, width: Int, height: Int): Boolean {
        if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0) return false
        val ring = 12.0 * height / 720.0
        if (x - ring < 0 || y - ring < 0 || x + ring > width || y + ring > height) return false
        return !covered(x, y, width, height)
    }

    /**
     * D-33: a spot icon that lies at most [MAX_SHIFT] px inside a fixed button's zone, on a map that cannot be moved any
     * further, still shows its upper or outer part. Returns the nearest point within [MAX_SHIFT] px of ([x], [y]) that is
     * outside every zone by at least [CLEARANCE] px and on the screen, or null when there is none. Axis directions only,
     * smallest shift first (up, right, left, down on ties); a spot that already is tappable returns null (no shift needed).
     */
    fun visibleTap(x: Double, y: Double, width: Int, height: Int): ScreenPoint? {
        if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0 || isTappable(x, y, width, height)) return null
        fun clear(px: Double, py: Double): Boolean = isTappable(px, py, width, height) &&
            listOf(-CLEARANCE to 0.0, CLEARANCE to 0.0, 0.0 to -CLEARANCE, 0.0 to CLEARANCE).none { (dx, dy) -> covered(px + dx, py + dy, width, height) }
        val s = height / 720.0
        val directions = listOf(0.0 to -1.0, 1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0)
        for (step in 1..(MAX_SHIFT * s).toInt()) {
            for ((dx, dy) in directions) {
                val px = x + dx * step
                val py = y + dy * step
                if (clear(px, py)) return ScreenPoint(px, py)
            }
        }
        return null
    }

    const val MAX_SHIFT = 30.0
    private const val CLEARANCE = 4.0

    /** D-39: image maps establish the boundary by fitting again after this inward drag. */
    fun edgeDrag(x: Double, y: Double, width: Int, height: Int): ScreenPoint? {
        val margin = 60.0 * height / 720.0
        if (x !in 0.0..width.toDouble() || y !in 0.0..height.toDouble()) return null
        val dx = when { x < margin -> margin - x; x > width - margin -> width - margin - x; else -> 0.0 }
        val dy = when { y < margin -> margin - y; y > height - margin -> height - margin - y; else -> 0.0 }
        fun step(v: Double) = if (v == 0.0) 0.0 else if (v > 0) maxOf(4.0, v) else minOf(-4.0, v)
        return ScreenPoint(step(dx), step(dy)).takeIf { it.x != 0.0 || it.y != 0.0 }
    }

    /**
     * The drag that moves the point at ([x], [y]) towards the middle of the open map area, or null when it is already
     * tappable. The drag is capped so that one step stays a single bounded scroll.
     */
    fun dragToward(x: Double, y: Double, width: Int, height: Int, centre: Boolean = false): ScreenPoint? {
        val nearCentre = abs(x - width * 0.5) <= width * 0.08 && abs(y - height * 0.5) <= height * 0.08
        if (isTappable(x, y, width, height) && (!centre || nearCentre)) return null
        val targetX = width * 0.5
        val targetY = height * 0.5
        val horizontalLimit = minOf(width * MAX_STEP_X, maxOf(0.0,width - 460.0 * height / 720.0))
        val dx = (targetX - x).coerceIn(-horizontalLimit, horizontalLimit)
        val dy = (targetY - y).coerceIn(-height * MAX_STEP_Y, height * MAX_STEP_Y)
        return ScreenPoint(dx, dy)
    }

    /**
     * Start and end of the finger for [drag], centred in the part of the map that no fixed button covers. A drag longer
     * than that part is shortened (the route re-localizes and drags again), so neither end lands on a button.
     */
    fun dragPath(drag: ScreenPoint, width: Int, height: Int): Pair<ScreenPoint, ScreenPoint> {
        val s = height / 720.0
        val inset = inset(width, height)
        val left = 372 * s + inset
        val right = width - 126 * s - inset
        val top = 172 * s
        val bottom = 440 * s
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val hx = minOf(abs(drag.x) / 2, (right - left) / 2)
        val hy = minOf(abs(drag.y) / 2, (bottom - top) / 2)
        val sx = if (drag.x < 0) -hx else hx
        val sy = if (drag.y < 0) -hy else hy
        return ScreenPoint(cx - sx, cy - sy) to ScreenPoint(cx + sx, cy + sy)
    }

    /** A drag too small to move the map (below four pixels both ways) cannot make progress. */
    fun isMeaningful(drag: ScreenPoint) = abs(drag.x) >= 4 || abs(drag.y) >= 4

    /** A fresh post-scroll fit must move the origin by at least four 720p pixels. */
    fun originMoved(before: ScreenPoint, after: ScreenPoint): Boolean =
        kotlin.math.hypot(after.x - before.x, after.y - before.y) >= 4.0

    /** At a map boundary recentering may reuse the current safe spot; never repeat an ineffective drag. */
    fun afterScroll(before: ScreenPoint, after: ScreenPoint, recenter: Boolean, tappable: Boolean): ScrollResult =
        if (originMoved(before, after)) ScrollResult.Refit
        else if (recenter && tappable) ScrollResult.Tap
        else ScrollResult.Stop

    enum class ScrollResult { Refit, Tap, Stop }

    /** 720p to the 1440p script coordinates the click and scroll helpers take. */
    fun toScript(v: Double) = (v * 2).roundToInt()
}
