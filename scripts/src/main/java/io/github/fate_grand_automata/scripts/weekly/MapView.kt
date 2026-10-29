package io.github.fate_grand_automata.scripts.weekly

import io.github.fate_grand_automata.scripts.isWide
import io.github.fate_grand_automata.scripts.locations.WIDE_EDGE_INSET
import io.github.lib_automata.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point or displacement on the 720p game area. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * Screen geometry of the chapter map: where a spot may be tapped and how far to drag the map to bring it there
 * (design 0009 D-10). Everything is in 720p game-area pixels; [width] is the game area width (1280 on 16:9).
 */
object MapView {
    /** Largest drag of one scroll step, as a fraction of the screen. */
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
        val portraitRight = (if (inset > 0) 248 else 238) * s + inset
        val portraitTop = (if (inset > 0) 442 else 484) * s
        return when {
            x < 190 * s + inset && y < 100 * s -> true                      // terminal / close button
            fromRight < 560 * s + inset && y < 140 * s -> true               // chapter title
            // Measured at 720p: JP 16:9 diamond x=0..226, top=496; KR wide x=102..321, top=454.
            // Include the full diamond and a 12px margin; wide UI sits 42px higher.
            x < portraitRight && y > portraitTop -> true                    // master portrait
            x < 410 * s + inset && y > 548 * s -> true                       // level and AP status
            // Gift box, mission clipboard and their "기간한정" / "주간 미션" bubbles. Measured at 720p: the bubbles reach
            // x 378..593 and top y=560 on 16:9 and tablet (1280x720 captures), x 476..691 and top y=519 on wide (the map
            // UI sits 42 px higher); 12px margin each. On wide the right bubble edge is 13 px beyond the 16:9 edge plus inset.
            x in 366 * s + inset..(if (inset > 0) 703 * s else 605 * s) && y > (if (inset > 0) 507 else 548) * s -> true
            fromRight < 250 * s + inset && y > 605 * s -> true               // menu button
            fromRight < 90 * s + inset && y in 100 * s..440 * s -> true      // quest list scroll bar column
            else -> false
        }
    }

    /** Test hook (issue #102 simulation): whether a fixed button zone covers the point. */
    internal fun isCovered(x: Double, y: Double, width: Int, height: Int) = covered(x, y, width, height)

    /** True when a tap at ([x], [y]) lands on the map and not on a fixed button, with a margin around the point. */
    fun isTappable(x: Double, y: Double, width: Int, height: Int): Boolean {
        if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0) return false
        val ring = 12.0 * height / 720.0
        if (x - ring < 0 || y - ring < 0 || x + ring > width || y + ring > height) return false
        return !covered(x, y, width, height)
    }

    /** Tappable and at least [CLEARANCE] px (per axis direction) away from every fixed button zone: not a knife edge. */
    fun isClear(x: Double, y: Double, width: Int, height: Int): Boolean = isTappable(x, y, width, height) &&
        listOf(-CLEARANCE to 0.0, CLEARANCE to 0.0, 0.0 to -CLEARANCE, 0.0 to CLEARANCE).none { (dx, dy) -> covered(x + dx, y + dy, width, height) }

    /**
     * D-33: a spot icon that lies at most [MAX_SHIFT] px inside a fixed button's zone, on a map that cannot be moved any
     * further, still shows its upper or outer part. Returns the nearest point within [MAX_SHIFT] px of ([x], [y]) that is
     * outside every zone by at least [CLEARANCE] px and on the screen, or null when there is none. Axis directions only,
     * smallest shift first (up, right, left, down on ties); a spot that already is tappable returns null (no shift needed).
     */
    fun visibleTap(x: Double, y: Double, width: Int, height: Int): ScreenPoint? {
        if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0 || isClear(x, y, width, height)) return null
        fun clear(px: Double, py: Double) = isClear(px, py, width, height)
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

    /**
     * The spot icon's centre sits this many Atlas map pixels above the spot point (measured on the KR Okeanos capture:
     * icon art centre about 21 px above the point at a 0.889 fit; the name plate sits below it).
     */
    const val ICON_OFFSET_MAP = 25.0

    /** Where a tap goes: the icon centre, the icon shifted to its visible part, or the plain spot point. */
    data class TapChoice(val point: ScreenPoint, val kind: String)

    /**
     * Tap target for a spot whose icon centre is ([iconX], [iconY]) and whose Atlas point is ([spotX], [spotY]), screen
     * 720p: the icon centre; else the nearest clear point of the icon ([visibleTap]); else the spot point when that is
     * clear; else null. A covered point is never returned.
     */
    fun resolveTap(iconX: Double, iconY: Double, spotX: Double, spotY: Double, width: Int, height: Int): TapChoice? = when {
        // Issue #102: a clear icon centre ("icon"); else the nearest clear point of the icon; else a clear spot point; and only
        // when nothing is clear, an icon or spot point that merely is not covered (knife edge, "icon-edge" / "spot-edge").
        isClear(iconX, iconY, width, height) -> TapChoice(ScreenPoint(iconX, iconY), "icon")
        else -> visibleTap(iconX, iconY, width, height)?.let { TapChoice(it, "icon-shifted") }
            ?: if (isClear(spotX, spotY, width, height)) TapChoice(ScreenPoint(spotX, spotY), "spot")
            else if (isTappable(iconX, iconY, width, height)) TapChoice(ScreenPoint(iconX, iconY), "icon-edge")
            else if (isTappable(spotX, spotY, width, height)) TapChoice(ScreenPoint(spotX, spotY), "spot-edge") else null
    }

    const val MAX_SHIFT = 30.0
    internal const val CLEARANCE = 4.0

    /** D-39: image maps establish the boundary by fitting again after this inward drag. */
    fun edgeDrag(x: Double, y: Double, width: Int, height: Int): ScreenPoint? {
        val margin = 60.0 * height / 720.0
        if (x !in 0.0..width.toDouble() || y !in 0.0..height.toDouble()) return null
        val dy = when { y < margin -> margin - y; y > height - margin -> height - margin - y; else -> 0.0 }
        fun step(v: Double) = if (v == 0.0) 0.0 else if (v > 0) maxOf(4.0, v) else minOf(-4.0, v)
        return ScreenPoint(0.0, step(dy)).takeIf { it.y != 0.0 }
    }

    /**
     * The drag that moves the point at ([x], [y]) towards the middle of the open map area, or null when it is already
     * tappable. The drag is capped so that one step stays a single bounded scroll.
     */
    fun dragToward(x: Double, y: Double, width: Int, height: Int, centre: Boolean = false): ScreenPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (isTappable(x, y, width, height) && (!centre || abs(y - height * 0.5) <= height * 0.08)) return null
        val target = safeYs(x, width, height).minByOrNull { abs(it - height * 0.5) } ?: return null
        return ScreenPoint(0.0, (target - y).coerceIn(-height * MAX_STEP_Y, height * MAX_STEP_Y))
    }

    private fun safeYs(x: Double, width: Int, height: Int) = (12..height - 12).map { it.toDouble() }.filter { y ->
        isTappable(x, y, width, height) && isTappable(x, y - 4, width, height) && isTappable(x, y + 4, width, height)
    }

    /** D-40/41: choose a reachable safe y at this fixed x, then keep its origin target across refits. */
    fun targetOrigin(x: Double, mapY: Double, origin: Double, range: Double, width: Int, height: Int,
                     centre: Boolean = false, edgeFirst: Boolean = true): Double? {
        if (listOf(x, mapY, origin, range).any { !it.isFinite() } || range < 0) return null
        val y = origin + mapY
        val margin = 60.0 * height / 720.0
        if (isTappable(x, y, width, height) && !centre && (!edgeFirst || y in margin..height - margin)) return origin.coerceIn(-range, 0.0)
        val reachable = safeYs(x, width, height).filter { it - mapY in -range..0.0 }
        val inward = reachable.filter { it in margin..height - margin }.ifEmpty { reachable }
        return inward.minByOrNull { abs(it - height * 0.5) }?.let { (it - mapY).coerceIn(-range, 0.0) }
            ?: origin.coerceIn(-range, 0.0).takeIf { isTappable(x, y, width, height) }
    }

    /** Which point the map move was planned on: the Atlas spot point (as before issue #78) or, when that has no safe row, the icon. */
    data class OriginPlan(val target: Double, val plan: String) { val icon get() = plan == "icon" }

    /**
     * Issue #91: plan the map move on the spot point exactly as before #78; only when that finds no safe row (the spot can
     * never leave a fixed-button zone, e.g. Okeanos 10352 under the bubbles) plan on the icon point, [ICON_OFFSET_MAP] above it.
     * [spotMapY] and [iconMapY] are map pixels already multiplied by the vertical scale.
     */
    fun planOrigin(x: Double, spotMapY: Double, iconMapY: Double, origin: Double, range: Double, width: Int, height: Int,
                   centre: Boolean = false, edgeFirst: Boolean = true): OriginPlan? =
        targetOrigin(x, spotMapY, origin, range, width, height, centre, edgeFirst)?.let { OriginPlan(it, "spot") }
            ?: targetOrigin(x, iconMapY, origin, range, width, height, centre, edgeFirst)?.let { OriginPlan(it, "icon") }

    /**
     * Issue #102: [planOrigin] found no row that keeps the spot or the icon clear of every zone by the safe-row margin (16:9
     * knife edge, e.g. Fuyuki X-G). Instead of giving up, pick the reachable origin (0 down to -[range]) where the icon point
     * needs the smallest [visibleTap] shift, or only the plain spot point is clear. A candidate is rated by its worst result
     * within 2 px of the origin (the drag converges within 2 px), so a one-pixel knife edge never wins. Ties go to the origin
     * nearest to [origin] (fewest scrolls). Null when no reachable origin yields a tap point.
     */
    fun bestEffortOrigin(x: Double, spotMapY: Double, iconMapY: Double, origin: Double, range: Double, width: Int, height: Int): OriginPlan? {
        if (listOf(x, spotMapY, iconMapY, origin, range).any { !it.isFinite() } || range < 0) return null
        fun cost(o: Double): Double? {
            val c = resolveTap(x, o + iconMapY, x, o + spotMapY, width, height) ?: return null
            return when (c.kind) {
                "icon" -> 0.0
                "icon-shifted" -> hypot(c.point.x - x, c.point.y - (o + iconMapY))
                "spot" -> MAX_SHIFT + 1
                else -> MAX_SHIFT + 2
            }
        }
        var best: Triple<Double, Double, Double>? = null // cost, distance from the current origin, origin
        var o = 0.0
        while (true) {
            val costs = listOf(-2.0, 0.0, 2.0).map { cost((o + it).coerceIn(-range, 0.0)) }
            if (costs.none { it == null }) {
                val c = costs.maxOf { it!! }
                val d = abs(o - origin)
                if (best == null || c < best.first - 1e-9 || (abs(c - best.first) <= 1e-9 && d < best.second)) best = Triple(c, d, o)
            }
            if (o <= -range) break
            o = maxOf(o - 1.0, -range)
        }
        return best?.let { OriginPlan(it.third, "icon") }
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
        val hx = 0.0 // Minimum zoom maps fill the width; horizontal gestures cannot move them.
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
