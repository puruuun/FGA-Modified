package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class GameNamesTest {
    @TempDir lateinit var directory: File
    private val run = RunGains(items = listOf(GainedItem(2, 6510, 1), GainedItem(3, 9, 1)), bond = mapOf(10L to 1L))

    @Test fun failedDownloadsUseStableFallbacksAndAreThrottledAcrossInstances() = runBlocking {
        var calls = 0
        val downloader: (String) -> String = { calls++; error("offline") }
        val names = GameNames(directory, downloader) { 1000 }.resolve(listOf(run))
        assertEquals("Item #6510", names.item(6510))
        assertEquals("Servant #10", names.servant(10))
        assertEquals("CE #9", names.equip(9))
        assertEquals("CE #9", names.drop(3, 9))
        assertEquals("Servant #10", names.drop(1, 10))
        assertEquals(3, calls)
        GameNames(directory, downloader) { 2000 }.resolve(listOf(run))
        assertEquals(3, calls)
    }

    @Test fun downloadsOnlyPublicKrExportsAndStoresOnlyNames() = runBlocking {
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { address ->
            urls += address
            """[{"id":6510,"name":"증거","secret":"not-retained"},{"id":10,"name":"서번트"},{"id":9,"name":"예장"}]"""
        }) { 1000 }.resolve(listOf(run))
        assertEquals("증거", names.item(6510))
        assertEquals("서번트", names.servant(10))
        assertEquals("예장", names.equip(9))
        assertEquals(setOf("nice_item", "basic_servant", "basic_equip").map {
            "https://api.atlasacademy.io/export/KR/$it.json"
        }.toSet(), urls.toSet())
        assertFalse(File(directory, "nice_item.json").readText().contains("secret"))
        val loaded = GameNames(directory, { error("must use disk cache") }) { 1001 }.resolve(listOf(run))
        assertEquals(names, loaded)
    }

    @Test fun unknownIdRefreshesAfterOneDayAndFailureKeepsGoodCache() = runBlocking {
        var time = 1000L
        var calls = 0
        val cache = GameNames(directory, {
            calls++
            if (calls > 3) error("offline")
            """[{"id":100,"name":"황금색 과일"}]"""
        }) { time }
        assertEquals("황금색 과일", cache.resolve(listOf(run)).item(100))
        time += 86_399_999
        cache.resolve(listOf(run))
        assertEquals(3, calls)
        time++
        assertEquals("황금색 과일", cache.resolve(listOf(run)).item(100))
        assertEquals(6, calls)
    }

    @Test fun knownIdsDoNotTriggerDailyRefresh() = runBlocking {
        var calls = 0
        var time = 1000L
        val cache = GameNames(directory, {
            calls++
            """[{"id":100,"name":"a"},{"id":101,"name":"b"},{"id":102,"name":"c"},{"id":104,"name":"d"}]"""
        }) { time }
        cache.resolve(emptyList())
        time += 2 * 86_400_000
        cache.resolve(emptyList())
        assertEquals(3, calls)
    }

    @Test fun malformedRowsAreSkipped() {
        assertEquals(mapOf(5L to "Five"), GameNames.parseNames("""[null,{}, {"id":5,"name":"Five"},{"id":6,"name":""}]"""))
    }
}
