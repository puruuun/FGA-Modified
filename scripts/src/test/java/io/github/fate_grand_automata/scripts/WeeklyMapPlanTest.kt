package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

/**
 * Issue #91: the map move is planned on the spot point exactly as in beta.4; the icon point (issue #78) plans only when the
 * spot has no safe row. The final tap always goes through [MapView.resolveTap].
 */
class WeeklyMapPlanTest {
    private class Case(val width: Int, mapW: Int, val mapH: Int, val spotX: Double, val spotY: Double) {
        val scale = width / mapW.toDouble()
        val range = mapH * scale - 720
        val x = spotX * scale
        fun plan(origin: Double) = MapView.planOrigin(x, spotY * scale, (spotY - MapView.ICON_OFFSET_MAP) * scale, origin, range, width, 720)
        /** Drag with a 1:1 finger-to-map gain until the move ends; returns the final origin. */
        fun move(start: Double, target: Double): Double {
            val drag = VerticalMapDrag(target, width, 720)
            var origin = start
            repeat(16) {
                val d = drag.next(origin) ?: return origin
                origin = (origin + d.y).coerceIn(-range, 0.0)
            }
            fail("map movement safety limit")
        }
        fun tap(origin: Double) = assertNotNull(MapView.resolveTap(x, origin + (spotY - MapView.ICON_OFFSET_MAP) * scale, x, origin + spotY * scale, width, 720))
    }

    // KR Fuyuki (war 100) X-E, quest 93000005: Atlas map 1920x1240, spot (1618, 766), on the 1680x720 wide capture.
    private val fuyuki = Case(1680, 1920, 1240, 1618.0, 766.0)
    // KR Okeanos (war 103) 10352: Atlas map 1920x1240, spot (701, 1007), on the 1707x720 wide frame.
    private val okeanos = Case(1707, 1920, 1240, 701.0, 1007.0)

    @Test fun `Fuyuki X-E plans on the spot point and gets the beta 4 target`() {
        val plan = assertNotNull(fuyuki.plan(0.0))
        assertEquals("spot", plan.plan)
        // beta.4: MapView.targetOrigin(x, spotY * scaleY, ...), no icon offset.
        assertEquals(MapView.targetOrigin(fuyuki.x, 766.0 * fuyuki.scale, 0.0, fuyuki.range, 1680, 720), plan.target)
        assertEquals(-310.25, plan.target, 0.01)
        // The icon-only plan of beta.5 asked for a different, edge-side target and never converged on the device.
        val iconOnly = MapView.targetOrigin(fuyuki.x, (766.0 - MapView.ICON_OFFSET_MAP) * fuyuki.scale, 0.0, fuyuki.range, 1680, 720)
        assertNotEquals(plan.target, iconOnly)
        // The same target for every start where the spot is not already clear.
        for (start in listOf(0.0, -25.0)) assertEquals(plan.target, fuyuki.plan(start)?.target)
    }

    @Test fun `Okeanos 10352 from a mid-map start plans with the icon and reaches the bottom end`() {
        val start = -300.0
        assertNull(MapView.targetOrigin(okeanos.x, okeanos.spotY * okeanos.scale, start, okeanos.range, 1707, 720), "the spot can never leave the bubbles")
        val plan = assertNotNull(okeanos.plan(start))
        assertEquals("icon", plan.plan)
        assertEquals(-okeanos.range, plan.target, 1.0)
        val end = okeanos.move(start, plan.target)
        assertEquals(-okeanos.range, end, 2.0)
        val choice = okeanos.tap(end)
        assertEquals("icon", choice.kind)
        assertTrue(choice.point.y < 507, "above the bubbles: ${choice.point.y}")
    }

    @Test fun `Okeanos 10352 starting at the bottom end taps the icon`() {
        val start = -okeanos.range
        val plan = assertNotNull(okeanos.plan(start))
        assertEquals("icon", plan.plan)
        assertNull(VerticalMapDrag(plan.target, 1707, 720).next(start), "already there: no drag")
        val choice = okeanos.tap(start)
        assertEquals("icon", choice.kind)
        assertTrue(MapView.isTappable(choice.point.x, choice.point.y, 1707, 720))
    }

    @Test fun `placeholder maps search the spot point first and the icon only when it finds nothing`() {
        val shin = WarMap(303, "", 2048, 1440)
        val geometry = assertNotNull(PlaceholderMap.geometry(shin, 1280, 720))
        // Ordinary spot: a spot-plan target, same as before the icon change.
        assertEquals("spot", assertNotNull(geometry.target(736.0, 341.0)).plan)
    }
}
