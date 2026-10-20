package io.github.fate_grand_automata.scripts.weekly

/** The list a chapter button sits in (design 0009 D-8). [Home] is the main screen itself. */
enum class ChapterList { Home, MainRecord1, MainRecord15, MainRecord2 }

object ChapterLists {
    /**
     * JP: main record 1 holds the singularities (100-108), 1.5 the variant singularities (2xx) and 2 everything from
     * Lostbelt No.1 on (3xx Lostbelts and 4xx Ordeal Calls). KR keeps the same first two lists, but its part-2 chapters
     * are not bundled and sit directly in the main screen list.
     */
    fun of(server: String, warId: Int): ChapterList = when {
        warId in 100..108 -> ChapterList.MainRecord1
        warId in 200..299 -> ChapterList.MainRecord15
        server == "KR" -> ChapterList.Home
        else -> ChapterList.MainRecord2
    }

    private val order = ChapterList.entries.associateWith { it.ordinal }
    fun position(list: ChapterList) = if (list == ChapterList.Home) 3 else order.getValue(list)-1
}

/** The quests of one chapter, in the order they are run. */
data class ChapterVisit(val warId: Int, val list: ChapterList, val stops: List<RouteItem>)

object WeeklyVisitOrder {
    /**
     * Groups the route by chapter. Lists come in main-record order (1, 1.5, 2/home) so that each list is entered once,
     * chapters by war order inside it and quests by spot, then quest ID, so that a spot's quests run back to back.
     * Entries with a zero count are dropped.
     */
    fun order(server: String, items: List<RouteItem>): List<ChapterVisit> =
        items.filter { it.count > 0 }
            .groupBy { it.quest.warId }
            .map { (war, stops) ->
                ChapterVisit(war, ChapterLists.of(server, war), stops.sortedWith(compareBy({ it.quest.mapId }, { it.quest.spotId }, { it.quest.id })))
            }
            .sortedWith(compareBy({ ChapterLists.position(it.list) }, { it.stops.first().quest.warOrder }, { it.warId }))
}

enum class StopStatus { Done, NotFound, Aborted, NotRun }

data class StopResult(val item: RouteItem, val done: Int, val status: StopStatus, val note: String = "")

/** What the screen shows, as far as the route needs to tell. */
enum class RouteScreen { Home, List, Map, Unknown }

sealed class SpotOutcome {
    /** The matching quest panel was tapped; the game is in the support selection or the battle. */
    data object Started : SpotOutcome()
    data class NotFound(val reason: String, val skipChapter: Boolean = false) : SpotOutcome()
}

data class BattleOutcome(val runs: Int, val completed: Boolean, val note: String = "")

/** The screen side of the route; the navigation state machine only decides the order of these steps. */
interface RouteDriver {
    fun screen(): RouteScreen
    fun openList(list: ChapterList): Boolean
    fun openChapter(warId: Int): Boolean
    fun closeList(): Boolean
    fun terminal(): Boolean
    fun closePanel(): Boolean
    /** Called once after entering a chapter map: the map is zoomed out to its minimum. */
    fun prepareMap(warId: Int): Boolean
    /** Finds the quest's spot on the open map and taps the quest panel whose name and AP match. */
    fun openSpot(quest: RouteQuest): SpotOutcome
    /** Runs the quest [count] times with the ordinary battle flow and brings the game back to the map with the panel open. */
    fun battle(quest: RouteQuest, count: Int): BattleOutcome
    fun log(message: String)
}

/**
 * The route as a state machine: from the main screen to each chapter's list and map and back (design 0009 D-6).
 * It tracks where the game is, so consecutive quests of one chapter never leave the map and a new chapter is reached
 * through the terminal button and the list, or through the main screen when it lives in another list.
 */
class WeeklyRouteMachine(private val driver: RouteDriver, private val server: String) {
    private sealed class Loc {
        data object Home : Loc()
        data class InList(val list: ChapterList) : Loc()
        data class OnMap(val warId: Int, val list: ChapterList) : Loc()
        data object Lost : Loc()
    }

