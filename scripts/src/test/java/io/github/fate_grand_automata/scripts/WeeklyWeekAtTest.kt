package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** `weekAt` is what the account dashboard uses to name the weekly set an account's mission rows belong to. */
class WeeklyWeekAtTest {
    private fun set(id: Int, start: Long, end: Long, vararg missions: Int, type: String = "weekly") =
        """{"id":$id,"startedAt":$start,"endedAt":$end,"missions":[${missions.joinToString(",") { """{"id":$it,"name":"m$it","type":"$type","conds":[]}""" }}]}"""

    private val master = "[" + listOf(
        set(1, 0, 1000, 11, 12, 13),
        set(2, 1000, 2000, 21, 22),
        set(3, 1000, 2000, 31, type = "daily"),
    ).joinToString(",") + "]"

    private fun repo(dir: java.io.File, calls: MutableList<String>, body: String? = master) = AtlasRouteRepository(dir, fetch = { path, target ->
        calls += path
        target.writeText(body ?: error("offline"))
    })

    @Test fun theSetIsChosenByTimeAndOnlyWeeklySetsCount() {
        val dir = createTempDirectory("week-at").toFile()
        val calls = mutableListOf<String>()
        val repo = repo(dir, calls)
        assertEquals(listOf(1, 1), listOf(repo.weekAt("KR", 500, download = true)!!.id, repo.weekAt("KR", 999, download = true)!!.id))
        assertEquals(2, repo.weekAt("KR", 1000, download = true)!!.id)
        assertEquals(listOf(21, 22), repo.weekAt("KR", 1500, download = true)!!.missions.map { it.id })
        assertNull(repo.weekAt("KR", 5000, download = true))
        dir.deleteRecursively()
    }

    @Test fun withoutDownloadOnlyTheCacheIsRead() {
        val dir = createTempDirectory("week-at").toFile()
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
        val dir = createTempDirectory("week-at").toFile()
        val repo = repo(dir, mutableListOf(), body = null)
        assertNull(repo.weekAt("KR", 500, download = true))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        val broken = repo(dir, mutableListOf(), body = "{not json")
        assertNull(broken.weekAt("KR", 500, download = true))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        dir.deleteRecursively()
    }

    @Test fun aDownloadThatDoesNotCoverTheTimeDoesNotReplaceTheCache() {
        val dir = createTempDirectory("week-at").toFile()
        repo(dir, mutableListOf()).weekAt("KR", 500, download = true)
        val before = dir.resolve("KR-missions.json").readText()
        val newer = repo(dir, mutableListOf(), body = "[" + set(9, 8000, 9000, 91) + "]")
        assertNull(newer.weekAt("KR", 5000, download = true))
        assertEquals(before, dir.resolve("KR-missions.json").readText())
        dir.deleteRecursively()
    }
}
