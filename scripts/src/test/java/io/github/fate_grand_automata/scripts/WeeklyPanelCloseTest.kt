package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyPanelCloseTest {
    @Test fun `missed taps stop immediately when map arrives on attempt one two or three`() {
        for (success in 1..3) {
            var taps = 0
            var waits = 0
            assertTrue(WeeklyPanelClose.run({ taps++; true }, { ++waits == success },
                { fail("unnecessary diagnostic") }, { fail("unnecessary recovery") }))
            assertEquals(success, taps)
            assertEquals(success, waits)
        }
    }

    @Test fun `three failed taps save diagnostic before exactly one recovery`() {
        for (recovered in listOf(true, false)) {
            val events = mutableListOf<String>()
            assertEquals(recovered, WeeklyPanelClose.run(
                { events += "tap"; true }, { events += "wait"; false },
                { events += "png" }, { events += "recover"; recovered }))
            assertEquals(listOf("tap", "wait", "tap", "wait", "tap", "wait", "png", "recover"), events)
        }
    }

    @Test fun `unknown screen never taps but still records and attempts recovery`() {
        var recoveries = 0
        var diagnostics = 0
        assertFalse(WeeklyPanelClose.run({ false }, { fail("unsafe wait") }, { diagnostics++ }, { recoveries++; false }))
        assertEquals(1, diagnostics)
        assertEquals(1, recoveries)
    }

    @Test fun `93031216 image map at origin minus177 and y19 scrolls before tapping`() {
        val fit = MapFit(1.0, 0.625, 0.625, 0.0, -177.0, 100, 1.0, 1152.0, 720.0)
        val spotY = (19.0 - fit.originY) / fit.scaleY
        assertEquals(19.0, fit.screenY(spotY))
        assertTrue(MapView.isTappable(500.0, 19.0, 1152, 720))
        val drag = assertNotNull(MapView.edgeDrag(500.0, fit.screenY(spotY), 1152, 720))
        assertEquals(41.0, drag.y)
        assertEquals(MapView.ScrollResult.Refit, MapView.afterScroll(ScreenPoint(0.0, -177.0), ScreenPoint(0.0, -136.0), false, true))
        assertNull(MapView.edgeDrag(500.0, 60.0, 1152, 720))
        assertEquals(MapView.ScrollResult.Stop, MapView.afterScroll(ScreenPoint(0.0, 0.0), ScreenPoint(0.0, 0.0), false, true))
    }

    @Test fun `geometry uses origin range to move bottom edge spot inward`() {
        val geometry = PlaceholderMap.Geometry(2048, 1440, 1280, 720)
        val target = assertNotNull(geometry.target(1000.0, 1130.0))
        assertEquals(PlaceholderMap.Edge.Top, target.edge)
        assertEquals(660.0, target.point.y)
        assertTrue(target.fit.originY in -geometry.range..0.0)
        assertTrue(target.fit.originY < 0)
    }
}
