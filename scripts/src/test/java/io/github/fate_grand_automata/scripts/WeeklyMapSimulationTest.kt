package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.WeeklyRouteDriver
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

/**
 * Issue #111 (after #102): every weekly spot of the main-story chapters (JP and KR Atlas exports, `weekly/map-spots.csv`) on
 * every phone ratio from 16:9 to 22:9 and three start origins, driven through the real locate flow with no map picture.
 * The fake device moves the map by a random fraction (0.66..1.00, fixed seed) of every requested drag, clamps it at the
 * ends, and answers every frame-to-frame measurement with the true motion plus up to 2 px of noise. The zoom is the true
 * minimum scale. A new failure here means a spot the route cannot tap on some phone.
 */
class WeeklyMapSimulationTest {
    private class Spot(val warId: Int, val mapId: Int, val mapW: Int, val mapH: Int, val spotId: Int, val x: Double, val y: Double)

    private class Failure(val ratio: Int, val spot: Spot, val start: String, val why: String)

    private val widths = listOf(1152, 1280, 1440, 1560, 1600, 1680, 1707, 1760)

    /** Spots that truly cannot be tapped on a ratio, keyed "width/warId/spotId". Keep empty; every entry needs a reason. */
    private val allowList = emptyMap<String, String>()

    /** How far the origin the flow believes in may be from the true one at the tap (the 12 px ring of MapView.isTappable). */
    private val originTolerance = 12.0

    private fun spots(): List<Spot> = checkNotNull(javaClass.getResource("/weekly/map-spots.csv")).readText().lines().drop(1)
        .filter { it.isNotBlank() }.map { it.trim().split(',') }
        .map { Spot(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toDouble(), it[6].toDouble()) }

    /** A frame that remembers the true origin it was taken at; everything else is the relaxed mock. */
    private class Frame(val origin: Double, val noise: () -> Double, val failing: () -> Boolean, delegate: Pattern) : Pattern by delegate {
        override val width = 1000
        override val height = 300
        override fun featureMatches(scene: Pattern, sceneRegion: Region): List<ImageFeatureMatch> {
            if (failing()) return emptyList()
            val delta = (scene as Frame).origin - origin + noise()
            return (0 until 40).map { i ->
                val mx = 300.0 + (i % 8) * 150
                val my = 500.0 + (i / 8) * 100
                ImageFeatureMatch(mx, my, mx, my + delta)
            }
        }
    }

    private class Outcome(val tap: ScreenPoint?, val why: String, val estimatedError: Double, val measuredSteps: Int)

    /** One driver reused for every case: the fake screen follows these fields. */
    private inner class Harness {
        var range = 0.0
        var origin = 0.0
        var drags = 0
        /** Measurement numbers (1-based, per case) that find no features at all. */
        var failMeasurements = emptySet<Int>()
        val logs = mutableListOf<String>()
        private val random = kotlin.random.Random(111)
        private val api = mockk<IFgoAutomataApi>(relaxed = true)
        private val swipe = mockk<Swiper>(relaxed = true)
        private val diagnostics = mockk<IWeeklyPanelDiagnostics>(relaxed = true)
        private val blank = mockk<Pattern>(relaxed = true)
        private val driver: WeeklyRouteDriver
        private var frames = 0

        private fun stubFrames() {
            every { with(api) { any<Region>().getPattern(any()) } } answers {
                // Frames come in pairs (before, after); the pair's number is the measurement number.
                frames++
                val number = (frames + 1) / 2
                Frame(origin, { random.nextDouble(-2.0, 2.0) }, { number in failMeasurements }, blank)
            }
        }

        init {
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>()() }
            stubFrames()
            every { swipe.scroll(any(), any()) } answers {
                val gain = random.nextDouble(0.66, 1.0)
                origin = (origin + gain * (secondArg<Location>().y - firstArg<Location>().y) / 2.0).coerceIn(-range, 0.0)
                drags++
            }
            every { diagnostics.mapLog(any()) } answers { logs += firstArg<String>() }
            driver = WeeklyRouteDriver(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                swipe, mockk(relaxed = true), BattleState(), mockk(relaxed = true), mockk(relaxed = true),
                diagnostics, mockk(relaxed = true))
        }

        private val locate = WeeklyRouteDriver::class.java.getDeclaredMethod("locateSpot", RouteQuest::class.java, WarMap::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }

