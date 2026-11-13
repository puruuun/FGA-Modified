package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaCollectionNumbers
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
        assertEquals(7, calls) // Three KR lists, three JP fallback lists for missing ids, and one missing JP item.
        GameNames(directory, downloader) { 2000 }.resolve(listOf(run))
        assertEquals(7, calls)
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
        assertEquals(7, calls)
        time++
        assertEquals("황금색 과일", cache.resolve(listOf(run)).item(100))
        assertEquals(14, calls)
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

    @Test fun friendNamesKeepAtlasClassesAcrossLegacyCacheMigrationAndJpFallback() = runBlocking {
        File(directory, "basic_servant.json").writeText("""{"1":"한국 서번트"}""")
        File(directory, "basic_equip.json").writeText("""{"90":"예장"}""")
        val cache = GameNames(directory, { url ->
            when {
                "/JP/basic_servant" in url -> """[{"id":1,"name":"JP overlap","className":"saber"},{"id":2,"name":"JP servant","className":"moonCancer"}]"""
                "/KR/basic_servant" in url -> """[{"id":1,"name":"한국 서번트","className":"caster"}]"""
                else -> """[{"id":100,"name":"item"}]"""
            }
        }) { 1000 }
        val names = cache.friends(setOf(1, 2), setOf(90), hasJpAccount = true)
        assertEquals("한국 서번트", names.servants[1])
        assertEquals("caster", names.servantData[1]?.className)
        assertEquals("JP servant", names.servants[2])
        assertEquals("moonCancer", names.servantData[2]?.className)
        val offline = GameNames(directory, { error("must use cache") }) { 1001 }.friends(setOf(1, 2), setOf(90), hasJpAccount = true)
        assertEquals(names, offline)
    }

    @Test fun collectionNumbersIsCacheOnlyUnionSkippingZeroAndNullAndNeverThrows() {
        val cache = GameNames(directory, { error("must never download") }) { 1000 }
        assertEquals(ChaldeaCollectionNumbers.EMPTY, cache.collectionNumbers())

        File(directory, "basic_equip_details.json").writeText(
            """[{"id":9300010,"collectionNo":11},{"id":9300020,"collectionNo":0},{"id":9300030,"collectionNo":null}]""",
        )
        File(directory, "jp_basic_equip_details.json").writeText(
            """[{"id":9300010,"collectionNo":11},{"id":9400010,"collectionNo":21}]""",
        )
        File(directory, "basic_command_code_details.json").writeText("not-json-at-all")
        File(directory, "jp_basic_command_code_details.json").writeText(
            """[{"id":80001,"collectionNo":1},{"id":80002,"collectionNo":0}]""",
        )
        File(directory, "basic_servant_details.json").writeText(
            """[{"id":100100,"collectionNo":1},{"id":100200,"collectionNo":0},{"id":100300,"collectionNo":null}]""",
        )
        File(directory, "jp_basic_servant_details.json").writeText(
            """[{"id":100100,"collectionNo":1},{"id":200100,"collectionNo":12}]""",
        )

        val got = cache.collectionNumbers()
        assertEquals(mapOf(9300010L to 11L, 9400010L to 21L), got.equips)
        assertEquals(mapOf(80001L to 1L), got.commandCodes)
        assertEquals(mapOf(100100L to 1L, 200100L to 12L), got.servants)
    }

    @Test fun ensureChaldeaCollectionListsDownloadsKrAndJpOncePerDayAndDetectsUpdates() = runBlocking {
        val urls = mutableListOf<String>()
        var time = 1000L
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                url.endsWith("/JP/basic_equip.json") -> """[{"id":9400010,"collectionNo":21}]"""
                url.endsWith("/KR/basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                url.endsWith("/JP/basic_command_code.json") -> """[{"id":80002,"collectionNo":2}]"""
                url.endsWith("/KR/basic_servant.json") -> """[{"id":100100,"collectionNo":1}]"""
                url.endsWith("/JP/basic_servant.json") -> """[{"id":200100,"collectionNo":12}]"""
                else -> "[]"
            }
        }
        val names = GameNames(directory, downloader) { time }
        assertTrue(names.ensureChaldeaCollectionLists())
        assertEquals(
            setOf(
                "https://api.atlasacademy.io/export/KR/basic_equip.json",
                "https://api.atlasacademy.io/export/JP/basic_equip.json",
                "https://api.atlasacademy.io/export/KR/basic_command_code.json",
                "https://api.atlasacademy.io/export/JP/basic_command_code.json",
                "https://api.atlasacademy.io/export/KR/basic_servant.json",
                "https://api.atlasacademy.io/export/JP/basic_servant.json",
            ),
            urls.toSet(),
        )
        assertEquals(mapOf(9300010L to 11L, 9400010L to 21L), names.collectionNumbers().equips)
        assertEquals(mapOf(80001L to 1L, 80002L to 2L), names.collectionNumbers().commandCodes)
        assertEquals(mapOf(100100L to 1L, 200100L to 12L), names.collectionNumbers().servants)

        // Second call immediately: cached and fresh -> no downloads, returns false
        urls.clear()
        assertFalse(names.ensureChaldeaCollectionLists())
        assertEquals(emptyList(), urls)
    }
}
