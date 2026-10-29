package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.math.abs
import kotlin.test.*

/** The locate flow of issue #111 with a constant finger-to-map gain: when it taps early, when it keeps moving, when it gives up. */
class WeeklyMapEarlyTapTest {
    private class Frame(val origin: Double, delegate: Pattern) : Pattern by delegate {
        override val width = 1000
        override val height = 300
        override fun featureMatches(scene: Pattern, sceneRegion: Region): List<ImageFeatureMatch> {
            val delta = (scene as Frame).origin - origin
            return (0 until 40).map { i ->
                val mx = 300.0 + (i % 8) * 150
                val my = 500.0 + (i / 8) * 100
                ImageFeatureMatch(mx, my, mx, my + delta)
            }
        }
    }

    /** The map starts at [start] (the flow pushes it to an end first); every drag moves it by [gain] of the request. */
    private class Fixture(
        val width: Int = 1280,
        val mapHeight: Int = 1800,
        spotX: Double = 960.0,
        val spotY: Double = 1150.0,
        start: Double = 0.0,
        gain: Double = 0.5,
        mapWidth: Int = 1920
    ) {
        val scale = width / mapWidth.toDouble()
        val range = mapHeight * scale - 720
        val x = spotX * scale
        var origin = start
        val logs = mutableListOf<String>()
        private val map = WarMap(1, "", mapWidth, mapHeight)
        private val driver: WeeklyRouteDriver
        private val quest = RouteQuest(96, 100, 0, "chapter", "spot", "quest", 3, "free", "repeatLast",
            spotX = spotX, spotY = spotY)

        /** Measured steps: the drags that follow the push to the end. */
        val steps get() = logs.count { it.startsWith("step quest=") && it.contains("drag=") }

        init {
            val api = mockk<IFgoAutomataApi>(relaxed = true)
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            val blank = mockk<Pattern>(relaxed = true)
            every { with(api) { any<Region>().getPattern(any()) } } answers { Frame(origin, blank) }
            val swipe = mockk<Swiper>(relaxed = true)
            every { swipe.scroll(any(), any()) } answers {
                val delta = (secondArg<Location>().y - firstArg<Location>().y) / 2.0
                origin = (origin + delta * gain).coerceIn(-range, 0.0)
            }
            val diagnostics = mockk<IWeeklyPanelDiagnostics>(relaxed = true)
            every { diagnostics.mapLog(any()) } answers { logs += firstArg<String>() }
            driver = WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                swipe, mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
                diagnostics, mockk(relaxed = true))
        }

        // Keep the locator private: the fixture supplies the measurements, while the production loop decides when to stop.
        @Suppress("UNCHECKED_CAST")
        fun locate(centre: Boolean = false, edgeFirst: Boolean = true): Pair<Location?, String> =
            WeeklyRouteDriver::class.java.getDeclaredMethod("locateSpot", RouteQuest::class.java, WarMap::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(driver, quest, map, width, 720, centre, edgeFirst) as Pair<Location?, String>

        /** The tap is the one MapView resolves at the true origin (the gain is constant and the measurements exact). */
        fun assertTap(result: Pair<Location?, String>, reason: String): String {
            assertEquals("ok", result.second)
            val point = assertNotNull(result.first)
            val choice = assertNotNull(MapView.resolveTap(x, origin + (spotY - MapView.ICON_OFFSET_MAP) * scale,
                x, origin + spotY * scale, width, 720))
            assertEquals(Location(MapView.toScript(choice.point.x), MapView.toScript(choice.point.y)), point)
            return logs.single { it.startsWith("tap point ") }.also { assertTrue(it.endsWith("reason=$reason"), it) }
        }
    }

    @Test fun `a spot in the tappable band at the top end is tapped without a measured step`() {
        val f = Fixture(spotY = 500.0, start = -200.0)
        f.assertTap(f.locate(), "early")
        assertEquals(0, f.steps)
        assertTrue(f.logs.single { it.startsWith("edge quest=") }.contains("edge=Top"))
    }

    @Test fun `a middle spot starts from the nearer end and moves in measured steps`() {
        // Map 1920x3000 at 1280: scale 0.667, range 1280. Spot row 1700 is off the screen at both ends and nearer the bottom end.
        val f = Fixture(mapHeight = 3000, spotY = 1700.0, start = -600.0)
        f.assertTap(f.locate(), "early")
        assertTrue(f.logs.single { it.startsWith("edge quest=") }.contains("edge=Bottom"))
        assertTrue(f.steps in 1..WeeklyRouteDriver.MAX_MAP_SCROLLS, "steps ${f.steps}")
    }

    @Test fun `centre retry keeps moving until its original target converges`() {
        val f = Fixture(mapHeight = 3000, spotY = 1700.0)
        // The target is planned from the end the flow starts at (the bottom one for this spot).
        val target = assertNotNull(MapView.planOrigin(f.x, f.spotY * f.scale,
            (f.spotY - MapView.ICON_OFFSET_MAP) * f.scale, -f.range, f.range, f.width, 720, centre = true)).target
        f.assertTap(f.locate(centre = true), "converged")
        assertTrue(f.steps >= 1)
        assertEquals(target, f.origin, 2.0)
    }

    @Test fun `clear tap inside the top edge margin keeps moving only while the edge-first rule asks for it`() {
        // At the top end the icon of this spot is clear but less than 60 px from the top and the map cannot move further.
        val f = Fixture(spotY = 90.0, start = -400.0)
        f.assertTap(f.locate(), "converged")
        assertEquals(0, f.steps)
    }

    @Test fun `edge recovery can tap a clear point without the edge-first margin`() {
        val f = Fixture(spotY = 90.0, start = -400.0)
        f.assertTap(f.locate(edgeFirst = false), "early")
        assertEquals(0, f.steps)
    }

    @Test fun `Fuyuki X-E taps with the spot plan`() {
        val f = Fixture(width = 1680, mapHeight = 1240, spotX = 1618.0, spotY = 766.0)
        val result = f.locate()
        assertEquals("ok", result.second)
        assertTrue(f.logs.single { it.startsWith("tap point ") }.contains("plan=spot"))
    }

    @Test fun `Okeanos 10352 taps the icon from any start`() {
        for (start in listOf(-300.0, -(1240 * (1707 / 1920.0) - 720), 0.0)) {
            val f = Fixture(width = 1707, mapHeight = 1240, spotX = 701.0, spotY = 1007.0, start = start)
            val result = f.locate()
            assertEquals("ok", result.second)
            val tap = f.logs.single { it.startsWith("tap point ") }
            assertTrue(tap.contains("kind=icon"), tap)
        }
    }

    @Test fun `a map that never reaches an end stops with no tap`() {
        val f = Fixture(gain = 0.01)
        val result = f.locate()
        assertNull(result.first)
        assertEquals("map movement did not converge", result.second)
        assertTrue(f.logs.none { it.startsWith("tap point ") })
        assertTrue(abs(f.origin) >= 0.0)
    }
}
