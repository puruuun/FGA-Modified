package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

/**
 * Issue #102: every weekly spot of the main-story chapters (JP and KR Atlas exports, `weekly/map-spots.csv`) on every phone
 * ratio from 16:9 to 22:9 and three start origins, driven through the real locate loop with an ideal map (a drag moves the
 * origin by the drag and clamps to the range). A new failure here means a spot the route cannot tap on some phone.
 */
class WeeklyMapSimulationTest {
    private class Spot(val warId: Int, val mapId: Int, val mapW: Int, val mapH: Int, val spotId: Int, val x: Double, val y: Double)

    private class Failure(val ratio: Int, val spot: Spot, val start: String, val why: String)

    private val widths = listOf(1280, 1440, 1560, 1600, 1707, 1760)

    /** Spots that truly cannot be tapped on a ratio, keyed "width/warId/spotId". Keep empty; every entry needs a reason. */
    private val allowList = emptyMap<String, String>()

    private fun spots(): List<Spot> = checkNotNull(javaClass.getResource("/weekly/map-spots.csv")).readText().lines().drop(1)
        .filter { it.isNotBlank() }.map { it.trim().split(',') }
        .map { Spot(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toDouble(), it[6].toDouble()) }

    /** One driver reused for every case: the mocked screen follows these fields. */
    private inner class Harness {
        var scale = 1.0
        var width = 1280
        var mapHeight = 1240
        var range = 0.0
        var origin = 0.0
        var drags = 0
        val logs = mutableListOf<String>()
        private val map = mockk<Pattern>(relaxed = true)
        private val driver: WeeklyRouteDriver
        private val api = mockk<IFgoAutomataApi>(relaxed = true)
        private val frame = mockk<Pattern>(relaxed = true)
        private val swipe = mockk<Swiper>(relaxed = true)
        private val diagnostics = mockk<IWeeklyPanelDiagnostics>(relaxed = true)
        private var quest = RouteQuest(1, 100, 0, "", "", "", 3, "free", "repeatLast")

        init {
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            every { frame.width } answers { width }
            every { frame.height } returns 720
            every { with(api) { any<Region>().getPattern(any()) } } returns frame
            every { map.height } answers { mapHeight }
            every { map.featureMatches(any(), any()) } answers {
                (0 until 40).map { i ->
                    val mx = 300.0 + (i % 8) * 150
                    val my = 500.0 + (i / 8) * 100
                    ImageFeatureMatch(mx, my, mx * scale, origin + my * scale)
                }
            }
            every { swipe.scroll(any(), any()) } answers {
                origin = (origin + (secondArg<Location>().y - firstArg<Location>().y) / 2.0).coerceIn(-range, 0.0)
                drags++
            }
            every { diagnostics.mapLog(any()) } answers { logs += firstArg<String>() }
            driver = WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                swipe, mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
                diagnostics, mockk(relaxed = true))
        }

        private val locate = WeeklyRouteDriver::class.java.getDeclaredMethod("locateSpot", RouteQuest::class.java, Pattern::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }

