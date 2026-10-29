package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.ceil
import kotlin.math.max

/**
 * Issue #111: edge geometry for every chapter map (no map picture is matched any more). The zoomed-out map is the game-area
 * width wide with its left edge on the screen's left edge; pushed to its top or bottom end its position is known exactly.
 *
 * Design 0009 D-31 (where this started): chapters whose Atlas `mapImage` is no map. Shin (war 303) publishes an 8x8 texture; the real map is
 * `mapGimmicks` layers whose base layer (gimmick_030301, depth -1100) is not on the Atlas static server in any region
 * (HTTP 404; the other layers are a fortress sprite, fog and rope pieces), so the map cannot be assembled for image fitting.
 *
 * Its geometry is known without the picture. Atlas spot coordinates are pixels of the display map (`mapImageW` x `mapImageH`,
 * 2048x1440 for Shin; layer x/y are centre coordinates and `scale` per mille: 512x360 fog at scale 4000 is exactly that size).
 * Fully zoomed out the game shows the map at the smallest scale that covers the screen, with its left edge at the screen's
 * left edge, and the map stops at its borders. So after a drag long enough to hit the top (or bottom) border the origin is
 * known exactly. Owner frame 30 (Shin, beta.8) confirms it: all eleven spot icons sit within 8 px of
 * `0.625 * (spotX, spotY)`, origin (0, -2).
 */
object PlaceholderMap {
    enum class Edge { Top, Bottom }

    /** Where to tap once the map has been dragged to [edge]; [point] is in 720p game-area pixels. */
    data class Target(val edge: Edge, val point: ScreenPoint, val fit: MapFit, val plan: String = "spot")

    class Geometry(val mapWidth: Int, val mapHeight: Int, val viewWidth: Int, val viewHeight: Int) {
        /** Smallest scale at which the map still covers the screen. */
        val scale = max(viewWidth.toDouble() / mapWidth, viewHeight.toDouble() / mapHeight)
        /** Only maps limited by the screen width are modelled (left edge on the screen's left edge). */
        val widthFit = viewWidth.toDouble() / mapWidth >= viewHeight.toDouble() / mapHeight
        /** How far the map can travel vertically, in screen pixels. */
        val range = (mapHeight * scale - viewHeight).coerceAtLeast(0.0)

        fun fit(edge: Edge) = MapFit(1.0, scale, scale, 0.0, if (edge == Edge.Top) 0.0 else -range, 0, 0.0, viewWidth.toDouble(), viewHeight.toDouble())

        /** Drags of [dragLength] px needed to be sure to reach a border from anywhere, one more than the range needs. */
        fun drags(dragLength: Double) = ceil(range / dragLength).toInt() + 1

        /**
         * Issue #91: the spot point decides first, exactly as before #78; the icon point ([MapView.ICON_OFFSET_MAP] above it)
         * is searched only when no edge gives a tappable spot. Each plan tries the first edge (top, then bottom) on which the
         * point can be tapped, then the one on which its visible part can.
         */
        fun target(spotX: Double, spotY: Double): Target? {
            if (!widthFit || !spotX.isFinite() || !spotY.isFinite()) return null
            return search(spotX, spotY, spotY, "spot") ?: search(spotX, spotY - MapView.ICON_OFFSET_MAP, spotY, "icon")
        }

        private fun search(spotX: Double, planY: Double, spotY: Double, plan: String): Target? {
            val edges = Edge.entries.filter { it == Edge.Top || range > 0 }
            for (edge in edges) {
                val border = fit(edge)
                val shift = MapView.edgeDrag(border.screenX(spotX), border.screenY(planY), viewWidth, viewHeight)?.y ?: 0.0
                val fit = border.copy(originY = (border.originY + shift).coerceIn(-range, 0.0))
                val x = fit.screenX(spotX)
                val y = fit.screenY(planY)
                if (MapView.isTappable(x, y, viewWidth, viewHeight)) return Target(edge, ScreenPoint(x, y), fit, plan)
            }
            for (edge in edges) {
                val fit = fit(edge)
                // Issue #111: the same rule as the final tap (MapView.resolveTap on the icon centre), so a target is never one the tap cannot reach.
                val point = MapView.resolveTap(fit.screenX(spotX), fit.screenY(spotY - MapView.ICON_OFFSET_MAP), fit.screenX(spotX), fit.screenY(spotY), viewWidth, viewHeight)?.point
                point?.let { return Target(edge, it, fit, plan) }
            }
            return null
        }
    }

    fun geometry(map: WarMap, viewWidth: Int, viewHeight: Int): Geometry? =
        if (map.width <= 0 || map.height <= 0 || viewWidth <= 0 || viewHeight <= 0) null
        else Geometry(map.width, map.height, viewWidth, viewHeight).takeIf { it.widthFit }
}
