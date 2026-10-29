package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.MapView
import io.github.fate_grand_automata.scripts.weekly.PlaceholderMap
import io.github.fate_grand_automata.scripts.weekly.WarMap
import kotlin.math.abs
import kotlin.test.*

/** Design 0009 D-31: Shin (war 303) has no Atlas map picture; its spots are placed from the display-map geometry. */
class WeeklyPlaceholderMapTest {
    private val shin = WarMap(30301, "", 2048, 1440)
    private val geometry = assertNotNull(PlaceholderMap.geometry(shin, 1280, 720))

    /** Spot icons as owner frame 30 (beta.8, map at the top border) shows them, 720p, within the few pixels an icon centre can be read to. */
    private val frame30 = mapOf(
        "咸陽" to (736.0 to 341.0 to (460.0 to 212.0)),
        "大坪峪" to (515.0 to 587.0 to (322.0 to 362.0)),
        "石泉峡" to (536.0 to 795.0 to (335.0 to 495.0)),
        "山陽丘" to (1087.0 to 702.0 to (679.0 to 437.0)),
        "景陽原" to (1225.0 to 811.0 to (766.0 to 507.0)),
        "八門洞" to (1370.0 to 505.0 to (856.0 to 315.0)),
        "シーディングポイント" to (1322.0 to 933.0 to (826.0 to 582.0)),
        "芥の陣幕" to (1535.0 to 968.0 to (960.0 to 603.0)),
        "氷冷窟" to (953.0 to 1047.0 to (596.0 to 652.0)),
        "隣村" to (1173.0 to 1091.0 to (733.0 to 681.0))
    )

    @Test fun `the zoomed out Shin map is the screen width and spots land on the frame 30 icons`() {
        assertEquals(0.625, geometry.scale)
        assertEquals(180.0, geometry.range)
        val top = geometry.fit(PlaceholderMap.Edge.Top)
        for ((name, v) in frame30) {
            val (map, icon) = v
            val dx = top.screenX(map.first) - icon.first
            val dy = top.screenY(map.second) - icon.second
            assertTrue(abs(dx) <= 12 && abs(dy) <= 12, "$name off by $dx,$dy")
        }
    }

    @Test fun `a spot behind the gift box at the top border is placed on the bottom border instead`() {
        val top = assertNotNull(geometry.target(736.0, 341.0))
        assertEquals(PlaceholderMap.Edge.Top, top.edge)
        val cold = assertNotNull(geometry.target(953.0, 1047.0)) // top border: (596, 654) lies in the gift box zone
        assertEquals(PlaceholderMap.Edge.Bottom, cold.edge)
        assertTrue(MapView.isTappable(cold.point.x, cold.point.y, 1280, 720))
        assertEquals(953.0 * 0.625, cold.point.x, 0.001)
        assertEquals((1047.0 - MapView.ICON_OFFSET_MAP) * 0.625 - 180, cold.point.y, 0.001) // the icon centre, not the spot point
    }

    @Test fun `every Shin free quest spot gets a tap point`() {
        val spots = listOf(1173 to 1091, 953 to 1047, 1535 to 968, 1225 to 811, 1087 to 702, 751 to 932, 536 to 795, 515 to 587, 736 to 341, 1322 to 933, 1370 to 505)
        for ((x, y) in spots) {
            val target = assertNotNull(geometry.target(x.toDouble(), y.toDouble()), "spot $x,$y")
            assertTrue(MapView.isTappable(target.point.x, target.point.y, 1280, 720))
        }
    }

    @Test fun `drags are enough to cross the range and unsupported shapes are refused`() {
        assertEquals(2, geometry.drags(268.0))
        assertNull(PlaceholderMap.geometry(WarMap(1, "", 4000, 1000), 1280, 720), "height limited map")
        assertNull(PlaceholderMap.geometry(WarMap(1, "", 0, 0), 1280, 720))
        assertNull(geometry.target(Double.NaN, 3.0))
    }

    @Test fun `placeholder textures are told apart from maps`() {
        assertTrue(PlaceholderMap.isPlaceholder(8, 8))
        assertTrue(PlaceholderMap.isPlaceholder(64, 2048))
        assertFalse(PlaceholderMap.isPlaceholder(2048, 1440))
    }
}
