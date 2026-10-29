package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

/** Issue #78: tap the spot icon, never a point under the mission or limited-time bubble. */
class WeeklyIconTapTest {
    // KR Okeanos (war 103) 1920x1240 map on the 1707x720 wide frame, scrolled to its bottom end.
    private val scale = 1707.0 / 1920.0
    private val origin = -(1240 * scale - 720)
    private fun sx(x: Double) = x * scale
    private fun sy(y: Double) = origin + y * scale

    @Test fun `spot 10352 at the map bottom resolves to the icon above both bubbles`() {
        val x = sx(701.0)
        val spotY = sy(1007.0)
        val iconY = sy(1007.0 - MapView.ICON_OFFSET_MAP)
        assertFalse(MapView.isTappable(x, spotY, 1707, 720), "old spot point is under the bubble")
        val choice = assertNotNull(MapView.resolveTap(x, iconY, x, spotY, 1707, 720))
        assertEquals("icon", choice.kind)
        assertTrue(MapView.isTappable(choice.point.x, choice.point.y, 1707, 720))
        // Measured bubbles at 720p: mission x587..691 y>=519; limited-time x476..580 y>=529. Icon at about y 473..520.
        assertTrue(choice.point.y < 507 && choice.point.y in 473.0..520.0, "y=${choice.point.y}")
        assertFalse(choice.point.x in 587.0..691.0 && choice.point.y >= 519.0)
    }

    @Test fun `bubble zones cover both bubbles on wide and on 16-9`() {
        for ((x, y) in listOf(630.0 to 530.0, 640.0 to 565.0, 530.0 to 535.0, 690.0 to 540.0)) assertFalse(MapView.isTappable(x, y, 1707, 720), "wide $x,$y")
        for ((x, y) in listOf(430.0 to 575.0, 540.0 to 565.0, 590.0 to 580.0)) assertFalse(MapView.isTappable(x, y, 1280, 720), "16:9 $x,$y")
        assertTrue(MapView.isTappable(630.0, 490.0, 1707, 720))
        assertTrue(MapView.isTappable(540.0, 530.0, 1280, 720))
    }

    @Test fun `an ordinary spot resolves to its icon centre`() {
        val choice = assertNotNull(MapView.resolveTap(640.0, 300.0 - MapView.ICON_OFFSET_MAP * 0.667, 640.0, 300.0, 1280, 720))
        assertEquals("icon", choice.kind)
        assertEquals(300.0 - MapView.ICON_OFFSET_MAP * 0.667, choice.point.y, 1e-9)
    }

    @Test fun `covered icon falls back to its visible part then to a clear spot point then to null`() {
        // Icon just inside the gift-box zone at the bottom: shifted up.
        val shifted = assertNotNull(MapView.resolveTap(430.0, 560.0, 430.0, 600.0, 1280, 720))
        assertEquals("icon-shifted", shifted.kind)
        assertTrue(MapView.isTappable(shifted.point.x, shifted.point.y, 1280, 720))
        // Icon far inside a zone (no visible part within the shift): clear spot point is used.
        val spot = assertNotNull(MapView.resolveTap(100.0, 700.0, 640.0, 360.0, 1280, 720))
        assertEquals("spot", spot.kind)
        assertEquals(ScreenPoint(640.0, 360.0), spot.point)
        // Everything covered: nothing is tapped.
        assertNull(MapView.resolveTap(100.0, 700.0, 100.0, 710.0, 1280, 720))
    }
}
