package io.github.fate_grand_automata.capture

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasVersionsTest {
    @TempDir lateinit var directory: File

    @Test fun firstCallRequestsInfoOnceCachesWithinHourAndRefreshesAfterHour() {
        var time = 1_000L
        var jpHash = "2b8b55"
        var krHash = "971d09"
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            """{"JP":{"hash":"$jpHash","timestamp":1794542509},"NA":{"hash":"na1"},"CN":{"hash":"cn1"},"KR":{"hash":"$krHash","timestamp":1794539746},"TW":{"hash":"tw1"}}"""
        }

        val first = AtlasVersions(directory, downloader) { time }.current()
        assertEquals(mapOf("KR" to "971d09", "JP" to "2b8b55"), first)
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)

        // Within 1 hour: returns cached hashes from disk without requesting /info again.
        time += AtlasVersions.CHECK_INTERVAL_MS - 1
        val second = AtlasVersions(directory, { error("must use saved state") }) { time }.current()
        assertEquals(mapOf("KR" to "971d09", "JP" to "2b8b55"), second)

        // At 1 hour: requests /info once again and picks up updated hashes.
        time += 1
        jpHash = "jp-new"
        krHash = "kr-new"
        val third = AtlasVersions(directory, downloader) { time }.current()
        assertEquals(mapOf("KR" to "kr-new", "JP" to "jp-new"), third)
        assertEquals(listOf(AtlasVersions.INFO_URL, AtlasVersions.INFO_URL), urls)
    }

    @Test fun failuresReturnNullAndAreThrottledForOneHour() {
        val failureModes: List<(String) -> String> = listOf(
            { error("offline") },
            { "not-json" },
            { "[]" },
            { """{"KR":{"hash":"  "},"JP":{"hash":"2b8b55"}}""" },
            { """{"KR":{"hash":"971d09"}}""" },
            { """{"KR":{"hash":123},"JP":{"hash":"2b8b55"}}""" },
        )

        failureModes.forEachIndexed { index, failingDownloader ->
            val subDir = File(directory, "case-$index").apply { mkdirs() }
            var time = 1_000L
            var calls = 0
            val countingFail: (String) -> String = { url ->
                assertEquals(AtlasVersions.INFO_URL, url)
                calls++
                failingDownloader(url)
            }

            assertNull(AtlasVersions(subDir, countingFail) { time }.current())
            assertEquals(1, calls)

            // Within 1 hour: throttled without calling download.
            time += AtlasVersions.CHECK_INTERVAL_MS - 1
            assertNull(AtlasVersions(subDir, countingFail) { time }.current())
            assertEquals(1, calls)

            // After 1 hour: retries /info and recovers on valid JSON.
            time += 1
            val recovered = AtlasVersions(subDir, { url ->
                assertEquals(AtlasVersions.INFO_URL, url)
                calls++
                """{"KR":{"hash":"kr1"},"JP":{"hash":"jp1"}}"""
            }) { time }.current()
            assertEquals(mapOf("KR" to "kr1", "JP" to "jp1"), recovered)
            assertEquals(2, calls)
        }
    }

    @Test fun corruptStateFileReadsAsNeverChecked() {
        File(directory, "atlas_info.json").writeText("{{not-valid-json")
        val urls = mutableListOf<String>()
        val result = AtlasVersions(directory, { url ->
            urls += url
            """{"KR":{"hash":"kr1"},"JP":{"hash":"jp1"}}"""
        }) { 1_000L }.current()

        assertEquals(mapOf("KR" to "kr1", "JP" to "jp1"), result)
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)
        assertTrue(File(directory, "atlas_info.json").isFile)
    }
}
