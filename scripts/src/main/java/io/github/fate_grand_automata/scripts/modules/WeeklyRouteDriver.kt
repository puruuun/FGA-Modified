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
import io.github.fate_grand_automata.scripts.weekly.MapFit
import io.github.fate_grand_automata.scripts.weekly.MapLocalizer
import io.github.fate_grand_automata.scripts.weekly.MapView
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
    private val loader: IPatternFileLoader
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

    override fun log(message: String) = messages.log(ScriptLog.WeeklyRoute(message))

    override fun close() {
        banners.values.forEach { it?.close() }
        banners.clear()
        mapCache?.third?.close()
        mapCache = null
    }

    // ---- screens -------------------------------------------------------------------------------------------------

    private fun topLeft(image: Images) = images[image] in loc.topLeftButton

    override fun screen(): RouteScreen = WeeklyRouteScreens(this,loc).read()

    private fun waitScreen(vararg wanted: RouteScreen, timeout: Int = SCREEN_TIMEOUT_SECONDS): Boolean {
        repeat(timeout * 2) {
            val now = screen()
            if (now in wanted) return true
            500.milliseconds.wait()
        }
        return false
    }

    // ---- lists ---------------------------------------------------------------------------------------------------

    private fun scrollList(down: Boolean) {
        if (down) swipe.scroll(loc.listScrollStart, loc.listScrollEnd) else swipe.scroll(loc.listScrollEnd, loc.listScrollStart)
        700.milliseconds.wait()
    }

    private fun findColor(template: Pattern, similarity: Double): Match? = useColor {
        useSameSnapIn { loc.listArea.findAll(template, similarity).maxByOrNull { it.score } }
    }

    /** Looks at the current list view first, then scrolls to the top and down through the list, at most [MAX_LIST_SCROLLS] steps. */
    private fun scanList(find: () -> Match?): Match? {
        val expected = screen()
        if (expected !in setOf(RouteScreen.Home,RouteScreen.List)) return null
        fun checked(): Match? = if (screen()==expected) find() else null
        checked()?.let { return it }
        repeat(MAX_LIST_SCROLLS) { if(screen()!=expected) return null; scrollList(down = false) }
        for (i in 0..MAX_LIST_SCROLLS) {
            if(screen()!=expected) return null
            checked()?.let { return it }
            if (i < MAX_LIST_SCROLLS) scrollList(down = true)
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
        val match = scanList { findColor(banner, BANNER_SIMILARITY) }
        if (match == null) {
            log("chapter $warId banner not found")
            return false
        }
        log("chapter $warId banner score=${"%.2f".format(match.score)} at ${match.region.center}")
        if(screen() !in setOf(RouteScreen.Home,RouteScreen.List)) return false
        match.region.center.click()
        2.seconds.wait()
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
        2.seconds.wait()
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
        val ok = swipe.pinch(PinchGesture.zoomOut(loc.pinchBounds))
        1.seconds.wait()
        log("pinch completed=$ok")
        return ok
    }

    override fun prepareMap(warId: Int): Boolean {
        2.seconds.wait()
        repeat(2) { if (!pinchOut()) return false }
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
    private fun locateSpot(quest: RouteQuest, map: Pattern, screenWidth: Int, screenHeight: Int): Pair<Location?, String> {
        var pinched = false
        var scrolls = 0
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
            val drag = MapView.dragToward(x, y, screenWidth, screenHeight)
            if (drag == null) return Location(MapView.toScript(x), MapView.toScript(y)) to "ok"
            if (scrolls >= MAX_MAP_SCROLLS || !MapView.isMeaningful(drag)) return null to "spot out of reach"
            scrolls++
            val (from, to) = MapView.dragPath(drag, screenWidth, screenHeight)
            log("scroll quest=${quest.id} drag=${"%.0f".format(drag.x)},${"%.0f".format(drag.y)}")
            swipe.scroll(Location(MapView.toScript(from.x), MapView.toScript(from.y)), Location(MapView.toScript(to.x), MapView.toScript(to.y)))
            1.seconds.wait()
        }
    }

    override fun openSpot(quest: RouteQuest): SpotOutcome {
        val war = run.wars[quest.warId] ?: return SpotOutcome.NotFound("no chapter data")
        if (screen() != RouteScreen.Map) return SpotOutcome.NotFound("not on the map")
        val map = mapFor(war, quest.mapId) ?: return SpotOutcome.NotFound("map ${quest.mapId} image unavailable",skipChapter=true)

        val width = loc.scriptArea.width / 2
        val height = loc.scriptArea.height / 2
        val (spot, why) = locateSpot(quest, map, width, height)
        if (spot == null) return SpotOutcome.NotFound(why,skipChapter=why in setOf("map not recognized","pinch failed"))

        spot.click()
        2.seconds.wait()
        if (!waitScreen(RouteScreen.List, timeout = 4)) {
            log("quest=${quest.id} no panel after tapping the spot")
            return SpotOutcome.NotFound("no panel at the spot")
        }
        1.seconds.wait()

        val gameServer = prefs.gameServer
        for (slot in 0 until QuestPanelLocations.SLOTS) {
            val panel = reader.read(gameServer, slot, run.questNames)
            val verdict = QuestPanelMatch.strictVerdict(panel, quest.ap, quest.name, gameServer)
            log("panel quest=${quest.id} slot=$slot free=${panel.free} ap=${panel.ap} name=${panel.nameText} nameCells=${panel.nameCells} verdict=${QuestPanelMatch.label(verdict)}")
            if (verdict == PanelVerdict.Match) {
                panelLoc.tap(slot).click()
                return SpotOutcome.Started
            }
        }
        closePanel()
        return SpotOutcome.NotFound("no free panel with the AP and name")
    }

    // ---- battle --------------------------------------------------------------------------------------------------

    /** The repeat dialog's close button, the one the interlude end screen uses; clicked only when its picture is there. */
    private fun closeRepeatDialog(): Boolean = useSameSnapIn {
        if (images[Images.Close] in locations.interludeEndScreenClose && locations.continueRegion.find(images[Images.Repeat]) != null) {
            locations.interludeCloseClick.click()
            true
        } else false
    }

    /** After the last run the game shows the map with the quest panel open (owner); a stuck repeat dialog is closed once. */
    private fun settleOnMap(): Boolean {
        var closed = false
        repeat(60) {
            val onPanel = useSameSnapIn { topLeft(Images.WeeklyClose) && (0 until QuestPanelLocations.SLOTS).any { reader.isFree(it) } }
            if (onPanel) return true
            if (!closed && closeRepeatDialog()) { closed = true; log("repeat dialog closed") }
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
