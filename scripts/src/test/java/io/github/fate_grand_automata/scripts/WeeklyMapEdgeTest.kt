package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.math.abs
import kotlin.test.*

class WeeklyMapEdgeTest {
    @Test fun `Traum 93031211 at 1083 696 reaches safe target with half distance gestures`() {
        // Owner beta.7 log: initial edge request -36 moved only -18; the map later reaches -179.
        val target = assertNotNull(MapView.targetOrigin(1083.0, 696.0, 0.0, 180.0, 1280, 720))
        assertTrue(target in -180.0..0.0)
        val controller = VerticalMapDrag(target, 1280, 720)
        var origin = 0.0
        var drags = 0
        while (true) {
            val drag = controller.next(origin) ?: break
            assertEquals(0.0, drag.x)
            val (from, to) = MapView.dragPath(drag, 1280, 720)
            assertEquals(from.x, to.x)
            origin = (origin + (to.y - from.y) * 0.5).coerceIn(-180.0, 0.0)
            assertTrue(++drags < 10)
        }
        assertEquals(0.5, controller.ratio, 0.001)
        assertTrue(abs(target - origin) <= 2)
        assertTrue(MapView.isTappable(1083.0, 696 + origin, 1280, 720))
        assertFalse(controller.stationary)
    }

    @Test fun `targets stay in computed vertical bounds and horizontal impossibilities stay unreachable`() {
        for (width in listOf(960, 1280, 1680)) {
            assertNull(MapView.targetOrigin(11.0, 360.0, 0.0, 180.0, width, 720))
            assertNull(MapView.targetOrigin(width - 11.0, 360.0, 0.0, 180.0, width, 720))
            assertNull(MapView.targetOrigin(width / 2.0, 1000.0, 0.0, 100.0, width, 720))
            val target = assertNotNull(MapView.targetOrigin(width / 2.0, 690.0, 0.0, 180.0, width, 720))
            assertTrue(target in -180.0..0.0)
            assertTrue(MapView.isTappable(width / 2.0, 690 + target, width, 720))
        }
        assertEquals(0.0, MapView.targetOrigin(640.0, 19.5, 0.0, 0.0, 1280, 720))
        assertNull(MapView.targetOrigin(Double.NaN, 500.0, 0.0, 180.0, 1280, 720))
    }

    @Test fun `small actual movement is compensated and only a stationary origin stops the controller`() {
        val controller = VerticalMapDrag(-20.0, 1280, 720)
        assertNotNull(controller.next(0.0))
        assertNotNull(controller.next(-2.0)) // Old four-pixel motion floor must not stop progress.
        assertFalse(controller.stationary)
        assertNull(controller.next(-2.0))
        assertTrue(controller.stationary)
    }

    @Test fun `UI avoidance and edge drags never contain a horizontal component`() {
        for (width in listOf(960, 1280, 1680)) for (x in listOf(100.0, width - 100.0)) {
            assertEquals(0.0, assertNotNull(MapView.dragToward(x, 650.0, width, 720)).x)
            assertEquals(0.0, assertNotNull(MapView.edgeDrag(x, 700.0, width, 720)).x)
            val path = MapView.dragPath(ScreenPoint(500.0, 180.0), width, 720)
            assertEquals(path.first.x, path.second.x)
        }
    }
    @Test fun `relative map fits measure half gesture displacement and distinguish a stationary border`() {
        for (delta in listOf(-134.0, 0.0, 90.0)) {
            val features = (1..8).flatMap { x -> (1..5).map { y ->
                io.github.lib_automata.ImageFeatureMatch(x * 60.0, y * 40.0, x * 60.0, y * 40.0 + delta)
            } }
            val fit = assertNotNull(MapLocalizer.fit(features, 0.98..1.02))
            assertEquals(delta, fit.originY, 0.001)
            assertEquals(0.0, fit.originX, 0.001)
        }
        assertEquals(ProbeStatus.MapFitFailed, ProbeStatus.classify("map movement safety limit"))
    }
    @Test fun `reference-free map gestures keep feature overlap and compensate measured gain`() {
        val control = VerticalMapDrag(-170.0, 1280, 720, initialRatio = 0.5, maxTravel = 120.0)
        var origin = 0.0
        repeat(10) {
            val drag = control.next(origin) ?: return@repeat
            assertEquals(0.0, drag.x)
            assertTrue(kotlin.math.abs(drag.y) <= 120)
            origin = (origin + drag.y * 0.5).coerceAtLeast(-180.0)
        }
        assertEquals(-170.0, origin, 2.0)
    }
}