        /** Runs one case; returns the tap in 720p, or the failure reason. */
        fun run(w: Int, mapW: Int, mapH: Int, spotX: Double, spotY: Double, start: Double): Outcome {
            val scale = w / mapW.toDouble()
            range = (mapH * scale - 720).coerceAtLeast(0.0)
            origin = start.coerceIn(-range, 0.0)
            drags = 0
            frames = 0
            logs.clear()
            // Thousands of cases: do not let the mocks keep every recorded call (keeps the stubbing).
            clearMocks(api, swipe, diagnostics, blank, answers = false, recordedCalls = true, childMocks = true)
            val quest = RouteQuest(1, 100, 0, "", "", "", 3, "free", "repeatLast", spotX = spotX, spotY = spotY)
            @Suppress("UNCHECKED_CAST")
            val (loc, why) = locate.invoke(driver, quest, WarMap(1, "", mapW, mapH), w, 720, false, true) as Pair<Location?, String>
            if (loc == null) return Outcome(null, why, 0.0, 0)
            val earlyBad = logs.firstOrNull { it.startsWith("tap point ") && it.contains("reason=early") && !it.contains("kind=icon ") }
            if (earlyBad != null) return Outcome(null, "early tap not on a clear icon: $earlyBad", 0.0, 0)
            val steps = logs.count { it.startsWith("step quest=") && it.contains("drag=") }
            val believed = logs.lastOrNull { it.startsWith("step quest=") && it.contains("origin=") }?.substringAfter("origin=")?.toDoubleOrNull()
                ?: logs.last { it.startsWith("edge quest=") }.let { if (it.contains("edge=Top")) 0.0 else -range }
            return Outcome(ScreenPoint(loc.x / 2.0, loc.y / 2.0), "ok", kotlin.math.abs(believed - origin), steps)
        }
    }

    private var maxError = 0.0
    private var maxSteps = 0
    private var maxDrags = 0

    private fun check(h: Harness, w: Int, s: Spot, start: Double): String? {
        val out = h.run(w, s.mapW, s.mapH, s.x, s.y, start)
        val p = out.tap ?: return out.why
        if (!MapView.isTappable(p.x, p.y, w, 720)) return "not tappable at ${p.x},${p.y}"
        val c = MapView.CLEARANCE
        for ((dx, dy) in listOf(-c to 0.0, c to 0.0, 0.0 to -c, 0.0 to c))
            if (MapView.isCovered(p.x + dx, p.y + dy, w, 720)) return "within clearance of a zone at ${p.x},${p.y}"
        maxError = maxOf(maxError, out.estimatedError); maxSteps = maxOf(maxSteps, out.measuredSteps); maxDrags = maxOf(maxDrags, h.drags)
        if (out.measuredSteps > WeeklyRouteDriver.MAX_MAP_SCROLLS) return "measured steps ${out.measuredSteps}"
        if (out.estimatedError > originTolerance) return "believed origin off by ${"%.1f".format(out.estimatedError)} px"
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
        summary.append("  max believed-origin error ${"%.1f".format(maxError)} px, max measured steps $maxSteps, max drags $maxDrags\n")
        println(summary)
        assertTrue(failures.isEmpty(), failures.take(40).joinToString("\n") {
            "${it.ratio} war=${it.spot.warId} map=${it.spot.mapId} spot=${it.spot.spotId} (${it.spot.x},${it.spot.y}) start=${it.start}: ${it.why}"
        } + "\n${failures.size} failures\n$summary")
    }

    @Test fun `a failed measurement goes back to the map end once and then succeeds`() {
        // Middle spot of the tall map 405 at 1760: pushed to an end, then moved by measured drags.
        val s = Spot(405, 40501, 2048, 3440, 0, 1000.0, 1700.0)
        val h = Harness()
        h.failMeasurements = setOf(3)
        val out = h.run(1760, s.mapW, s.mapH, s.x, s.y, -1000.0)
        assertNotNull(out.tap, out.why)
        assertEquals(2, h.logs.count { it.startsWith("edge quest=") }, "edge pushed again after the failed measurement")
        assertEquals(1, h.logs.count { it.contains("measurement failed") })
        assertTrue(out.estimatedError <= originTolerance, "error ${out.estimatedError}")
    }

    @Test fun `two failed attempts report that the map movement did not converge`() {
        val s = Spot(405, 40501, 2048, 3440, 0, 1000.0, 1700.0)
        val h = Harness()
        h.failMeasurements = (1..200).toSet()
        val out = h.run(1760, s.mapW, s.mapH, s.x, s.y, -1000.0)
        assertNull(out.tap)
        assertEquals("map movement did not converge", out.why)
        assertEquals(2, h.logs.count { it.startsWith("edge quest=") })
    }

    @Test fun `a map that is not limited by the screen width is reported as not recognized`() {
        val h = Harness()
        val out = h.run(1280, 4000, 1000, 500.0, 500.0, 0.0)
        assertEquals("map not recognized", out.why)
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
    }

    @Test fun `the zoom is confirmed by two unchanged pinches in a row`() {
        assertFalse(MapZoom.confirmed(emptyList()))
        assertFalse(MapZoom.confirmed(listOf(1.0)))
        assertFalse(MapZoom.confirmed(listOf(1.0, 0.7)))
        assertFalse(MapZoom.confirmed(listOf(1.0, null)))
        assertFalse(MapZoom.confirmed(listOf(0.8, 1.0)))
        assertTrue(MapZoom.confirmed(listOf(0.7, 1.01, 0.995)))
        assertFalse(MapZoom.atMinimum(1.05))
        assertFalse(MapZoom.atMinimum(Double.NaN))
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
