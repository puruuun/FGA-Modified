package io.github.fate_grand_automata.scripts.weekly

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

    /** The fixed buttons and banners of the map screen, by their 720p anchors: left, right and bottom. */
    private fun covered(x: Double, y: Double, width: Int, height: Int): Boolean {
        val s = height / 720.0
        val fromRight = width - x
        return when {
            x < 190 * s && y < 100 * s -> true                      // terminal / close button
            fromRight < 560 * s && y < 140 * s -> true               // chapter title
            x < 340 * s && y > 470 * s -> true                       // master portrait and status
            x in 340 * s..640 * s && y > 540 * s -> true             // gift box and mission note
            fromRight < 230 * s && y > 590 * s -> true               // menu button
            fromRight < 90 * s && y in 100 * s..440 * s -> true      // quest list scroll bar column
            else -> false
        }
    }

    /** True when a tap at ([x], [y]) lands on the map and not on a fixed button, with a margin around the point. */
    fun isTappable(x: Double, y: Double, width: Int, height: Int): Boolean {
        if (x < 20 || y < 20 || x > width - 20 || y > height - 20) return false
        val m = 24.0 * height / 720.0
        return !covered(x, y, width, height) && !covered(x - m, y, width, height) && !covered(x + m, y, width, height) &&
            !covered(x, y - m, width, height) && !covered(x, y + m, width, height)
    }

    /**
     * The drag that moves the point at ([x], [y]) towards the middle of the open map area, or null when it is already
     * tappable. The drag is capped so that one step stays a single bounded scroll.
     */
    fun dragToward(x: Double, y: Double, width: Int, height: Int): ScreenPoint? {
        if (isTappable(x, y, width, height)) return null
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
        val left = 372 * s
        val right = width - 126 * s
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

    /** 720p to the 1440p script coordinates the click and scroll helpers take. */
    fun toScript(v: Double) = (v * 2).roundToInt()
}
