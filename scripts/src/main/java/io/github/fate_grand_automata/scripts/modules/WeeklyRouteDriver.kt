package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.locations.QuestPanelLocations
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.weekly.AtlasAssets
import io.github.fate_grand_automata.scripts.weekly.AtlasWeekly
import io.github.fate_grand_automata.scripts.weekly.BattleOutcome
import io.github.fate_grand_automata.scripts.weekly.ChapterList
import io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader
import io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession
import io.github.fate_grand_automata.scripts.weekly.ListSearchDirection
import io.github.fate_grand_automata.scripts.weekly.MapFit
import io.github.fate_grand_automata.scripts.weekly.MapLocalizer
import io.github.fate_grand_automata.scripts.weekly.MapView
import io.github.fate_grand_automata.scripts.weekly.MapZoom
import io.github.fate_grand_automata.scripts.weekly.WarMap
import io.github.fate_grand_automata.scripts.weekly.PlaceholderMap
import io.github.fate_grand_automata.scripts.weekly.ScreenPoint
import io.github.fate_grand_automata.scripts.weekly.RouteDriver
import io.github.fate_grand_automata.scripts.weekly.RouteQuest
import io.github.fate_grand_automata.scripts.weekly.RouteScreen
import io.github.fate_grand_automata.scripts.weekly.SpotOutcome
import io.github.fate_grand_automata.scripts.weekly.WarInfo
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteRun
import io.github.lib_automata.Location
import io.github.lib_automata.Match
import io.github.lib_automata.PinchGesture
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Swiper
import io.github.lib_automata.dagger.ScriptScope
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The screen side of the weekly route (design 0009 D-3 to D-6): list buttons and chapter banners are found by image
 * matching, the map is localized against the Atlas map image, and a quest panel is tapped only when it carries the
 * Free Quest emblem and its AP and name match. Every method that taps checks first what it is about to tap.
 */
