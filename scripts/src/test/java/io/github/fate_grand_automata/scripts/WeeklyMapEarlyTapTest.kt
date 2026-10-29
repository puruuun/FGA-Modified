package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.math.abs
import kotlin.test.*

class WeeklyMapEarlyTapTest {
    /** Exercise the product locate loop with fresh feature matches after each simulated, half-distance drag. */
    private class Fixture(
        val width: Int = 1280,
        mapHeight: Int = 1800,
        spotX: Double = 960.0,
        val spotY: Double = 1150.0,
        start: Double = 0.0,
        gain: Double = 0.5
    ) {
        val scale = width / 1920.0
        val range = mapHeight * scale - 720
        val x = spotX * scale
        var origin = start
        var drags = 0
        val logs = mutableListOf<String>()
        private val map = mockk<Pattern>(relaxed = true)
        private val driver: WeeklyRouteDriver
        private val quest = RouteQuest(96, 100, 0, "chapter", "spot", "quest", 3, "free", "repeatLast",
            spotX = spotX, spotY = spotY)

        init {
            val api = mockk<IFgoAutomataApi>(relaxed = true)
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            val frame = mockk<Pattern>(relaxed = true)
            every { frame.width } returns width
            every { frame.height } returns 720
            every { with(api) { any<Region>().getPattern(any()) } } returns frame
            every { map.height } returns mapHeight
            every { map.featureMatches(any(), any()) } answers {
                (0 until 40).map { i ->
                    val mx = 300.0 + (i % 8) * 150
                    val my = 500.0 + (i / 8) * 100
                    ImageFeatureMatch(mx, my, mx * scale, origin + my * scale)
                }
            }
            val swipe = mockk<Swiper>(relaxed = true)
            every { swipe.scroll(any(), any()) } answers {
                val delta = (secondArg<Location>().y - firstArg<Location>().y) / 2.0
                origin = (origin + delta * gain).coerceIn(-range, 0.0)
                drags++
            }
            val diagnostics = mockk<IWeeklyPanelDiagnostics>(relaxed = true)
            every { diagnostics.mapLog(any()) } answers { logs += firstArg<String>() }
            driver = WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                swipe, mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
                diagnostics, mockk(relaxed = true))
        }

        // Keep the locator private: the fixture supplies localization evidence, while the production loop decides when to stop.
        @Suppress("UNCHECKED_CAST")
        fun locate(centre: Boolean = false, edgeFirst: Boolean = true): Pair<Location?, String> =
            WeeklyRouteDriver::class.java.getDeclaredMethod("locateSpot", RouteQuest::class.java, Pattern::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(driver, quest, map, width, 720, centre, edgeFirst) as Pair<Location?, String>

        fun assertTap(result: Pair<Location?, String>, reason: String): String {
            assertEquals("ok", result.second)
            val point = assertNotNull(result.first)
            val choice = assertNotNull(MapView.resolveTap(x, origin + (spotY - MapView.ICON_OFFSET_MAP) * scale,
                x, origin + spotY * scale, width, 720))
            assertEquals(Location(MapView.toScript(choice.point.x), MapView.toScript(choice.point.y)), point)
            return logs.single { it.startsWith("tap point ") }.also { assertTrue(it.endsWith("reason=$reason"), it) }
        }
    }

    @Test fun `first drag into the tappable band stops without correction drags`() {
        val f = Fixture()
        val target = assertNotNull(MapView.planOrigin(f.x, f.spotY * f.scale,
            (f.spotY - MapView.ICON_OFFSET_MAP) * f.scale, f.origin, f.range, f.width, 720)).target
        f.assertTap(f.locate(), "early")
        assertEquals(1, f.drags)
        assertTrue(abs(target - f.origin) > 2, "must tap before the origin target converges")
    }

    @Test fun `centre retry keeps moving until its original target converges`() {
        val f = Fixture()
        val target = assertNotNull(MapView.planOrigin(f.x, f.spotY * f.scale,
            (f.spotY - MapView.ICON_OFFSET_MAP) * f.scale, f.origin, f.range, f.width, 720, centre = true)).target
        f.assertTap(f.locate(centre = true), "converged")
        assertTrue(f.drags > 1)
        assertEquals(target, f.origin, 2.0)
    }

    @Test fun `clear tap inside the top edge margin keeps moving until the next fit enters the band`() {
        val f = Fixture(spotY = 650.0, start = -400.0, gain = 0.1)
        f.assertTap(f.locate(), "early")
        assertEquals(2, f.drags, "after the first drag the icon is clear but still less than 60 px from the top")
    }

    @Test fun `edge recovery can tap a clear point without the edge-first margin`() {
        val f = Fixture(spotY = 650.0, start = -400.0)
        f.assertTap(f.locate(edgeFirst = false), "early")
        assertEquals(0, f.drags)
    }

    @Test fun `already inward tappable point needs no drag`() {
        val f = Fixture(start = -200.0)
        f.assertTap(f.locate(), "early")
        assertEquals(0, f.drags)
    }

    @Test fun `Fuyuki X-E retains the beta 4 spot plan and taps after one drag`() {
        val f = Fixture(width = 1680, mapHeight = 1240, spotX = 1618.0, spotY = 766.0)
        assertTrue(f.assertTap(f.locate(), "early").contains("plan=spot"))
        assertEquals(1, f.drags)
        assertTrue(f.logs.single { it.startsWith("plan ") }.contains("target=-310"))
    }

    @Test fun `Okeanos 10352 retains the icon fallback from mid-map and the bottom end`() {
        for (start in listOf(-300.0, -(1240 * (1707 / 1920.0) - 720))) {
            val f = Fixture(width = 1707, mapHeight = 1240, spotX = 701.0, spotY = 1007.0, start = start)
            val tap = f.assertTap(f.locate(), "early")
            assertTrue(tap.contains("plan=icon"), tap)
            assertTrue(tap.contains("kind=icon"), tap)
            assertEquals(if (start == -300.0) 1 else 0, f.drags)
        }
    }

    @Test fun `unreachable movement still stops at the sixteen drag safety limit`() {
        val f = Fixture(gain = 0.01)
        val result = f.locate()
        assertNull(result.first)
        assertEquals("map movement safety limit", result.second)
        assertEquals(16, f.drags)
        assertTrue(f.logs.none { it.startsWith("tap point ") })
    }
}
