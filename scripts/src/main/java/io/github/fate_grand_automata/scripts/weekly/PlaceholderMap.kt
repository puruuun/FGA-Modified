package io.github.fate_grand_automata.scripts.weekly

import kotlin.math.ceil
import kotlin.math.max

/**
 * Design 0009 D-31: chapters whose Atlas `mapImage` is no map. Shin (war 303) publishes an 8x8 texture; the real map is
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
    /** A texture this small on either side is a placeholder, not a map. */
    const val MAX_PLACEHOLDER_SIDE = 64

    fun isPlaceholder(textureWidth: Int, textureHeight: Int) = textureWidth <= MAX_PLACEHOLDER_SIDE || textureHeight <= MAX_PLACEHOLDER_SIDE

    enum class Edge { Top, Bottom }

    /** Where to tap once the map has been dragged to [edge]; [point] is in 720p game-area pixels. */
    data class Target(val edge: Edge, val point: ScreenPoint, val fit: MapFit)

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

        /** The first edge (top, then bottom) on which the spot can be tapped, or on which its visible icon part can. */
        fun target(spotX: Double, spotY: Double): Target? {
            if (!widthFit || !spotX.isFinite() || !spotY.isFinite()) return null
            val edges = Edge.entries.filter { it == Edge.Top || range > 0 }
            for (edge in edges) {
                val fit = fit(edge)
                val x = fit.screenX(spotX)
                val y = fit.screenY(spotY)
                if (MapView.isTappable(x, y, viewWidth, viewHeight)) return Target(edge, ScreenPoint(x, y), fit)
            }
            for (edge in edges) {
                val fit = fit(edge)
                MapView.visibleTap(fit.screenX(spotX), fit.screenY(spotY), viewWidth, viewHeight)?.let { return Target(edge, it, fit) }
            }
            return null
        }
    }

    fun geometry(map: WarMap, viewWidth: Int, viewHeight: Int): Geometry? =
        if (map.width <= 0 || map.height <= 0 || viewWidth <= 0 || viewHeight <= 0) null
        else Geometry(map.width, map.height, viewWidth, viewHeight).takeIf { it.widthFit }
}
