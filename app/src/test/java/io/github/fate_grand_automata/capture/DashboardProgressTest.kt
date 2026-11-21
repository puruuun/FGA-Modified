package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import io.github.fate_grand_automata.scripts.weekly.AtlasWeekly
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DashboardProgressTest {
    private fun week(id: Int, start: Long, end: Long, vararg missions: Int) =
        """{"id":$id,"startedAt":$start,"endedAt":$end,"missions":[${missions.joinToString(",") { """{"id":$it,"name":"m$it","type":"weekly","conds":[]}""" }}]}"""

    private val master = "[" + week(1, 0, 1000, 11, 12) + "," + week(2, 1000, 2000, 21, 22, 23, 24, 25, 26, 27) + "]"

    private fun dir() = createTempDirectory("progress").toFile()

    private fun loader(dir: File, masterBody: String? = master, events: EventInfoStore = EventInfoStore(File(dir, "events")) { _, _ -> null }) =
        DashboardProgressLoader(AtlasRouteRepository(File(dir, "weekly"), fetch = { _, target -> target.writeText(masterBody ?: error("offline")) }), events)

    private fun input(snapshot: Long, achieved: Set<Long>?, points: List<DashboardEventPoint> = emptyList()) = DashboardProgressInput(snapshot, achieved, points)

    // Weekly line

    @Test fun theCurrentSetIsChosenByTheSnapshotTimeAndCountsAchievedAndReceivedOnly() = runBlocking<Unit> {
        val dir = dir()
        val achieved = setOf(21L, 23L, 27L)
        val progress = loader(dir).resolve(input(1500, achieved), "KR", network = true)
        assertEquals(WeeklyProgress(1000, 2000, done = 3, total = 7), progress.weekly)
        // An earlier snapshot belongs to the previous set, which has its own mission ids.
        assertEquals(WeeklyProgress(0, 1000, done = 0, total = 2), loader(dir).resolve(input(500, achieved), "KR", network = true).weekly)
        dir.deleteRecursively()
    }

    @Test fun aMissionWithoutARowIsNotDoneAndRowsOfOtherSetsAreIgnored() {
        val week = AtlasWeekly.activeWeek(master, 1500)
        assertEquals(0, weeklyProgress(week, emptySet())!!.done)
        // 11 and 12 belong to the past set, 99 to no set at all.
        assertEquals(1, weeklyProgress(week, setOf(11L, 12L, 99L, 22L))!!.done)
        assertEquals(7, weeklyProgress(week, week.missions.map { it.id.toLong() }.toSet())!!.done)
    }

    @Test fun theWeeklyLineIsHiddenWithoutMasterDataOrWithoutTheMissionTable() = runBlocking<Unit> {
        val dir = dir()
        assertNull(loader(dir, masterBody = null).resolve(input(1500, setOf(21L)), "KR", network = true).weekly)
        assertNull(loader(dir, masterBody = null).resolve(input(1500, setOf(21L)), "KR", network = false).weekly)
        assertNull(loader(dir, masterBody = "[]").resolve(input(1500, setOf(21L)), "KR", network = true).weekly)
        // The failed attempts above left a retry marker in [dir], so the cases below start from clean folders.
        val clean = dir()
        assertNull(loader(clean).resolve(input(1500, achieved = null), "KR", network = true).weekly)
        assertNull(loader(clean).resolve(input(5000, setOf(21L)), "KR", network = true).weekly)
        dir.deleteRecursively()
        clean.deleteRecursively()
    }

    @Test fun theMissionRowsAreReadFromTheStoredStateByProgressType() {
        val rows = Json.parseToJsonElement("""[{"missionId":1,"missionProgressType":0},{"missionId":2,"missionProgressType":3},
            {"missionId":3,"missionProgressType":4},{"missionId":4,"missionProgressType":5},{"missionProgressType":5}]""").jsonArray.map { it.jsonObject }
        assertEquals(setOf(3L, 4L), projectProgressInput(100, rows, null)!!.achievedMissions)
        assertNull(projectProgressInput(100, null, emptyList())!!.achievedMissions)
        assertNull(projectProgressInput(100, null, null))
    }

    // Event lines

    private fun points(vararg rows: String) = Json.parseToJsonElement("[${rows.joinToString(",")}]").jsonArray.map { it.jsonObject }

    @Test fun onlyGroupZeroRowsOfTheLast30DaysAreCandidates() {
        val snapshot = 100L * 86400
        val rows = points(
            """{"eventId":1,"groupId":0,"value":5,"updatedAt":${snapshot - 29 * 86400}}""",
            """{"eventId":2,"groupId":0,"value":6,"updatedAt":${snapshot - 31 * 86400}}""",
            """{"eventId":3,"groupId":7,"value":7,"updatedAt":$snapshot}""",
            """{"eventId":4,"groupId":0,"value":8,"updatedAt":$snapshot}""",
            """{"eventId":5,"groupId":0,"value":9}""",
        )
        assertEquals(listOf(4L, 1L), projectProgressInput(snapshot, null, rows)!!.eventPoints.map { it.eventId })
    }

    private val eventJson = """{"id":80500,"name":"샘플 이벤트","startedAt":1000,"endedAt":2000,
        "shop":[{"id":1,"name":"big"}],"rewards":[{"groupId":0,"point":100,"gifts":[]},{"groupId":0,"point":2000000,"gifts":[]},
        {"groupId":5,"point":99999999,"gifts":[]},{"groupId":0,"point":50}]}"""

    @Test fun theMaximumIsTheHighestGroupZeroRewardAndAnEventWithoutRewardsHasNone() {
        assertEquals(EventInfo("샘플 이벤트", 1000, 2000, 2_000_000), parseEventInfo(eventJson))
        assertEquals(EventInfo("샘플 이벤트", 1000, 2000, 2_000_000), parseEventInfo(eventJson.byteInputStream()))
        assertNull(parseEventInfo("""{"name":"포인트 없음","startedAt":1,"endedAt":2,"rewards":[{"groupId":3,"point":10}]}""")!!.maxPoint)
        assertNull(parseEventInfo("""{"name":"","startedAt":1,"endedAt":2}"""))
        assertNull(parseEventInfo("""{"name":"x","startedAt":5,"endedAt":2}"""))
        assertNull(parseEventInfo("{broken"))
    }

    @Test fun onlyRunningEventsAreShownAndTheLastRewardTurnsTheLineGold() {
        val running = EventProgress(1, "A", 10, 100, 1000, 2000)
        val done = EventProgress(2, "B", 100, 100, 1000, 2000)
        val future = EventProgress(3, "C", 10, 100, 1500, 2000)
        val ended = EventProgress(4, "D", 10, 100, 1000, 1500)
        val progress = DashboardProgress(null, listOf(running, done, future, ended))
        assertEquals(listOf(1L, 2L, 3L), progress.running(1500).map { it.eventId })
        assertEquals(listOf(running, done, ended), progress.running(1499))
        assertEquals(listOf(running, done), progress.running(1999) - future)
        assertTrue(progress.running(2000).isEmpty())
        assertFalse(running.complete)
        assertTrue(done.complete)
        assertFalse(EventProgress(5, "E", 10, null, 1, 2).complete)
        assertEquals(0.1f, running.fraction)
        assertEquals(1f, EventProgress(6, "F", 500, 100, 1, 2).fraction)
    }

    @Test fun aCachedEventIsNotFetchedAgainAndTheFileIsWrittenWithoutTemporaryLeftovers() = runBlocking<Unit> {
        val dir = dir()
        val fetched = mutableListOf<Pair<String, Long>>()
        val events = EventInfoStore(File(dir, "events")) { server, id -> fetched += server to id; EventInfo("이벤트 $id", 1000, 2000, 300) }
        val rows = listOf(DashboardEventPoint(7, 50, 1500))
        val loader = loader(dir, events = events)
        val first = loader.resolve(input(1500, null, rows), "KR", network = true)
        val second = loader.resolve(input(1500, null, rows), "KR", network = true)
        assertEquals(listOf("KR" to 7L), fetched)
        assertEquals(first, second)
        assertEquals(EventProgress(7, "이벤트 7", 50, 300, 1000, 2000), first.events.single())
        assertEquals(listOf("KR-7.json"), File(dir, "events").list()!!.toList())
        // The same event id on the other server is a different file.
        loader.resolve(input(1500, null, rows), "JP", network = true)
        assertEquals(listOf("KR" to 7L, "JP" to 7L), fetched)
        dir.deleteRecursively()
    }

    @Test fun theFirstPassReadsOnlyTheCacheAndASecondPassFillsIn() = runBlocking<Unit> {
        val dir = dir()
        var fetches = 0
        val events = EventInfoStore(File(dir, "events")) { _, id -> fetches++; EventInfo("이벤트 $id", 1000, 2000, 300) }
        val loader = loader(dir, events = events)
        val rows = listOf(DashboardEventPoint(7, 50, 1500))
        val emitted = loader.load(input(1500, setOf(21L), rows), "KR").toList()
        assertEquals(2, emitted.size)
        assertTrue(emitted[0].events.isEmpty() && emitted[0].weekly == null)
        assertEquals(1, emitted[1].events.size)
        assertNotNull(emitted[1].weekly)
        // Everything is cached now: one emission and no download.
        fetches = 0
        assertEquals(1, loader.load(input(1500, setOf(21L), rows), "KR").toList().size)
        assertEquals(0, fetches)
        dir.deleteRecursively()
    }

    @Test fun noDownloadHappensWhileAScriptIsRunning() = runBlocking<Unit> {
        val dir = dir()
        var fetches = 0
        val events = EventInfoStore(File(dir, "events")) { _, id -> fetches++; EventInfo("e$id", 1000, 2000, 10) }
        var downloads = 0
        val repository = AtlasRouteRepository(File(dir, "weekly"), fetch = { _, target -> downloads++; target.writeText(master) })
        val loader = DashboardProgressLoader(repository, events, allowDownload = { false })
        val emitted = loader.load(input(1500, setOf(21L), listOf(DashboardEventPoint(7, 50, 1500))), "KR").toList()
        assertEquals(listOf(DashboardProgress()), emitted)
        assertEquals(0, fetches + downloads)
        // The same holds for a network pass asked for directly: every request checks on its own.
        loader.resolve(input(1500, setOf(21L), listOf(DashboardEventPoint(7, 50, 1500))), "KR", network = true)
        assertEquals(0, fetches + downloads)
        dir.deleteRecursively()
    }

    // A script can start while the dashboard is already downloading, so the check is repeated before every request.
    @Test fun aScriptStartingAfterTheMasterDownloadStopsTheEventRequests() = runBlocking<Unit> {
        val dir = dir()
        var allowed = true
        var eventFetches = 0
        val events = EventInfoStore(File(dir, "events")) { _, id -> eventFetches++; EventInfo("e$id", 1000, 2000, 10) }
        val repository = AtlasRouteRepository(File(dir, "weekly"), fetch = { _, target -> target.writeText(master); allowed = false })
        val loader = DashboardProgressLoader(repository, events, allowDownload = { allowed })
        val progress = loader.resolve(input(1500, setOf(21L), listOf(DashboardEventPoint(7, 50, 1500))), "KR", network = true)
        assertNotNull(progress.weekly)
        assertEquals(0, eventFetches)
        assertTrue(progress.events.isEmpty())
        dir.deleteRecursively()
    }

    @Test fun aScriptStartingBetweenTwoEventRequestsStopsTheSecond() = runBlocking<Unit> {
        val dir = dir()
        var allowed = true
        val fetched = mutableListOf<Long>()
        val events = EventInfoStore(File(dir, "events")) { _, id -> fetched += id; allowed = false; EventInfo("e$id", 1000, 2000, 10) }
        val repository = AtlasRouteRepository(File(dir, "weekly"), fetch = { _, target -> target.writeText(master) })
        val loader = DashboardProgressLoader(repository, events, allowDownload = { allowed })
        val rows = listOf(DashboardEventPoint(7, 50, 1500), DashboardEventPoint(8, 60, 1400))
        assertEquals(1, loader.resolve(input(1500, null, rows), "KR", network = true).events.size)
        assertEquals(listOf(7L), fetched)
        dir.deleteRecursively()
    }

    @Test fun aFailedFetchSkipsTheEventAndIsTriedAgainNextTime() = runBlocking<Unit> {
        val dir = dir()
        var online = false
        var fetches = 0
        val events = EventInfoStore(File(dir, "events")) { _, _ -> fetches++; if (online) EventInfo("복구", 1000, 2000, null) else error("offline") }
        val loader = loader(dir, events = events)
        val rows = listOf(DashboardEventPoint(7, 50, 1500))
        assertTrue(loader.resolve(input(1500, null, rows), "KR", network = true).events.isEmpty())
        assertTrue(File(dir, "events").listFiles().orEmpty().isEmpty())
        online = true
        assertEquals("복구", loader.resolve(input(1500, null, rows), "KR", network = true).events.single().name)
        assertEquals(2, fetches)
        dir.deleteRecursively()
    }

    @Test fun aCorruptOrPartialCacheFileIsIgnoredAndFetchedAgain() {
        val dir = dir()
        File(dir, "events").mkdirs()
        val events = EventInfoStore(File(dir, "events")) { _, _ -> EventInfo("새로", 1000, 2000, 10) }
        val file = File(dir, "events/KR-7.json")
        for (bad in listOf("{\"name\":\"반쪽", "", "[]", """{"name":"","startedAt":1,"endedAt":2,"v":1}""", """{"name":"옛","startedAt":1,"endedAt":2,"v":0}""")) {
            file.writeText(bad)
            assertNull(events.cached("KR", 7), bad)
            assertEquals("새로", events.get("KR", 7)!!.name, bad)
            assertEquals("새로", events.cached("KR", 7)!!.name, bad)
        }
        dir.deleteRecursively()
    }

    @Test fun theEventLinesAreOrderedByEndTimeAndLimited() = runBlocking<Unit> {
        val dir = dir()
        val events = EventInfoStore(File(dir, "events")) { _, id -> EventInfo("e$id", 1000, 3000 - id, 10) }
        val rows = (1L..12L).map { DashboardEventPoint(it, it, 2000L - it) }
        val progress = loader(dir, events = events).resolve(input(1500, null, rows), "JP", network = true)
        assertEquals(8, progress.events.size)
        assertEquals(progress.events.sortedBy { it.endedAt }, progress.events)
        dir.deleteRecursively()
    }
}