        /** Runs one case; returns the tap in 720p, or the failure reason. */
        fun run(w: Int, mapW: Int, mapH: Int, spotX: Double, spotY: Double, start: Double): Pair<ScreenPoint?, String> {
            width = w; scale = w / mapW.toDouble(); mapHeight = mapH
            range = (mapH * scale - 720).coerceAtLeast(0.0)
            origin = start.coerceIn(-range, 0.0)
            drags = 0
            logs.clear()
            // Thousands of cases: do not let the mocks keep every recorded call (keeps the stubbing).
            clearMocks(api, frame, map, swipe, diagnostics, answers = false, recordedCalls = true, childMocks = true)
            quest = RouteQuest(1, 100, 0, "", "", "", 3, "free", "repeatLast", spotX = spotX, spotY = spotY)
            @Suppress("UNCHECKED_CAST")
            val (loc, why) = locate.invoke(driver, quest, map, w, 720, false, true) as Pair<Location?, String>
            if (loc == null) return null to why
            val earlyBad = logs.firstOrNull { it.startsWith("tap point ") && it.contains("reason=early") && !it.contains("kind=icon ") }
            if (earlyBad != null) return null to "early tap not on a clear icon: $earlyBad"
            return ScreenPoint(loc.x / 2.0, loc.y / 2.0) to "ok"
        }
    }

    private fun check(h: Harness, w: Int, s: Spot, start: Double): String? {
        val (p, why) = h.run(w, s.mapW, s.mapH, s.x, s.y, start)
        if (p == null) return why
        if (!MapView.isTappable(p.x, p.y, w, 720)) return "not tappable at ${p.x},${p.y}"
        val c = MapView.CLEARANCE
        for ((dx, dy) in listOf(-c to 0.0, c to 0.0, 0.0 to -c, 0.0 to c))
            if (MapView.isCovered(p.x + dx, p.y + dy, w, 720)) return "within clearance of a zone at ${p.x},${p.y}"
        if (h.drags > WeeklyRouteDriver.MAX_MAP_SCROLLS) return "scrolls ${h.drags}"
        return null
    }

    private fun starts(range: Double) = listOf("top" to 0.0, "middle" to -range / 2, "bottom" to -range)

    @Test fun `every weekly spot is tappable on every phone ratio from every start origin`() {
        val all = spots()
        assertTrue(all.size > 200, "spot data loaded: ${all.size}")
        val h = Harness()
        val failures = mutableListOf<Failure>()
        val summary = StringBuilder("map simulation: ${all.size} spots x ${widths.size} ratios x 3 starts\n")
        for (w in widths) {
            var cases = 0
            var bad = 0
            for (s in all) {
                val range = (s.mapH * w / s.mapW.toDouble() - 720).coerceAtLeast(0.0)
                for ((name, start) in starts(range)) {
                    cases++
                    val why = check(h, w, s, start) ?: continue
                    if ("$w/${s.warId}/${s.spotId}" in allowList) continue
                    bad++
                    failures += Failure(w, s, name, why)
                }
            }
            summary.append("  ${w}x720: cases=$cases failures=$bad\n")
        }
        println(summary)
        assertTrue(failures.isEmpty(), failures.take(40).joinToString("\n") {
            "${it.ratio} war=${it.spot.warId} map=${it.spot.mapId} spot=${it.spot.spotId} (${it.spot.x},${it.spot.y}) start=${it.start}: ${it.why}"
        } + "\n${failures.size} failures\n$summary")
    }

    @Test fun `the zoom scale is the width over the map width`() {
        assertEquals(0.667, 1280 / 1920.0, 0.001)
        assertEquals(0.889, 1707 / 1920.0, 0.001)
    }

    @Test fun `tablet 16 to 9 Fuyuki X-G knife edge is tapped from every start`() {
        // Measured on the tablet: x 243, spot y 589, icon y 572 at origin -2, range 107 (map 1920x1240, scale 0.667).
        val s = Spot(100, 100, 1920, 1240, 0, 243.0 / (1280 / 1920.0), 591.0 / (1280 / 1920.0))
        val h = Harness()
        for (start in listOf(-2.0, 0.0, -50.0, -107.0)) assertNull(check(h, 1280, s, start), "start $start")
    }

    @Test fun `Okeanos 10352 on 16 to 9 is tapped from every start`() {
        val s = Spot(103, 103, 1920, 1240, 0, 701.0, 1007.0)
        val h = Harness()
        for (start in listOf(0.0, -50.0, -107.0)) assertNull(check(h, 1280, s, start), "start $start")
        assertEquals(0.667, h.scale, 0.001)
    }
}

class WeeklyProbeReadTest {
    @Test fun `a mismatching panel keeps what the OCR read for the probe CSV`() {
        val failure = WeeklyPanelFailure()
        failure.panelRead("집터?", "미확인좌표")
        val outcome = SpotOutcome.NotFound(failure.reason("x"), read = failure.read)
        val quest = RouteQuest(1, 100, 0, "c", "s", "q", 3, "free", "repeatLast")
        val csv = WeeklyProbe.csv(listOf(ProbeResult(quest, ProbeStatus.Mismatch, outcome.reason, 5, outcome.read)))
        assertTrue(csv.trim().endsWith("집터?,미확인좌표"), csv)
    }
}
