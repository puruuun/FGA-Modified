package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** `weekAt` is what the account dashboard uses to name the weekly set an account's mission rows belong to. */
class WeeklyWeekAtTest {
    private fun set(id: Int, start: Long, end: Long, vararg missions: Int, type: String = "weekly") =
        """{"id":$id,"startedAt":$start,"endedAt":$end,"missions":[${missions.joinToString(",") { """{"id":$it,"name":"m$it","type":"$type","conds":[]}""" }}]}"""

    // Every folder is removed after the test, also when an assertion fails halfway.
    private val made = mutableListOf<File>()
    private fun dir() = createTempDirectory("week-at").toFile().also(made::add)
    @AfterTest fun removeFolders() { made.forEach { it.deleteRecursively() }; made.clear() }

    private val master = "[" + listOf(
        set(1, 0, 1000, 11, 12, 13),
        set(2, 1000, 2000, 21, 22),
        set(3, 1000, 2000, 31, type = "daily"),
    ).joinToString(",") + "]"

    private fun repo(dir: File, calls: MutableList<String>, body: String? = master) = AtlasRouteRepository(dir, fetch = { path, target ->
        calls += path
        target.writeText(body ?: error("offline"))
    })

    @Test fun theSetIsChosenByTimeAndOnlyWeeklySetsCount() {
        val dir = dir()
        val repo = repo(dir, mutableListOf())
        assertEquals(listOf(1, 1), listOf(repo.weekAt("KR", 500, download = true)!!.id, repo.weekAt("KR", 999, download = true)!!.id))
        assertEquals(2, repo.weekAt("KR", 1000, download = true)!!.id)
        assertEquals(listOf(21, 22), repo.weekAt("KR", 1500, download = true)!!.missions.map { it.id })
        assertNull(repo.weekAt("KR", 5000, download = true))
        dir.deleteRecursively()
    }

    @Test fun withoutDownloadOnlyTheCacheIsRead() {
        val dir = dir()
        val calls = mutableListOf<String>()
        val repo = repo(dir, calls)
        assertNull(repo.weekAt("KR", 500, download = false))
        assertTrue(calls.isEmpty())
        repo.weekAt("KR", 500, download = true)
        assertEquals(1, calls.size)
        assertEquals(1, repo.weekAt("KR", 500, download = false)!!.id)
        assertEquals(1, calls.size)
        dir.deleteRecursively()
    }

