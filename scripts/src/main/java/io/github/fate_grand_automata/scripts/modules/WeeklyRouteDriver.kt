package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.locations.QuestPanelLocations
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.weekly.AtlasAssets
import io.github.fate_grand_automata.scripts.weekly.BattleOutcome
import io.github.fate_grand_automata.scripts.weekly.ChapterList
import io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader
import io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession
import io.github.fate_grand_automata.scripts.weekly.ListSearchDirection
import io.github.fate_grand_automata.scripts.weekly.MapFit
import io.github.fate_grand_automata.scripts.weekly.MapLocalizer
import io.github.fate_grand_automata.scripts.weekly.MapView
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
    private val diagnostics: io.github.fate_grand_automata.scripts.weekly.IWeeklyPanelDiagnostics
) : RouteDriver, IFgoAutomataApi by api, AutoCloseable {
    companion object {
        /** Chapter banner match score: the corrected opaque crop scores above 0.90 on owner captures; absent chapters below 0.61. */
        const val BANNER_SIMILARITY = 0.85
        /** Main record button match score (colour, 525x70 px). */
        const val BUTTON_SIMILARITY = 0.8
        const val MAX_LIST_SCROLLS = 14
        const val MAX_MAP_SCROLLS = 4
        /** Screen pixels per map pixel after the full zoom-out (width of the game area / width of the map). */
        val ZOOMED_OUT_SCALE = 0.45..0.9
        const val SCREEN_TIMEOUT_SECONDS = 8
        /** Waits for the Free Quest mark (500 ms each) and reads of the panel (about 2 s in total). */
        const val PANEL_SETTLE_ATTEMPTS = 6
        const val PANEL_READ_ATTEMPTS = 4
    }

    private val run: WeeklyRouteRun get() = checkNotNull(session.run) { "no weekly route" }
    private val server get() = run.server
    private val localizer = MapLocalizer()
    private val assets by lazy { AtlasAssets(File(run.cacheDir, "navigation"), loader, log = ::log) }
    private val banners = mutableMapOf<Int, Pattern?>()
    private var mapCache: Triple<Int, Int, Pattern>? = null

    /** The ordinary battle's exit when it stopped for a reason other than the run limit (AP, defeat, user): the route ends with it. */
    var battleStop: AutoBattle.ExitException? = null
        private set

    override fun log(message: String) = messages.log(ScriptLog.WeeklyRoute(if (session.run?.probe == true) "mode=probe $message" else message))

    override fun close() {
        banners.values.forEach { it?.close() }
        banners.clear()
        mapCache?.third?.close()
        mapCache = null
    }

    // ---- screens -------------------------------------------------------------------------------------------------

    private fun topLeft(image: Images) = images[image] in loc.topLeftButton

    override fun screen(): RouteScreen = WeeklyRouteScreens(this,loc).read().also { log("screen detected=$it") }

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
        700.milliseconds.wait()
    }

    private fun findColor(template: Pattern, similarity: Double): Match? = useColor {
        useSameSnapIn {
            // 0.3 keeps near misses in the log (D-32); only the threshold below accepts a banner.
            val best = loc.listArea.findAll(template, 0.3).maxByOrNull { it.score }
            log("banner score=${best?.score} threshold=$similarity point=${best?.region?.center}")
            best?.takeIf { it.score >= similarity }
        }
    }

    /** Search lower content first, then upper content; never leave the expected list or exceed either bound. */
    private fun scanList(onEnd: (ListSearchDirection) -> Unit = {}, find: () -> Match?): Match? {
        val expected = screen()
        if (expected !in setOf(RouteScreen.Home, RouteScreen.List)) return null
        fun checked(): Match? = if (screen() == expected) find() else null
        checked()?.let { return it }
        for (direction in ListSearchDirection.entries) {
            repeat(MAX_LIST_SCROLLS) { index ->
                if (screen() != expected) return null
                scrollList(direction, index + 1)
                checked()?.let { return it }
            }
            onEnd(direction)
        }
        return null
    }

    override fun openList(list: ChapterList): Boolean {
        val image = when (list) {
            ChapterList.MainRecord1 -> Images.WeeklyMainRecord1
            ChapterList.MainRecord15 -> Images.WeeklyMainRecord15
            ChapterList.MainRecord2 -> Images.WeeklyMainRecord2
            ChapterList.Home -> return true
        }
        val template = useColor { images[image] }
        val match = scanList { findColor(template, BUTTON_SIMILARITY) }
        if (match == null) {
            log("list $list button not found")
            return false
        }
        log("list $list button score=${"%.2f".format(match.score)} at ${match.region.center}")
        if(screen() !in setOf(RouteScreen.Home,RouteScreen.List)) return false
        match.region.center.click()
        2.seconds.wait()
        val opened = waitScreen(RouteScreen.List)
        log("list $list opened=$opened")
        return opened
    }

    private fun bannerFor(war: WarInfo): Pattern? = banners.getOrPut(war.id) { assets.banner(server, war) }

    override fun openChapter(warId: Int): Boolean {
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
        io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow.enterMapWait.wait()
        val opened = waitScreen(RouteScreen.Map, timeout = 12)
        log("chapter $warId opened=$opened")
        return opened
    }

    override fun closeList(): Boolean {
        if (screen() != RouteScreen.List) return false
        loc.topLeftButton.click()
        2.seconds.wait()
        return waitScreen(RouteScreen.Home).also { log("close list home=$it") }
    }

    override fun terminal(): Boolean {
        if (screen() != RouteScreen.Map) return false.also { log("terminal button not visible") }
        loc.topLeftButton.click()
        io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow.leaveMapWait.wait()
        return waitScreen(RouteScreen.List, RouteScreen.Home).also { log("terminal returned=$it") }
    }

    override fun closePanel(): Boolean {
        if (screen() != RouteScreen.List) return false.also { log("panel close button not visible") }
        loc.topLeftButton.click()
        1.seconds.wait()
        return waitScreen(RouteScreen.Map).also { log("panel closed=$it") }
    }

    // ---- map -----------------------------------------------------------------------------------------------------

    private fun pinchOut(): Boolean {
        if (screen() != RouteScreen.Map) return false
        log("pinch before screen=Map")
        val ok = swipe.pinch(PinchGesture.zoomOut(loc.pinchBounds))
        1.seconds.wait()
        log("pinch after completed=$ok")
        return ok
    }

    override fun prepareMap(warId: Int): Boolean {
        2.seconds.wait()
        // Diagnostic fits use the broad scale range so the initial, still zoomed-in map is observable.
        val quest = run.items.firstOrNull { it.quest.warId == warId }?.quest
        val war = run.wars[warId]
        val map = if (war != null && quest != null) mapFor(war, quest.mapId) else null
        fun measure(stage: String) {
            val fit = map?.let { pattern ->
                useSameSnapIn {
                    loc.scriptArea.getPattern("weekly pinch diagnostic").use { shot -> localizer.localize(shot, pattern) }
                }
            }
            log("pinch $stage war=$warId scale=${fit?.scaleX}/${fit?.scaleY} origin=${fit?.originX},${fit?.originY} score=${fit?.score}")
        }
        measure("before")
        repeat(2) { if (!pinchOut()) return false }
        measure("after")
        log("chapter $warId zoomed out")
        return true
    }

    private fun mapFor(war: WarInfo, mapId: Int): Pattern? {
        mapCache?.let { (w, m, p) -> if (w == war.id && m == mapId) return p }
        mapCache?.third?.close()
        mapCache = null
        val pattern = assets.map(server, war, mapId) ?: return null
        mapCache = Triple(war.id, mapId, pattern)
        return pattern
    }

    private fun localize(map: Pattern): MapFit? = useSameSnapIn {
        loc.scriptArea.getPattern("weekly map").use { screen -> localizer.localize(screen, map, ZOOMED_OUT_SCALE) }
    }

    /** The tappable screen position of the quest's spot in 720p, or why it could not be located. */
    private fun locateSpot(quest: RouteQuest, map: Pattern, screenWidth: Int, screenHeight: Int, centre: Boolean = false): Pair<Location?, String> {
        var pinched = false
        var scrolls = 0
        var beforeScroll: io.github.fate_grand_automata.scripts.weekly.ScreenPoint? = null
        while (true) {
            val fit = localize(map)
            if (!MapLocalizer.accepts(fit)) {
                log("localize quest=${quest.id} rejected score=${fit?.score?.let { "%.2f".format(it) }} inliers=${fit?.inliers}")
                // Zoom resets on every entry and a pinch can be missed once; one more try before giving up on the chapter map.
                if (!pinched) { pinched = true; if (!pinchOut()) return null to "pinch failed"; continue }
                return null to "map not recognized"
            }
            fit!!
            val x = fit.screenX(quest.spotX)
            val y = fit.screenY(quest.spotY)
            log("localize quest=${quest.id} score=${"%.2f".format(fit.score)} scale=${"%.3f".format(fit.scaleX)}/${"%.3f".format(fit.scaleY)} " +
                "origin=${"%.0f".format(fit.originX)},${"%.0f".format(fit.originY)} spot=${"%.0f".format(x)},${"%.0f".format(y)}")
            beforeScroll?.let { before ->
                val origin = io.github.fate_grand_automata.scripts.weekly.ScreenPoint(fit.originX, fit.originY)
                when (MapView.afterScroll(before, origin, centre, MapView.isTappable(x, y, screenWidth, screenHeight))) {
                    MapView.ScrollResult.Tap -> {
                        log("scroll quest=${quest.id} stationary=true recenter=current-spot")
                        return Location(MapView.toScript(x), MapView.toScript(y)) to "ok"
                    }
                    MapView.ScrollResult.Stop -> return visibleIcon(quest, x, y, screenWidth, screenHeight) ?: (null to "spot out of reach")
                    MapView.ScrollResult.Refit -> Unit
                }
            }
            val drag = MapView.dragToward(x, y, screenWidth, screenHeight, centre)
            if (drag == null) return Location(MapView.toScript(x), MapView.toScript(y)) to "ok"
            if (scrolls >= MAX_MAP_SCROLLS || !MapView.isMeaningful(drag)) return visibleIcon(quest, x, y, screenWidth, screenHeight) ?: (null to "spot out of reach")
            scrolls++
            beforeScroll = io.github.fate_grand_automata.scripts.weekly.ScreenPoint(fit.originX, fit.originY)
            val (from, to) = MapView.dragPath(drag, screenWidth, screenHeight)
            log("scroll quest=${quest.id} drag=${"%.0f".format(drag.x)},${"%.0f".format(drag.y)}")
            swipe.scroll(Location(MapView.toScript(from.x), MapView.toScript(from.y)), Location(MapView.toScript(to.x), MapView.toScript(to.y)))
            1.seconds.wait()
        }
    }

    private fun rawText(raw: List<io.github.lib_automata.OcrText>) = raw.joinToString("|") { "${it.text.trim()}@${it.confidence}" }

    /** D-30: how the last probed quest matched; read by the route machine right after a successful [probeSpot]. */
    private var evidence: io.github.fate_grand_automata.scripts.weekly.ProbeEvidence? = null
    private var evidenceAnchor: io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor? = null
    override fun lastEvidence() = evidence

    /** D-33: the map cannot bring the spot clear of a fixed button; tap the part of its icon that shows, if there is one. */
    private fun visibleIcon(quest: RouteQuest, x: Double, y: Double, width: Int, height: Int): Pair<Location?, String>? {
        val point = MapView.visibleTap(x, y, width, height) ?: return null
        log("tap fallback quest=${quest.id} spot=${"%.0f".format(x)},${"%.0f".format(y)} visible=${"%.0f".format(point.x)},${"%.0f".format(point.y)}")
        return Location(MapView.toScript(point.x), MapView.toScript(point.y)) to "ok"
    }

    /**
     * D-31: the spot on a map the Atlas has no picture of. The map is dragged to its top or bottom border (it stops there),
     * which fixes its position, and the spot is placed from its Atlas coordinates. A wrong tap can only open another spot's
     * panel, whose names then fail the check, so nothing is started on a wrong spot.
     */
    private fun locatePlaceholderSpot(quest: RouteQuest, war: WarInfo, width: Int, height: Int): Pair<Location?, String> {
        val map = war.maps.firstOrNull { it.id == quest.mapId } ?: return null to "map ${quest.mapId} image unavailable"
        val geometry = PlaceholderMap.geometry(map, width, height) ?: return null to "map not recognized"
        val target = geometry.target(quest.spotX, quest.spotY) ?: return null to "spot out of reach"
        val down = target.edge == PlaceholderMap.Edge.Top
        val (from, to) = MapView.dragPath(ScreenPoint(0.0, if (down) 1000.0 else -1000.0), width, height)
        val length = kotlin.math.abs(to.y - from.y)
        repeat(geometry.drags(length)) {
            swipe.scroll(Location(MapView.toScript(from.x), MapView.toScript(from.y)), Location(MapView.toScript(to.x), MapView.toScript(to.y)))
            1.seconds.wait()
        }
        log("placeholder map quest=${quest.id} edge=${target.edge} scale=${"%.3f".format(geometry.scale)} spot=${"%.0f".format(target.point.x)},${"%.0f".format(target.point.y)}")
        return Location(MapView.toScript(target.point.x), MapView.toScript(target.point.y)) to "ok"
    }

    private fun readAnchoredPanel(quest: RouteQuest, tap: Boolean, failure: io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFailure): Boolean {
        evidence = null
        evidenceAnchor = null
        for (i in 0 until PANEL_SETTLE_ATTEMPTS) {
            if (useSameSnapIn { reader.anchors().isNotEmpty() }) break
            500.milliseconds.wait()
        }
        300.milliseconds.wait()
        // D-29: the spot ribbon is read against the spot names of this chapter only.
        val spotNames = run.wars[quest.warId]?.spots?.values?.filter { it.isNotBlank() }?.distinct().orEmpty()
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
                    val panel = reader.readAnchored(prefs.gameServer, anchor, run.questNames, spot)
                    val by = QuestPanelMatch.matchBy(panel, quest.name, quest.spot, quest.spotFreeQuests)
                    val verdict = if (by != null) PanelVerdict.Match else QuestPanelMatch.strictVerdict(panel, quest.ap, quest.name, prefs.gameServer)
                    failure.verdict(QuestPanelMatch.label(verdict))
                    log("panel quest=${quest.id} anchor=${anchor.mark} attempt=$attempt ap=${panel.ap} name=${panel.nameText} spot=${panel.spotText} " +
                        "cells=${panel.nameCells?.let { "%.2f".format(it) }} by=${by ?: "none"} verdict=${QuestPanelMatch.label(verdict)}")
                    log("panel quest=${quest.id} attempt=$attempt rawNames=[${rawText(panel.rawNames)}] rawSpots=[${rawText(panel.rawSpots)}]")
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
            if (attempt < PANEL_READ_ATTEMPTS - 1) 500.milliseconds.wait()
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
        val war = run.wars[quest.warId] ?: return SpotOutcome.NotFound("no chapter data")
        if (screen() != RouteScreen.Map) return SpotOutcome.NotFound("not on the map")
        val map = mapFor(war, quest.mapId)
        // D-31: a placeholder Atlas map is located by geometry instead of by image.
        val placeholder = map == null && assets.isPlaceholder(server, war, quest.mapId)
        if (map == null && !placeholder) return SpotOutcome.NotFound("map ${quest.mapId} image unavailable", skipChapter = true)
        val width = loc.scriptArea.width / 2
        val height = loc.scriptArea.height / 2
        var problem = "no matching free panel name"
        val failure = io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFailure()
        fun openPanel(centre: Boolean): Boolean {
            val (spot, why) = if (map != null) locateSpot(quest, map, width, height, centre) else locatePlaceholderSpot(quest, war, width, height)
            if (spot == null) { problem = why; return false }
            log("tap step=spot quest=${quest.id} centre=$centre point=$spot")
            spot.click()
            2.seconds.wait()
            if (!waitScreen(RouteScreen.List, timeout = 4)) {
                // D-33: keep the frame right after the tap, then tap the same point once more before giving up.
                log("no panel quest=${quest.id} retap=true point=$spot")
                if (!tap) probeDiagnostic(quest.id, 1)
                1.seconds.wait()
                spot.click()
                2.seconds.wait()
                if (!waitScreen(RouteScreen.List, timeout = 4)) { problem = "no panel at the spot"; return false }
            }
            failure.panelOpened()
            return true
        }
        if (!openPanel(false)) {
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
                    300.milliseconds.wait()
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
        return SpotOutcome.NotFound(failure.reason(problem), skipChapter = !failure.opened && problem in setOf("map not recognized", "pinch failed"))
    }

    // ---- battle --------------------------------------------------------------------------------------------------

    /** Only called after the existing battle flow reaches its final run limit. */
    private fun closeRepeatDialog(): Boolean = WeeklyRepeatDialog(this, loc).closeIfVisible()

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
                val settled = settleOnMap()
                log("battle done quest=${quest.id} runs=$done onMap=$settled")
                if (settled) BattleOutcome(done, true) else BattleOutcome(done, false, "map with the quest panel not reached")
            } else {
                log("battle stopped quest=${quest.id} runs=$done reason=${if (e.reason is AutoBattle.ExitReason.Abort) "Abort" else "BattleStopped"}")
                battleStop = e
                BattleOutcome(done, false, if (e.reason is AutoBattle.ExitReason.Abort) "Abort" else "BattleStopped")
            }
        }
    }
}