    private var loc: Loc = Loc.Home
    val results = mutableListOf<StopResult>()
    private var active: RouteItem? = null
    private val rejectedChapters=mutableSetOf<Int>()
    fun interrupt(note: String) {
        active?.let { item -> if (results.none { it.item.quest.id == item.quest.id }) results += StopResult(item,0,StopStatus.Aborted,note) }
    }

    fun run(visits: List<ChapterVisit>): List<StopResult> {
        for ((index, visit) in visits.withIndex()) {
            if (loc == Loc.Lost) { rest(visit, "navigation lost"); continue }
            active = visit.stops.firstOrNull()
            val problem = reach(visit)
            if (problem != null) { notFound(visit, problem); continue }
            var stopped = false
            for (item in visit.stops) {
                if (stopped) { results += StopResult(item, 0, StopStatus.NotRun, "route stopped"); continue }
                if(item.quest.warId in rejectedChapters) {
                    results += StopResult(item,0,StopStatus.NotFound,"chapter map not recognized")
                    continue
                }
                stopped = !runStop(item)
            }
            if (stopped) {
                loc = Loc.Lost
                visits.drop(index + 1).forEach { rest(it, "route stopped") }
                break
            }
        }
        return results
    }

    private fun rest(visit: ChapterVisit, note: String) = visit.stops.forEach { results += StopResult(it, 0, StopStatus.NotRun, note) }
    private fun notFound(visit: ChapterVisit, note: String) = visit.stops.forEach {
        driver.log("skip quest=${it.quest.id} reason=$note")
        results += StopResult(it, 0, StopStatus.NotFound, note)
    }

    /** Runs one quest on the open map. Returns false when the whole route has to stop. */
    private fun runStop(item: RouteItem): Boolean {
        active = item
        when (val spot = driver.openSpot(item.quest)) {
            is SpotOutcome.NotFound -> {
                driver.log("skip quest=${item.quest.id} reason=${spot.reason}")
                if(spot.skipChapter) rejectedChapters += item.quest.warId
                results += StopResult(item, 0, StopStatus.NotFound, spot.reason)
                // openSpot leaves the map without an open panel on a miss.
                return true
            }
            SpotOutcome.Started -> Unit
        }
        val battle = driver.battle(item.quest, item.count)
        if (!battle.completed) {
            driver.log("abort quest=${item.quest.id} runs=${battle.runs} note=${battle.note}")
            results += StopResult(item, battle.runs, StopStatus.Aborted, battle.note)
            return false
        }
        results += StopResult(item, battle.runs, StopStatus.Done, battle.note)
        if (!driver.closePanel()) {
            driver.log("abort reason=panel did not close")
            return false
        }
        return true
    }

    /** Brings the game to the visit's zoomed-out map with no panel open. Returns null, or why the chapter was skipped. */
    private fun reach(visit: ChapterVisit): String? {
        val current = loc
        if (current is Loc.OnMap && current.warId == visit.warId) return null
        if (current is Loc.OnMap) {
            if (!driver.terminal()) { loc = Loc.Lost; return "terminal button not found" }
            // The terminal button lands on the chapter list, or on the main screen (owner, beta.4): follow what is shown.
            loc = if (current.list == ChapterList.Home || driver.screen() == RouteScreen.Home) Loc.Home else Loc.InList(current.list)
        }
        val here = loc
        if (here is Loc.InList && here.list != visit.list) {
            if (!driver.closeList()) { loc = Loc.Lost; return "close button not found" }
            loc = Loc.Home
        }
        if (loc == Loc.Home && visit.list != ChapterList.Home) {
            if (!driver.openList(visit.list)) return "main record list ${visit.list} not found"
            loc = Loc.InList(visit.list)
        }
        if (!driver.openChapter(visit.warId)) return "chapter ${visit.warId} not found"
        loc = Loc.OnMap(visit.warId, visit.list)
        // The map stays open on a failed zoom: the next chapter leaves it through the terminal button as usual.
        return if (driver.prepareMap(visit.warId)) null else "zoom out failed"
    }
}
