package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.ImageFeatureMatch
import kotlin.test.*

class WeeklyMapEdgeTest {
    private fun moveAndRefit(width: Int, height: Int = 720) {
        // One common minimum-zoom map, still larger than all three viewport aspect ratios.
        val scale = 2.0 / 3
        val initialX = (width - 2400 * scale) / 2
        val initialY = (height - 1200 * scale) / 2
        val screenAnchors = listOf(
            ScreenPoint(initialX + 5 * scale, initialY + 5 * scale),
            ScreenPoint(initialX + 2395 * scale, initialY + 1195 * scale),
            ScreenPoint(11.0, 360.0), ScreenPoint(width - 11.0, 360.0),
            ScreenPoint(width / 2.0, 11.0), ScreenPoint(width / 2.0, height - 11.0),
            ScreenPoint(150.0, 600.0), ScreenPoint(450.0, 600.0), ScreenPoint(width - 100.0, 650.0))
        for (anchor in screenAnchors) {
            val mapX = (anchor.x - initialX) / scale
            val mapY = (anchor.y - initialY) / scale
            var originX = initialX
            var originY = initialY
            var scrolls = 0
            var reads = 0
            while (true) {
                // A fresh visible-point fit after every actual (possibly shortened) finger travel.
                val features = (1..23).flatMap { x -> (1..11).map { y ->
                    ImageFeatureMatch(x * 100.0, y * 100.0, originX + scale * x * 100, originY + scale * y * 100)
                } }.filter { it.screenX in 0.0..width.toDouble() && it.screenY in 0.0..height.toDouble() }
                val fit = assertNotNull(MapLocalizer.fit(features))
                reads++
                val x = fit.screenX(mapX)
                val y = fit.screenY(mapY)
                val drag = MapView.dragToward(x, y, width, height)
                if (drag == null) {
                    assertTrue(scrolls > 0, "unsafe initial point was tapped: $anchor width=$width")
                    assertTrue(reads > scrolls, "tap requires a fit after the last scroll")
                    assertTrue(MapView.isTappable(MapView.toScript(x) / 2.0, MapView.toScript(y) / 2.0, width, height))
                    break
                }
                assertFalse(MapView.isTappable(x, y, width, height))
                assertTrue(scrolls < 4, "point did not become safe: $anchor width=$width")
                val (start, end) = MapView.dragPath(drag, width, height)
                assertTrue(MapView.isTappable(start.x, start.y, width, height))
                assertTrue(MapView.isTappable(end.x, end.y, width, height))
                originX += end.x - start.x
                originY += end.y - start.y
                scrolls++
            }
        }
    }

    @Test fun `16 by 9 clipped and UI covered map spots scroll and refit before tap`() = moveAndRefit(1280)
    @Test fun `18 by 9 clipped and UI covered map spots scroll and refit before tap`() = moveAndRefit(1440)
    @Test fun `4 by 3 clipped and UI covered map spots scroll and refit before tap`() = moveAndRefit(960)
    @Test fun `partially clipped margins and nonfinite coordinates are not tappable`() {
        assertFalse(MapView.isTappable(11.0, 360.0, 1280, 720))
        assertNotNull(MapView.dragToward(11.0, 360.0, 1280, 720))
        assertFalse(MapView.isTappable(Double.NaN, 360.0, 1280, 720))
        assertFalse(MapView.isTappable(640.0, Double.POSITIVE_INFINITY, 1280, 720))
    }
}