@ScriptScope
class WeeklyRouteDriver @Inject constructor(
    api: IFgoAutomataApi,
    private val loc: WeeklyRouteLocations,
    private val panelLoc: QuestPanelLocations,
    private val reader: QuestPanelReader,
    private val swipe: Swiper,
    private val battle: AutoBattle,
    private val state: BattleState,
    private val session: IWeeklyRouteSession,
    private val loader: IPatternFileLoader,
    private val diagnostics: io.github.fate_grand_automata.scripts.weekly.IWeeklyPanelDiagnostics,
    private val vaultReader: WeeklyVaultReader,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : RouteDriver, IFgoAutomataApi by api, AutoCloseable {
    companion object {
        /** Chapter banner match score: the corrected opaque crop scores above 0.90 on owner captures; absent chapters below 0.61. */
        const val BANNER_SIMILARITY = 0.85
        /** Main record button match score (colour, 525x70 px). */
        const val BUTTON_SIMILARITY = 0.8
        const val MAX_LIST_SCROLLS = 14
        const val MAX_MAP_SCROLLS = 16
        /** Largest requested drag of one measured step: the motion crop is 300 px tall, so at least 100 px overlap stay at a 1:1 gain. */
        const val MAX_STEP_DRAG = 180.0
        /** Long drag that pushes the map to an end (the finger path is capped by the free map area). */
        const val PUSH_DRAG = 268.0
        /** Measured drag that shows whether the end is reached. */
        const val VERIFY_DRAG = 120.0
        /** Smallest finger-to-map gain seen on devices (0.66..1.00); the count of long drags assumes a bit less. */
        const val MIN_GAIN = 0.6
        const val DEFAULT_GAIN = 0.85
        const val SCREEN_TIMEOUT_SECONDS = 8
        /** Waits for the Free Quest mark (500 ms each) and reads of the panel (about 2 s in total). */
        const val PANEL_SETTLE_ATTEMPTS = 6
        const val PANEL_READ_ATTEMPTS = 4
        /** Map lines written to the run's diagnostics folder (issue #91). */
        private val MAP_LOG_PREFIXES = listOf("edge quest=", "step quest=", "zoom ", "tap point ", "tap fallback ", "mismatch ", "vault ")
    }

    private val run: WeeklyRouteRun get() = checkNotNull(session.run) { "no weekly route" }
    private val server get() = run.server
    private val assets by lazy { AtlasAssets(File(run.cacheDir, "navigation"), loader, log = ::log) }
    private val banners = mutableMapOf<Int, Pattern?>()

    /** The ordinary battle's exit when it stopped for a reason other than the run limit (AP, defeat, user): the route ends with it. */
    var battleStop: AutoBattle.ExitException? = null
        private set

    override fun log(message: String) {
        if (MAP_LOG_PREFIXES.any { message.startsWith(it) }) diagnostics.mapLog(message)
        if (message.startsWith("abort ") && !message.startsWith("abort diagnostic")) abortDiagnostic()
        messages.log(ScriptLog.WeeklyRoute(if (session.run?.probe == true) "mode=probe $message" else message))
    }

    override fun close() {
        diagnostics.flushMapLog()
        banners.values.forEach { it?.close() }
        banners.clear()
    }

    // ---- screens -------------------------------------------------------------------------------------------------

    private fun topLeft(image: Images) = loc.topLeftButton.exists(images[image],
        similarity = addedTuning.weekly.defaultSimilarity { prefs.platformPrefs.minSimilarity })

    override fun screen(): RouteScreen = useSameSnapIn {
        val (notice, terminal, close) = WeeklyRouteScreens(this, loc, addedTuning).scores()
        WeeklyRouteScreens.classify(notice, terminal, close, addedTuning.weekly).also {
            log("screen detected=$it notice=$notice terminal=$terminal close=$close")
        }
    }

    /** Daily content is identified by panel anchors, independently of its title or event art. */
    fun dailyList(): Boolean = useSameSnapIn {
        val (notice, terminal, close) = WeeklyRouteScreens(this, loc, addedTuning).scores()
        val anchors = vaultReader.anchorMatches()
        val daily = WeeklyRouteScreens.classify(notice, terminal, close, addedTuning.weekly) == RouteScreen.List && anchors.isNotEmpty()
        log("vault step=daily-list close=$close anchor=${anchors.maxOfOrNull { it.score }} found=$daily")
        daily
    }

    private fun waitDaily(): Boolean {
        repeat(24) {
            if (dailyList()) return true
            500.milliseconds.wait()
        }
        return false
    }

    private fun waitScreen(vararg wanted: RouteScreen, timeout: Int = SCREEN_TIMEOUT_SECONDS): Boolean {
        repeat(timeout * 2) {
            val now = screen()
            if (now in wanted) return true
            500.milliseconds.wait()
        }
        return false
    }

    // ---- lists ---------------------------------------------------------------------------------------------------

    private fun scrollList(direction: ListSearchDirection, count: Int) {
        val (startY, endY) = direction.fingerY(loc.listScrollStart.y, loc.listScrollEnd.y)
        val from = loc.listScrollStart.copy(y = startY)
        val to = loc.listScrollEnd.copy(y = endY)
        log("scroll direction=$direction finger=${if (endY < startY) "UP" else "DOWN"} count=$count from=$from to=$to")
        swipe.scroll(from, to)
        addedTuning.weekly.delay(700.milliseconds).wait()
    }

    private fun findColor(template: Pattern, similarity: Double, label: String = "banner"): Match? = useColor {
        useSameSnapIn {
            // 0.3 keeps near misses in the log (D-32); only the threshold below accepts a banner.
            val best = loc.listArea.findAll(template, 0.3).maxByOrNull { it.score }
            log("$label score=${best?.score} threshold=$similarity point=${best?.region?.center}")
            best?.takeIf { it.score >= addedTuning.weekly.similarity(similarity) }
        }
    }

    /** Search lower content first, then upper content; never leave the expected list or exceed either bound. */
    private fun <T> scanList(onEnd: (ListSearchDirection) -> Unit = {}, find: () -> T?): T? {
        val expected = screen()
        if (expected !in setOf(RouteScreen.Home, RouteScreen.List)) return null
        return io.github.fate_grand_automata.scripts.weekly.WeeklyListScan.run(
            maxScrolls = MAX_LIST_SCROLLS,
            capture = {
                val valid = screen() == expected
                // Colour banner matching finishes before the separate grayscale end-template snapshot.
                val match = if (valid) find() else null
                val bar = if (valid) io.github.fate_grand_automata.scripts.weekly.ListScrollbar.read(this, loc, addedTuning.weekly)
                    else io.github.fate_grand_automata.scripts.weekly.ListScrollbar.State(false, false)
                io.github.fate_grand_automata.scripts.weekly.ListScanFrame(valid, match, bar)
            },
            scroll = { direction, count -> scrollList(direction, count) },
            onEnd = onEnd,
            log = ::log
        )
    }

    override fun openList(list: ChapterList): Boolean {
        if (list == ChapterList.ChaldeaGate && screen() != RouteScreen.Home) return false
        val image = when (list) {
            ChapterList.MainRecord1 -> Images.WeeklyMainRecord1
            ChapterList.MainRecord15 -> Images.WeeklyMainRecord15
            ChapterList.MainRecord2 -> Images.WeeklyMainRecord2
            ChapterList.MainInterlude -> Images.WeeklyMainInterlude
            ChapterList.ChaldeaGate -> Images.WeeklyGate
            ChapterList.Home -> return true
        }
        // The Main Interlude button has a template for JP only; a server without one cannot enter the list.
        val template = runCatching { useColor { images[image] } }.getOrNull()
            ?: return false.also { log("list $list button has no template for this server") }
        val match = scanList { findColor(template, BUTTON_SIMILARITY, if (list == ChapterList.ChaldeaGate) "gate" else "banner") }
        if (match == null) {
            log("list $list button not found")
            return false
        }
        log("list $list button score=${"%.2f".format(match.score)} at ${match.region.center}")
        val beforeTap = screen()
        if (list == ChapterList.ChaldeaGate && beforeTap != RouteScreen.Home) return false
        if (beforeTap !in setOf(RouteScreen.Home, RouteScreen.List)) return false
        match.region.center.click()
        addedTuning.weekly.delay(2.seconds).wait()
        val opened = waitScreen(RouteScreen.List)
        log("list $list opened=$opened")
        return opened
    }

    private fun bannerFor(war: WarInfo): Pattern? = banners.getOrPut(war.id) { assets.banner(server, war) }

    override fun openChapter(warId: Int): Boolean {
        if (warId == AtlasWeekly.CHALDEA_GATE_DAILY_WAR) {
            if (screen() != RouteScreen.List) return false
            val template = useColor { images[Images.WeeklyDaily] }
            val match = scanList { findColor(template, BANNER_SIMILARITY, "daily banner") }
                ?: return false.also { abortDiagnostic() }
            if (screen() != RouteScreen.List) return false
            match.region.center.click()
            addedTuning.weekly.delay(3.seconds).wait()
            return waitDaily()
        }
        if (screen() !in setOf(RouteScreen.Home, RouteScreen.List)) return false
        val war = run.wars[warId] ?: return false.also { log("chapter $warId has no data") }
        val banner = bannerFor(war) ?: return false.also { log("chapter $warId banner unavailable") }
        // D-32: a chapter that is never found leaves one list frame per scan direction for the owner to compare with the Atlas banner.
        val match = scanList(onEnd = { direction -> if (session.run?.probe == true) probeDiagnostic(900_000 + warId, direction.ordinal) }) {
            findColor(banner, BANNER_SIMILARITY)
        }
        if (match == null) {
            log("chapter $warId banner not found banner=${banner.width}x${banner.height}")
            return false
        }
        log("chapter $warId banner score=${"%.2f".format(match.score)} at ${match.region.center}")
        if(screen() !in setOf(RouteScreen.Home,RouteScreen.List)) return false
        match.region.center.click()
        addedTuning.weekly.delay(io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow.enterMapWait).wait()
        val opened = waitScreen(RouteScreen.Map, timeout = 12)
        log("chapter $warId opened=$opened")
        return opened
    }

    override fun closeList(): Boolean {
        if (screen() !in setOf(RouteScreen.List)) return false
        loc.topLeftButton.click()
        addedTuning.weekly.delay(2.seconds).wait()
        return waitScreen(RouteScreen.Home).also { log("close list home=$it") }
    }

    override fun terminal(): Boolean {
        if (dailyList()) {
            loc.topLeftButton.click()
            addedTuning.weekly.delay(2.seconds).wait()
            return waitScreen(RouteScreen.List)
        }
        if (screen() != RouteScreen.Map) return false.also { log("terminal button not visible") }
        loc.topLeftButton.click()
        addedTuning.weekly.delay(io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow.leaveMapWait).wait()
        return waitScreen(RouteScreen.List, RouteScreen.Home).also { log("terminal returned=$it") }
    }

    override fun closePanel(): Boolean {
        if (dailyList()) return true
        return io.github.fate_grand_automata.scripts.weekly.WeeklyPanelClose.run(
        tap = { if (screen() == RouteScreen.List) { loc.topLeftButton.click(); true } else false },
        waitMap = { waitScreen(RouteScreen.Map, timeout = 3) },
        diagnostic = { abortDiagnostic() },
        recover = {
            // D-19: choose the action from the current screen.
            when (screen()) {
                RouteScreen.Map -> true
                RouteScreen.List -> { loc.topLeftButton.click(); waitScreen(RouteScreen.Map, timeout = 3) }
                else -> false
            }
        }
    ).also { log("panel closed=$it") }
    }

    fun abortDiagnostic() {
        runCatching {
            useColor {
                loc.scriptArea.getPattern("weekly route abort").use { frame ->
                    diagnostics.saveAbort(frame)?.let { log("abort diagnostic path=$it") }
                }
            }
        }.onFailure { log("diagnostic failed=${it.message}") }
    }

    // ---- map -----------------------------------------------------------------------------------------------------

    private fun pinchOut(): Boolean {
        if (screen() != RouteScreen.Map) return false
        log("pinch before screen=Map")
        val ok = swipe.pinch(PinchGesture.zoomOut(loc.pinchBounds))
        addedTuning.weekly.delay(1.seconds).wait()
        log("pinch after completed=$ok")
        return ok
    }

    /** Scale between two frames of the map motion crop, or null when they cannot be fitted. */
    private fun frameScale(before: Pattern, after: Pattern): Double? =
        MapLocalizer.fit(before.featureMatches(after, Region(0, 0, after.width, after.height)))
            ?.takeIf { kotlin.math.abs(it.scaleX / it.scaleY - 1) <= 0.05 }?.let { (it.scaleX + it.scaleY) / 2 }

    /** Issue #111: pinch out until two pinches in a row leave the map unchanged (see [MapZoom]). No map image is involved. */
    override fun prepareMap(warId: Int): Boolean {
        mapProblem = null
        if (warId == AtlasWeekly.CHALDEA_GATE_DAILY_WAR) return true
        addedTuning.weekly.delay(2.seconds).wait()
        val history = mutableListOf<Double?>()
        repeat(MapZoom.MAX_PINCHES) {
            val scale = loc.mapMotion.getPattern("map zoom before").use { before ->
                if (!pinchOut()) return false
                loc.mapMotion.getPattern("map zoom after").use { after -> frameScale(before, after) }
            }
            history += scale
            log("zoom pinch=${history.size} scale=${scale?.let { "%.3f".format(it) }}")
            if (MapZoom.confirmed(history)) {
                log("chapter $warId zoomed out")
                return true
            }
        }
        log("chapter $warId zoom not confirmed")
        return false
    }

    private var mapProblem: String? = null
    override fun mapProblem() = mapProblem

    private var edgeDrag: ScreenPoint? = null
    private var mapPlacement: Pair<PlaceholderMap.Geometry, MapFit>? = null

    /** A frame-to-frame measurement failed; the locate flow starts again from the map end once. */
    private class MapMeasurementFailed : RuntimeException(null, null, false, false)

    /**
     * Issue #111: the tappable screen position of the quest's spot in 720p, or why it could not be located. The map is
     * never matched against a picture. The zoom is the smallest one, so the map is the game-area width wide and its
     * vertical position is known at its top and bottom end ([PlaceholderMap.Geometry]); the flow pushes to an end, checks
     * that the end is reached (one more drag shows no motion) and from there moves by measured drags only.
     */
    private fun locateSpot(quest: RouteQuest, map: WarMap, width: Int, height: Int, centre: Boolean = false, edgeFirst: Boolean = true): Pair<Location?, String> {
        val geometry = PlaceholderMap.geometry(map, width, height) ?: run {
            log("edge quest=${quest.id} map=${map.id} is not limited by the screen width ${map.width}x${map.height} view=${width}x$height")
            return null to "map not recognized"
        }
        repeat(2) { attempt ->
            try {
                return locateFromEdge(quest, geometry, width, height, centre, edgeFirst)
            } catch (e: MapMeasurementFailed) {
                log("step quest=${quest.id} measurement failed attempt=${attempt + 1}")
            }
        }
        return null to "map movement did not converge"
    }

    private fun locateFromEdge(quest: RouteQuest, geometry: PlaceholderMap.Geometry, width: Int, height: Int, centre: Boolean, edgeFirst: Boolean): Pair<Location?, String> {
        val scale = geometry.scale
        val range = geometry.range
        val target = geometry.target(quest.spotX, quest.spotY)
        // Edge spots go to the end Geometry.target picked; middle spots start from the end nearer to the spot's row.
        val middleOrigin = height / 2.0 - scale * quest.spotY
        val edge = target?.edge ?: if (kotlin.math.abs(middleOrigin) <= kotlin.math.abs(middleOrigin + range)) PlaceholderMap.Edge.Top else PlaceholderMap.Edge.Bottom
        log("edge quest=${quest.id} edge=$edge scale=${"%.3f".format(scale)} range=${"%.0f".format(range)}")
        val ratio = pushToEdge(geometry, edge, width, height)
        val endFit = geometry.fit(edge)
        var origin = endFit.originY
        val x = scale * quest.spotX
        val spotMapY = scale * quest.spotY
        val iconMapY = scale * (quest.spotY - MapView.ICON_OFFSET_MAP)
        val desired: Double
        val plan: String
        if (target != null) {
            desired = target.fit.originY
            plan = target.plan
        } else {
            val chosen = MapView.planOrigin(x, spotMapY, iconMapY, origin, range, width, height, centre, edgeFirst)
                ?: MapView.bestEffortOrigin(x, spotMapY, iconMapY, origin, range, width, height)
                ?: return (tapPoint(quest, x, origin + iconMapY, origin + spotMapY, width, height, "none") ?: (null to "spot out of reach"))
            desired = chosen.target
            plan = chosen.plan
        }
        fun remember(iy: Double, y: Double) {
            mapPlacement = geometry to endFit.copy(originY = origin)
            MapView.edgeDrag(x, if (plan == "icon") iy else y, width, height)?.let { edgeDrag = it }
        }
        val movement = io.github.fate_grand_automata.scripts.weekly.VerticalMapDrag(desired, width, height, ratio, maxTravel = MAX_STEP_DRAG)
        var steps = 0
        while (true) {
            val iy = origin + iconMapY
            val y = origin + spotMapY
            // Issue #96/#102: only a clear icon centre taps before the move has ended.
            if (!centre) {
                tapPoint(quest, x, iy, y, width, height, plan, reason = "early", edgeFirst = edgeFirst, onlyKind = "icon")?.let {
                    remember(iy, y)
                    return it
                }
            }
            val drag = movement.next(origin)
            if (drag == null) {
                remember(iy, y)
                return tapPoint(quest, x, iy, y, width, height, plan)
                    ?: (null to if (movement.stationary) "spot out of reach" else "map movement did not converge")
            }
            if (steps >= MAX_MAP_SCROLLS) return null to "map movement did not converge"
            steps++
            val moved = measure(drag, width, height)
            origin = (origin + moved).coerceIn(-range, 0.0)
            log("step quest=${quest.id} drag=${"%.0f".format(drag.y)} measured=${"%.1f".format(moved)} origin=${"%.1f".format(origin)}")
        }
    }

    private fun measure(drag: ScreenPoint, width: Int, height: Int): Double = measuredMapDrag(drag, width, height) ?: throw MapMeasurementFailed()

    /**
     * Drags the map to [edge] and checks that it got there: long drags first (the range over the smallest gain seen on
     * device), then measured drags until one shows no motion. Returns the finger-to-map gain seen on the way.
     */
    private fun pushToEdge(geometry: PlaceholderMap.Geometry, edge: PlaceholderMap.Edge, width: Int, height: Int): Double {
        if (geometry.range <= 0.0) return DEFAULT_GAIN
        val down = edge == PlaceholderMap.Edge.Top
        val far = ScreenPoint(0.0, if (down) PUSH_DRAG else -PUSH_DRAG)
        val (farFrom, farTo) = MapView.dragPath(far, width, height)
        repeat(kotlin.math.ceil(geometry.range / (MIN_GAIN * kotlin.math.abs(farTo.y - farFrom.y))).toInt() + 1) {
            swipe.scroll(Location(MapView.toScript(farFrom.x), MapView.toScript(farFrom.y)), Location(MapView.toScript(farTo.x), MapView.toScript(farTo.y)))
            addedTuning.weekly.delay(1.seconds).wait()
        }
        val gains = mutableListOf<Double>()
        repeat(MAX_MAP_SCROLLS) {
            // The 300 px motion crop keeps more than 100 px of overlap even at a 1:1 gain.
            val drag = ScreenPoint(0.0, if (down) VERIFY_DRAG else -VERIFY_DRAG)
            val path = MapView.dragPath(drag, width, height)
            val delta = measure(drag, width, height)
            if (kotlin.math.abs(delta) < 0.75) return if (gains.isEmpty()) DEFAULT_GAIN else gains.average().coerceIn(0.1, 2.0)
            gains += kotlin.math.abs(delta / (path.second.y - path.first.y))
        }
        throw MapMeasurementFailed()
    }

    private fun rawText(raw: List<io.github.lib_automata.OcrText>) = raw.joinToString("|") { "${it.text.trim()}@${it.confidence}" }

    /** D-30: how the last probed quest matched; read by the route machine right after a successful [probeSpot]. */
    private var evidence: io.github.fate_grand_automata.scripts.weekly.ProbeEvidence? = null
    private var evidenceAnchor: io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor? = null
    override fun lastEvidence() = evidence

    /**
     * Issue #78: tap the spot icon's centre, else the visible part of the icon (D-33), else the spot point if it is clear.
     * Null when every point is covered; the caller then reports "spot out of reach".
     */
    private fun tapPoint(quest: RouteQuest, x: Double, iconY: Double, spotY: Double, width: Int, height: Int, plan: String,
                         reason: String = "converged", edgeFirst: Boolean = false, onlyKind: String? = null): Pair<Location?, String>? {
        val choice = MapView.resolveTap(x, iconY, x, spotY, width, height)?.takeIf { onlyKind == null || it.kind == onlyKind } ?: return null
        // edgeDrag uses the same scaled 60 px top/bottom margins as targetOrigin.
        if (edgeFirst && MapView.edgeDrag(choice.point.x, choice.point.y, width, height) != null) return null
        log("tap point quest=${quest.id} plan=$plan kind=${choice.kind} icon=${"%.0f".format(x)},${"%.0f".format(iconY)} spot=${"%.0f".format(x)},${"%.0f".format(spotY)} " +
            "used=${"%.0f".format(choice.point.x)},${"%.0f".format(choice.point.y)} reason=$reason")
        return Location(MapView.toScript(choice.point.x), MapView.toScript(choice.point.y)) to "ok"
    }

    /** Relative motion of the map between two frames, from the central crop of the screen. Both crops are map pixels, never fixed UI. */
    private fun measuredMapDrag(drag: ScreenPoint, width: Int, height: Int): Double? {
        val (from, to) = MapView.dragPath(drag, width, height)
        return loc.mapMotion.getPattern("map motion before").use { before ->
            swipe.scroll(Location(MapView.toScript(from.x), MapView.toScript(from.y)), Location(MapView.toScript(to.x), MapView.toScript(to.y)))
            addedTuning.weekly.delay(1.seconds).wait()
            loc.mapMotion.getPattern("map motion after").use { after ->
                MapLocalizer.fit(before.featureMatches(after, io.github.lib_automata.Region(0, 0, after.width, after.height)), 0.98..1.02)
                    ?.takeIf { kotlin.math.abs(it.originX) <= 2.0 }?.originY
            }
        }
    }

    private fun readAnchoredPanel(quest: RouteQuest, tap: Boolean, failure: io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFailure): Boolean {
        evidence = null
        evidenceAnchor = null
        for (i in 0 until PANEL_SETTLE_ATTEMPTS) {
            if (useSameSnapIn { reader.anchors().isNotEmpty() }) break
            addedTuning.weekly.delay(500.milliseconds).wait()
        }
        addedTuning.weekly.delay(300.milliseconds).wait()
        // D-29: the spot ribbon is read against the spot names of this chapter only.
        val spotNames = run.wars[quest.warId]?.spots?.values?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val spotQuestNames = run.wars[quest.warId]?.spotQuests?.get(quest.spotId).orEmpty()
        for (attempt in 0 until PANEL_READ_ATTEMPTS) {
            val matched = useSameSnapIn {
                val anchors = reader.anchors()
                if (anchors.isEmpty()) {
                    failure.verdict("NoPanel")
                    log("panel quest=${quest.id} attempt=$attempt verdict=NoPanel")
                    panelDiagnostic(quest.id, attempt)
                }
                val spot = if (anchors.isEmpty() || spotNames.isEmpty()) null else reader.readSpot(prefs.gameServer, spotNames)
                for (anchor in anchors) {
                    val panel = reader.readAnchored(prefs.gameServer, anchor, run.questNames, spot, spotQuestNames)
                    var by = QuestPanelMatch.matchBy(panel, quest.name, quest.spot, quest.spotFreeQuests)
                    var imageScore: Double? = null
                    if (by == null && panel.free) {
                        // D-42 (#130): only quests with a name template, only after the OCR did not match.
                        imageScore = reader.nameImageScore(prefs.gameServer, anchor, quest.id)
                        by = QuestNameTemplates.matchBy(panel, imageScore, QuestNameTemplates.entry(prefs.gameServer, quest.id), quest.ap)
                    }
                    val verdict = if (by != null) PanelVerdict.Match else QuestPanelMatch.strictVerdict(panel, quest.ap, quest.name, prefs.gameServer)
                    failure.verdict(QuestPanelMatch.label(verdict))
                    log("panel quest=${quest.id} anchor=${anchor.mark} attempt=$attempt ap=${panel.ap} name=${panel.nameText} spot=${panel.spotText} " +
                        "cells=${panel.nameCells?.let { "%.2f".format(it) }} by=${by ?: "none"}${imageScore?.let { " nameImage=${"%.3f".format(it)}" }.orEmpty()} verdict=${QuestPanelMatch.label(verdict)}")
                    log("panel quest=${quest.id} attempt=$attempt rawNames=[${rawText(panel.rawNames)}] rawSpots=[${rawText(panel.rawSpots)}]")
                    if (verdict != PanelVerdict.Match) {
                        failure.panelRead(panel.nameText, panel.spotText)
                        log("mismatch quest=${quest.id} read=${panel.nameText.orEmpty()} spotRead=${panel.spotText.orEmpty()} expected=${quest.name} expectedSpot=${quest.spot}")
                    }
                    if (verdict == PanelVerdict.Match) {
                        evidenceAnchor = anchor
                        evidence = io.github.fate_grand_automata.scripts.weekly.ProbeEvidence(
                            by = by.orEmpty(), questRead = panel.nameText.orEmpty(), spotRead = panel.spotText.orEmpty())
                    }
                    if (verdict == PanelVerdict.Match && !tap) {
                        log("probe quest=${quest.id} verdict=Match by=$by panel left untapped")
                        return@useSameSnapIn true
                    }
                    if (verdict == PanelVerdict.Match) {
                        log("tap step=panel quest=${quest.id} by=$by point=${anchor.tap}")
                        anchor.tap.click()
                        return@useSameSnapIn true
                    }
                    panelDiagnostic(quest.id, attempt)
                }
                false
            }
            if (matched) {
                // D-30: the evidence picture comes from a fresh colour frame (the reads above use the grayscale snapshot).
                val anchor = evidenceAnchor
                if (!tap && anchor != null) evidence = evidence?.copy(file = saveEvidence(quest.id, anchor).orEmpty())
                return true
            }
            if (attempt < PANEL_READ_ATTEMPTS - 1) addedTuning.weekly.delay(500.milliseconds).wait()
        }
        return false
    }

    /** D-30: panel-name band above the spot ribbon, from a colour frame. Null when the sink is off. */
    private fun saveEvidence(questId: Int, anchor: io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor): String? =
        useColor { reader.evidence(anchor)?.use { image -> diagnostics.saveEvidence(image, questId) } }

    private fun panelDiagnostic(questId: Int, attempt: Int) {
        // Probe mode keeps one frame per failed quest (probeDiagnostic) instead of one per read attempt.
        if (run.probe || !diagnostics.enabled) return
        useColor {
            loc.scriptArea.getPattern("weekly panel non-Match").use { frame ->
                diagnostics.save(frame, questId, attempt)?.let { log("panel diagnostic path=$it") }
            }
        }
    }

    /** One frame of the screen a failed probe ended on (map or open panel). */
    private fun probeDiagnostic(questId: Int, attempt: Int = 0) {
        if (!diagnostics.enabled) return
        useColor {
            loc.scriptArea.getPattern("weekly probe failure").use { frame ->
                diagnostics.save(frame, questId, attempt)?.let { log("probe diagnostic path=$it") }
            }
        }
    }

    override fun openSpot(quest: RouteQuest): SpotOutcome = open(quest, tap = true)

    override fun probeSpot(quest: RouteQuest): SpotOutcome {
        diagnostics.startProbe()
        return open(quest, tap = false)
    }

    private fun open(quest: RouteQuest, tap: Boolean): SpotOutcome {
        if (quest.warId == AtlasWeekly.CHALDEA_GATE_DAILY_WAR) return openVault(quest, tap)
        val war = run.wars[quest.warId] ?: return SpotOutcome.NotFound("no chapter data")
        if (screen() != RouteScreen.Map) return SpotOutcome.NotFound("not on the map")
        // Issue #111: the map is located by edge geometry from its size; no map image is needed.
        val map = war.maps.firstOrNull { it.id == quest.mapId } ?: return SpotOutcome.NotFound("map ${quest.mapId} image unavailable", skipChapter = true)
        val width = loc.scriptArea.width / 2
        val height = loc.scriptArea.height / 2
        var problem = "no matching free panel name"
        val failure = io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFailure()
        edgeDrag = null
        mapPlacement = null
        fun openPanel(centre: Boolean, edgeFirst: Boolean = true, recoveredSpot: Location? = null): Boolean {
            val (spot, why) = if (recoveredSpot != null) recoveredSpot to "ok" else locateSpot(quest, map, width, height, centre, edgeFirst)
            if (spot == null) { problem = why; return false }
            log("tap step=spot quest=${quest.id} centre=$centre point=$spot")
            spot.click()
            addedTuning.weekly.delay(2.seconds).wait()
            if (!waitScreen(RouteScreen.List, timeout = 4)) {
                // D-33: keep the frame right after the tap, then tap the same point once more before giving up.
                log("no panel quest=${quest.id} retap=true point=$spot")
                if (!tap) probeDiagnostic(quest.id, 1)
                addedTuning.weekly.delay(1.seconds).wait()
                spot.click()
                addedTuning.weekly.delay(2.seconds).wait()
                if (!waitScreen(RouteScreen.List, timeout = 4)) { problem = "no panel at the spot"; return false }
            }
            failure.panelOpened()
            return true
        }
        var opened = openPanel(false)
        val edge = edgeDrag
        if (!opened && problem == "no panel at the spot" && edge != null && screen() == RouteScreen.Map) {
            log("edge recovery quest=${quest.id} opposite=true")
            val actualDelta = if (mapPlacement != null) measuredMapDrag(ScreenPoint(0.0, -edge.y), width, height) else null
            val recovered = mapPlacement?.let { (geometry, fit) ->
                val delta = actualDelta ?: return@let null
                val originY = (fit.originY + delta).coerceIn(-geometry.range, 0.0)
                val y = originY + fit.scaleY * quest.spotY
                val x = fit.screenX(quest.spotX)
                val point = MapView.resolveTap(x, originY + fit.scaleY * (quest.spotY - MapView.ICON_OFFSET_MAP), x, y, width, height)?.point
                point?.let { Location(MapView.toScript(it.x), MapView.toScript(it.y)) }
            }
            opened = openPanel(false, edgeFirst = false, recoveredSpot = recovered)
        }
        if (!opened) {
            if (!tap) probeDiagnostic(quest.id)
            return SpotOutcome.NotFound(problem, skipChapter = problem in setOf("map not recognized", "pinch failed"))
        }
        val matched = io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow.run(
            read = { readAnchoredPanel(quest, tap, failure) },
            resetList = {
                val anchor = useSameSnapIn { reader.resetAnchor() }
                if (screen() != RouteScreen.List || anchor == null) {
                    log("panel reset skipped reason=no-safe-anchor")
                    false
                } else {
                    log("panel reset finger=DOWN from=${anchor.resetStart} to=${anchor.resetEnd}")
                    swipe.scroll(anchor.resetStart, anchor.resetEnd)
                    addedTuning.weekly.delay(300.milliseconds).wait()
                    true
                }
            },
            recenter = {
                log("panel recovery step=centre quest=${quest.id}")
                closePanel() && openPanel(true)
            }
        )
        if (matched) return SpotOutcome.Started
        if (!tap) probeDiagnostic(quest.id)
        if (screen() == RouteScreen.List) closePanel()
        return SpotOutcome.NotFound(failure.reason(problem), skipChapter = !failure.opened && problem in setOf("map not recognized", "pinch failed"), read = failure.read)
    }

    // ---- battle --------------------------------------------------------------------------------------------------

    private fun readVault(): List<VaultPanel> = vaultReader.read().also { panels ->
        panels.forEach { panel ->
            log("vault anchor=${panel.anchor.mark} hue=${panel.color?.hue} saturation=${panel.color?.saturation} " +
                "name=${panel.name} ap=${panel.ap} verdict=${panel.reason} tap=${panel.anchor.tap} rawNames=[${rawText(panel.rawNames)}]")
        }
    }

    private fun openVault(quest: RouteQuest, tap: Boolean): SpotOutcome {
        evidence = null
        if (quest.id !in AtlasWeekly.TREASURE_VAULT_QUESTS || !dailyList()) {
            return SpotOutcome.NotFound("vault panel not found")
        }
        val problem = "vault panel not found"
        var vaultRead = ""
        val panel = scanList {
            val panels = readVault()
            panels.firstOrNull { it.color?.yellow == true }?.let { vaultRead = it.name.orEmpty() }
            if (vaultRead.isEmpty()) vaultRead = panels.firstOrNull()?.name.orEmpty()
            panels.firstOrNull { it.accepted }
        }
        if (panel == null) {
            panelDiagnostic(quest.id, 0)
            if (vaultRead.isNotEmpty()) log("mismatch quest=${quest.id} read=$vaultRead expected=${quest.name} problem=$problem")
            return SpotOutcome.NotFound(problem, read = io.github.fate_grand_automata.scripts.weekly.ProbeEvidence(questRead = vaultRead))
        }
        if (tap) {
            log("tap step=vault quest=${quest.id} point=${panel.anchor.tap}")
            panel.anchor.tap.click()
        } else {
            val file = runCatching { useColor { panel.anchor.name.getPattern().use { diagnostics.saveEvidence(it, quest.id) } } }.getOrNull()
            evidence = io.github.fate_grand_automata.scripts.weekly.ProbeEvidence(file = file.orEmpty(), by = "vault-yellow", questRead = panel.name.orEmpty())
            log("probe quest=${quest.id} verdict=Match vault panel left untapped")
        }
        return SpotOutcome.Started
    }

    private fun settleOnDaily(): Boolean {
        var closed = false
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < 30.seconds) {
            if (dailyList()) return true
            if (!closed && closeRepeatDialog()) {
                closed = true
                log("repeat dialog closed; settling daily list for 5s")
                5.seconds.wait()
            }
            500.milliseconds.wait()
        }
        return false
    }

    /** Only called after the existing battle flow reaches its final run limit. */
    private fun closeRepeatDialog(): Boolean = WeeklyRepeatDialog(this, loc, addedTuning).closeIfVisible()

    /** After the last run the game shows the map with the quest panel open (owner); a stuck repeat dialog is closed once. */
    private fun settleOnMap(): Boolean {
        var closed = false
        repeat(60) {
            val onPanel = useSameSnapIn { topLeft(Images.WeeklyClose) && reader.anchors().isNotEmpty() }
            if (onPanel) return true
            if (!closed && closeRepeatDialog()) {
                closed = true
                log("repeat dialog closed; settling map for 5s")
                5.seconds.wait()
            }
            500.milliseconds.wait()
        }
        return false
    }

    override fun battle(quest: RouteQuest, count: Int): BattleOutcome {
        val config = prefs.selectedServerConfigPref
        val before = state.runs
        // The battle script counts runs cumulatively and turns the limit off when it has been used up: set it again for each quest.
        config.shouldLimitRuns = true
        config.limitRuns = before + count
        log("battle start quest=${quest.id} runs=$count")
        try {
            battle.runSelectedQuest()
        } catch (e: AutoBattle.ExitException) {
            val done = state.runs - before
            return if (e.reason is AutoBattle.ExitReason.LimitRuns && done == count) {
                val vault = quest.warId == AtlasWeekly.CHALDEA_GATE_DAILY_WAR && quest.id in AtlasWeekly.TREASURE_VAULT_QUESTS
                val settled = if (vault) settleOnDaily() else settleOnMap()
                log("battle done quest=${quest.id} runs=$done onMap=$settled")
                if (settled) BattleOutcome(done, true) else BattleOutcome(done, false,
                    if (vault) "daily list with the vault panel not reached" else "map with the quest panel not reached")
            } else {
                log("battle stopped quest=${quest.id} runs=$done reason=${if (e.reason is AutoBattle.ExitReason.Abort) "Abort" else "BattleStopped"}")
                battleStop = e
                BattleOutcome(done, false, if (e.reason is AutoBattle.ExitReason.Abort) "Abort" else "BattleStopped")
            }
        }
    }
}
