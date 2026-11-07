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

    @Test fun eventNamesOnlyUseKrBasicEquipAndKeepDailyRetryRules() = runBlocking {
        var time = 1000L
        val urls = mutableListOf<String>()
        var offline = false
        fun cache() = GameNames(directory, { address ->
            urls += address
            if (offline) error("offline")
            """[{"id":10,"name":"샘플 예장"}]"""
        }) { time }
        assertEquals("샘플 예장", cache().eventEquips(listOf(10)).equips[10])
        cache().eventEquips(listOf(20))
        assertEquals(1, urls.size)
        time += 86_400_000
        offline = true
        assertEquals("샘플 예장", cache().eventEquips(listOf(10, 20)).equips[10])
        cache().eventEquips(listOf(20))
        assertEquals(List(2) { "https://api.atlasacademy.io/export/KR/basic_equip.json" }, urls)
    }

    @Test fun failedDownloadsUseStableFallbacksAndAreThrottledAcrossInstances() = runBlocking {
        var calls = 0
        val downloader: (String) -> String = { calls++; error("offline") }
        val names = GameNames(directory, downloader) { 1000 }.resolve(listOf(run))
        assertEquals("Item #6510", names.item(6510))
        assertEquals("Servant #10", names.servant(10))
        assertEquals("CE #9", names.equip(9))
        assertEquals("CE #9", names.drop(3, 9))
        assertEquals("Servant #10", names.drop(1, 10))
        assertEquals(4, calls) // Three KR lists and one missing JP item.
        GameNames(directory, downloader) { 2000 }.resolve(listOf(run))
        assertEquals(4, calls)
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
        // Other drop types can share an object ID with an item, but must not borrow its name.
        assertEquals("Item #6510", names.drop(99, 6510))
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
        assertEquals(4, calls)
        time++
        assertEquals("황금색 과일", cache.resolve(listOf(run)).item(100))
        assertEquals(8, calls)
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

    @Test fun freshNameAttemptDoesNotBlockMissingIconMap() = runBlocking {
        File(directory, "nice_item.json").writeText("""{"100":"Fruit","101":"Silver","102":"Copper","104":"Bronze"}""")
        File(directory, "nice_item.attempt").writeText("1000")
        File(directory, "basic_servant.json").writeText("""{"10":"Servant"}""")
        File(directory, "basic_equip.json").writeText("""{"9":"CE"}""")
        val urls = mutableListOf<String>()
        val icon = "https://static.atlasacademy.io/KR/Items/100.png"
        val names = GameNames(directory, { urls += it; """[{"id":100,"name":"Fruit","icon":"$icon"}]""" }) { 1001 }.resolve(emptyList())
        assertEquals(listOf("https://api.atlasacademy.io/export/KR/nice_item.json"), urls)
        assertEquals(mapOf(100L to icon), names.itemIcons)
        assertEquals("1000", File(directory, "nice_item.attempt").readText())
        assertEquals(names.itemIcons, GameNames(directory, { error("must reuse icons") }) { 1002 }.resolve(emptyList()).itemIcons)
    }

    @Test fun missingIconsHaveIndependentPersistentFailureBackoff() = runBlocking {
        File(directory, "nice_item.attempt").writeText("1000")
        listOf("basic_servant", "basic_equip").forEach { File(directory, "$it.json").writeText("""{"1":"cached"}""") }
        var attempts = 0
        val download: (String) -> String = { attempts++; error("offline") }
        GameNames(directory, download) { 1001 }.resolve(emptyList())
        GameNames(directory, download) { 1002 }.resolve(emptyList())
        assertEquals(1, attempts)
        GameNames(directory, download) { 1001 + 86_400_000L }.resolve(emptyList())
        assertEquals(2, attempts)
    }

    @Test fun iconsAreKeptAlongsideNamesAndReloadedWithoutNetwork() = runBlocking {
        val url = "https://static.atlasacademy.io/KR/Items/100.png"
        val names = GameNames(directory, { """[{"id":100,"name":"Fruit","icon":"$url"}]""" }) { 1000 }.resolve(emptyList())
        assertEquals(mapOf(100L to url), names.itemIcons)
        assertEquals(names, GameNames(directory, { error("offline") }) { 1001 }.resolve(emptyList()))
    }

    @Test fun iconParserRejectsNonPublicAndMalformedUrls() {
        assertEquals(mapOf(1L to "https://static.atlasacademy.io/KR/Items/5.png"), GameNames.parseIcons("""[
            {"id":1,"icon":"https://static.atlasacademy.io/KR/Items/5.png"},
            {"id":2,"icon":"http://static.atlasacademy.io/x"}, {"id":3,"icon":"https://private.test/x"},
            {"id":4,"icon":"file:///private"}, {"id":5,"icon":null}, null]
        """))
    }
}