    @Test fun aFailedDownloadReturnsNullAndKeepsNoPartialFile() {
        val dir = dir()
        assertNull(repo(dir, mutableListOf(), body = null).weekAt("KR", 500, download = true))
        assertNull(repo(dir, mutableListOf(), body = "{not json").weekAt("KR", 600, download = true))
        assertEquals(listOf("KR-missions-attempt.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test fun aDownloadThatDoesNotCoverTheTimeDoesNotReplaceTheCache() {
        val dir = dir()
        repo(dir, mutableListOf()).weekAt("KR", 500, download = true)
        val before = dir.resolve("KR-missions.json").readText()
        val newer = repo(dir, mutableListOf(), body = "[" + set(9, 8000, 9000, 91) + "]")
        assertNull(newer.weekAt("KR", 5000, download = true))
        assertEquals(before, dir.resolve("KR-missions.json").readText())
        dir.deleteRecursively()
    }

    // The weekly route and the dashboard may download the same list at the same time.
    @Test fun weekAtUsesItsOwnDownloadFileSoARunningWeeklyLoadStillSucceeds() {
        val dir = dir()
        val dashboard = repo(dir, mutableListOf())
        val route = AtlasRouteRepository(dir, fetch = { path, target ->
            if (path.endsWith("nice_master_mission.json")) {
                target.writeText(master)
                // The dashboard looks the week up after the route's download and before the route reads it.
                dashboard.weekAt("KR", 500, download = true)
            } else error("stop after the missions")
        })
        val failure = assertFailsWith<IllegalStateException> { route.load("KR", 500) }
        assertEquals("stop after the missions", failure.message)
        dir.deleteRecursively()
    }

    // An account whose snapshot is older than the sets in the master must not download it on every opening.
    @Test fun aFailedOrNonCoveringAttemptIsNotRepeatedForTheSameSnapshotWithinADay() {
        val dir = dir()
        val calls = mutableListOf<String>()
        val repo = repo(dir, calls)
        val t0 = 1_000_000L
        assertNull(repo.weekAt("KR", 5000, download = true, now = t0))
        assertEquals(1, calls.size)
        assertNull(repo.weekAt("KR", 5000, download = true, now = t0 + 3600))
        assertEquals(1, calls.size)
        // Another snapshot, and the same one a day later, are tried.
        repo.weekAt("KR", 6000, download = true, now = t0 + 3600)
        assertEquals(2, calls.size)
        repo.weekAt("KR", 5000, download = true, now = t0 + 24 * 3600 + 1)
        assertEquals(3, calls.size)
        // A server has its own marker.
        repo.weekAt("JP", 5000, download = true, now = t0 + 3600)
        assertEquals(4, calls.size)
        dir.deleteRecursively()
    }

    @Test fun aFailedDownloadIsAlsoNotRepeatedAndASuccessClearsTheMarker() {
        val dir = dir()
        val failing = mutableListOf<String>()
        assertNull(repo(dir, failing, body = null).weekAt("KR", 500, download = true, now = 100))
        assertNull(repo(dir, failing, body = null).weekAt("KR", 500, download = true, now = 200))
        assertEquals(1, failing.size)
        // Later it works, and the marker is gone.
        assertEquals(1, repo(dir, mutableListOf()).weekAt("KR", 500, download = true, now = 100 + 25 * 3600)!!.id)
        assertFalse(File(dir, "KR-missions-attempt.json").exists())
        dir.deleteRecursively()
    }

    // The master is large, so a later opening is answered from the small side file.
    @Test fun aLaterOpeningDoesNotParseTheMasterAgain() {
        val dir = dir()
        val repo = repo(dir, mutableListOf())
        assertEquals(listOf(11, 12, 13), repo.weekAt("KR", 500, download = true)!!.missions.map { it.id })
        val master = dir.resolve("KR-missions.json")
        val stamp = master.lastModified()
        // Unreadable content of the same size and time: only the side file can still answer.
        master.writeText("x".repeat(master.length().toInt()))
        master.setLastModified(stamp)
        val week = repo.weekAt("KR", 700, download = false)!!
        assertEquals(1, week.id)
        assertEquals(listOf(11, 12, 13), week.missions.map { it.id })
        assertEquals(0L to 1000L, week.start to week.end)
        dir.deleteRecursively()
    }

    @Test fun aChangedMasterIsParsedAgain() {
        val dir = dir()
        repo(dir, mutableListOf()).weekAt("KR", 500, download = true)
        dir.resolve("KR-missions.json").writeText("[" + set(1, 0, 1000, 11, 99) + "]")
        assertEquals(listOf(11, 99), repo(dir, mutableListOf()).weekAt("KR", 500, download = false)!!.missions.map { it.id })
        dir.deleteRecursively()
    }

    @Test fun aTimeNoSetCoversIsRememberedSoItIsNotParsedAgain() {
        val dir = dir()
        val repo = repo(dir, mutableListOf())
        repo.weekAt("KR", 500, download = true)
        assertNull(repo.weekAt("KR", 5000, download = false))
        // Same size and time, but now a set covers 5000: only the remembered miss still answers null.
        val master = dir.resolve("KR-missions.json")
        val stamp = master.lastModified()
        master.writeText(("[" + set(7, 4000, 6000, 71) + "]").padEnd(master.length().toInt()))
        master.setLastModified(stamp)
        assertNull(repo.weekAt("KR", 5000, download = false))
        assertTrue(dir.resolve("KR-missions-view.json").readText().contains("5000"))
        dir.deleteRecursively()
    }

    // Leaving the dashboard and coming back can start a second pass while the first one is still downloading.
    @Test fun twoOverlappingPassesBothGetTheWeekAndLeaveNoPartFile() {
        val dir = dir()
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val fetches = java.util.concurrent.atomic.AtomicInteger()
        fun repo() = AtlasRouteRepository(dir, fetch = { _, target ->
            fetches.incrementAndGet()
            target.writeText(master)
            // Without a lock both passes would sit here together, sharing one part file.
            runCatching { barrier.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        })
        val results = java.util.concurrent.ConcurrentLinkedQueue<Int?>()
        val threads = List(2) { Thread { results += repo().weekAt("KR", 500, download = true)?.id } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(listOf<Int?>(1, 1), results.toList())
        assertEquals(1, fetches.get())
        assertTrue(dir.list()!!.none { it.endsWith(".part") })
    }

    @Test fun theWeeklyRoutesOwnMasterFileIsNeverRewrittenByTheSideFile() {
        val dir = dir()
        val repo = repo(dir, mutableListOf())
        repo.weekAt("KR", 500, download = true)
        assertEquals(master, dir.resolve("KR-missions.json").readText())
        repo.weekAt("KR", 1500, download = false)
        assertEquals(master, dir.resolve("KR-missions.json").readText())
        dir.deleteRecursively()
    }
}
