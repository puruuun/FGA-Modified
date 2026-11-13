package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
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
    private fun infoJson(kr: String, jp: String) =
        """{"KR":{"hash":"$kr","timestamp":1},"JP":{"hash":"$jp","timestamp":2}}"""

    @Test fun failedDownloadsUseStableFallbacksAndAreThrottledAcrossInstances() = runBlocking {
        var calls = 0
        val downloader: (String) -> String = { url -> if (url != AtlasVersions.INFO_URL) calls++; error("offline") }
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
            if (address == AtlasVersions.INFO_URL) error("offline")
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
        val cache = GameNames(directory, { url ->
            if (url == AtlasVersions.INFO_URL) error("offline")
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
        val cache = GameNames(directory, { url ->
            if (url == AtlasVersions.INFO_URL) error("offline")
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
        val names = GameNames(directory, { url ->
            if (url == AtlasVersions.INFO_URL) error("offline")
            urls += url
            """[{"id":100,"name":"Fruit","icon":"$icon"}]"""
        }) { 1001 }.resolve(emptyList())
        assertEquals(listOf("https://api.atlasacademy.io/export/KR/nice_item.json"), urls)
        assertEquals(mapOf(100L to icon), names.itemIcons)
        assertEquals("1000", File(directory, "nice_item.attempt").readText())
        assertEquals(names.itemIcons, GameNames(directory, { error("must reuse icons") }) { 1002 }.resolve(emptyList()).itemIcons)
    }

    @Test fun missingIconsHaveIndependentPersistentFailureBackoff() = runBlocking {
        File(directory, "nice_item.attempt").writeText("1000")
        listOf("basic_servant", "basic_equip").forEach { File(directory, "$it.json").writeText("""{"1":"cached"}""") }
        var attempts = 0
        val download: (String) -> String = { url -> if (url != AtlasVersions.INFO_URL) attempts++; error("offline") }
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
            if (url == AtlasVersions.INFO_URL) error("offline")
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

    @Test fun versionModeSkipsRedownloadWhenJpHashMatchesEvenWithMissingIdAndRefreshesOnHashChange() = runBlocking {
        var time = 1_000L
        var jpHash = "jp1"
        var hasNewServant = false
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", jpHash)
                url.endsWith("/JP/basic_servant.json") -> if (hasNewServant) {
                    """[{"id":100100,"name":"古いJP"},{"id":705500,"name":"新しいサーヴァント"}]"""
                } else {
                    """[{"id":100100,"name":"古いJP"}]"""
                }
                else -> """[{"id":100,"name":"ダミー"}]"""
            }
        }
        val missingRun = listOf(RunGains(bond = mapOf(705500L to 1L)))
        val first = GameNames(directory, downloader) { time }.resolve(missingRun, hasJpAccount = true)
        assertEquals("Servant #705500", first.servant(705500))
        assertEquals("jp1", File(directory, "jp_basic_servant_details.hash").readText())

        // Even 2 days later, as long as JP hash is unchanged, JP list is NOT re-downloaded for missing 705500.
        urls.clear()
        time += 2 * 86_400_000L
        val stillMissing = GameNames(directory, downloader) { time }.resolve(missingRun, hasJpAccount = true)
        assertEquals("Servant #705500", stillMissing.servant(705500))
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)

        // Once JP hash changes and 1 hour passes, JP list downloads once and 705500 resolves.
        urls.clear()
        time += AtlasVersions.CHECK_INTERVAL_MS
        jpHash = "jp2"
        hasNewServant = true
        val updated = GameNames(directory, downloader) { time }.resolve(missingRun, hasJpAccount = true)
        assertEquals("新しいサーヴァント", updated.servant(705500))
        assertEquals("jp2", File(directory, "jp_basic_servant_details.hash").readText())
        assertTrue(urls.any { it.endsWith("/JP/basic_servant.json") })
        assertTrue(urls.none { "/KR/" in it })
    }

    @Test fun versionModeFailedDownloadKeepsOldNamesAndRetriesAfterOneHour() = runBlocking {
        var time = 1_000L
        var jpHash = "jp1"
        var failJpServant = false
        var newName = false
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", jpHash)
                url.endsWith("/JP/basic_servant.json") -> {
                    if (failJpServant) error("offline")
                    if (newName) """[{"id":100100,"name":"新しいJP"}]""" else """[{"id":100100,"name":"古いJP"}]"""
                }
                else -> """[{"id":100,"name":"ダミー"}]"""
            }
        }
        val names1 = GameNames(directory, downloader) { time }.friends(listOf(100100), emptyList(), hasJpAccount = true)
        assertEquals("古いJP", names1.servant(100100))

        // Hash updates, but list download fails -> keeps old name and hash, throttled for 1 hour.
        time += AtlasVersions.CHECK_INTERVAL_MS
        jpHash = "jp2"
        failJpServant = true
        urls.clear()
        val names2 = GameNames(directory, downloader) { time }.friends(listOf(100100), emptyList(), hasJpAccount = true)
        assertEquals("古いJP", names2.servant(100100))
        assertEquals("jp1", File(directory, "jp_basic_servant_details.hash").readText())
        assertEquals(1, urls.count { it.endsWith("/JP/basic_servant.json") })

        // Within 1 hour: not retried.
        time += AtlasVersions.CHECK_INTERVAL_MS - 1
        urls.clear()
        GameNames(directory, downloader) { time }.friends(listOf(100100), emptyList(), hasJpAccount = true)
        assertTrue(urls.none { it.endsWith("/JP/basic_servant.json") })

        // After 1 hour: retries and updates.
        time += 1
        failJpServant = false
        newName = true
        urls.clear()
        val names3 = GameNames(directory, downloader) { time }.friends(listOf(100100), emptyList(), hasJpAccount = true)
        assertEquals("新しいJP", names3.servant(100100))
        assertEquals("jp2", File(directory, "jp_basic_servant_details.hash").readText())
        assertEquals(1, urls.count { it.endsWith("/JP/basic_servant.json") })
    }

    @Test fun versionModeDoesNotRedownloadKrListForJpOnlyIdWhileKrHashUnchanged() = runBlocking {
        var time = 1_000L
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", "jp1")
                url.endsWith("/KR/basic_servant.json") -> """[{"id":10,"name":"한국 서번트"}]"""
                url.endsWith("/JP/basic_servant.json") -> """[{"id":705500,"name":"JP専用"}]"""
                else -> """[{"id":100,"name":"품목"}]"""
            }
        }
        val jpRun = listOf(RunGains(bond = mapOf(705500L to 1L)))
        val first = GameNames(directory, downloader) { time }.resolve(jpRun)
        assertEquals("JP専用", first.servant(705500))

        urls.clear()
        time += 86_400_000L
        GameNames(directory, downloader) { time }.resolve(jpRun)
        time += 86_400_000L
        GameNames(directory, downloader) { time }.resolve(jpRun)
        assertTrue(urls.none { "/KR/" in it }, "KR lists must not re-download when KR hash is unchanged: $urls")
    }

    @Test fun versionModeDoesNotRedownloadKrNiceItemForMissingIdWhileKrHashUnchanged() = runBlocking {
        var time = 1_000L
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", "jp1")
                else -> """[{"id":100,"name":"과일"}]"""
            }
        }
        val names = GameNames(directory, downloader) { time }
        names.resolve(emptyList())
        names.dashboard(emptyList())

        urls.clear()
        time += 86_400_000L
        val missingRun = listOf(RunGains(items = listOf(GainedItem(2, 999999L, 1))))
        names.resolve(missingRun)
        val state = AccountState(mapOf("userItem" to """[{"itemId":999999,"num":1}]"""), "[]", null, 1L, 2L)
        names.dashboard(listOf(state))
        assertTrue(urls.none { it.endsWith("/KR/nice_item.json") }, "KR nice_item must not re-download when KR hash is unchanged: $urls")
    }

    @Test fun versionModeDownloadsOldInstallWithoutHashOnce() = runBlocking {
        // Pre-populate cache files without any *_details.hash (old install).
        File(directory, "nice_item.json").writeText("""{"100":"과일"}""")
        File(directory, "nice_item_details.json").writeText("""[{"id":100,"name":"과일"}]""")
        File(directory, "item_icons.json").writeText("""{"100":"https://static.atlasacademy.io/KR/Items/100.png"}""")
        File(directory, "basic_servant.json").writeText("""{"10":"서번트"}""")
        File(directory, "basic_servant_details.json").writeText("""[{"id":10,"name":"서번트"}]""")
        File(directory, "basic_equip.json").writeText("""{"9":"예장"}""")
        File(directory, "basic_equip_details.json").writeText("""[{"id":9,"name":"예장"}]""")
        listOf("nice_item", "basic_servant", "basic_equip").forEach {
            File(directory, "${it}_details.fetched").writeText("1000")
            File(directory, "${it}_details.attempt").writeText("1000")
        }

        var time = 1_001L
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", "jp1")
                else -> """[{"id":100,"name":"새 과일"},{"id":10,"name":"새 서번트"},{"id":9,"name":"새 예장"}]"""
            }
        }
        val names = GameNames(directory, downloader) { time }
        val resolved = names.resolve(listOf(RunGains(items = listOf(GainedItem(2, 100, 1), GainedItem(3, 9, 1)), bond = mapOf(10L to 1L))))
        assertEquals("새 과일", resolved.item(100))
        assertEquals(
            setOf(
                "https://api.atlasacademy.io/export/KR/nice_item.json",
                "https://api.atlasacademy.io/export/KR/basic_servant.json",
                "https://api.atlasacademy.io/export/KR/basic_equip.json",
            ),
            urls.filterNot { it == AtlasVersions.INFO_URL }.toSet(),
        )
        listOf("nice_item", "basic_servant", "basic_equip").forEach {
            assertEquals("kr1", File(directory, "${it}_details.hash").readText())
            assertEquals("kr1", File(directory, "$it.json.hash").readText())
        }

        // Next call after 1 hour with same hash does not re-download.
        urls.clear()
        time += AtlasVersions.CHECK_INTERVAL_MS
        names.resolve(listOf(RunGains(items = listOf(GainedItem(2, 100, 1), GainedItem(3, 9, 1)), bond = mapOf(10L to 1L))))
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)
    }

    @Test fun collectionNumbersNeverRequestsInfoOrLists() {
        File(directory, "atlas_info.json").writeText("""{"checkedAt":1000,"attemptAt":1000,"kr":"kr1","jp":"jp1"}""")
        File(directory, "basic_servant_details.json").writeText("""[{"id":100100,"collectionNo":1}]""")
        val cache = GameNames(directory, { error("must never request network: $it") }) { 100L * 86_400_000L }
        val numbers = cache.collectionNumbers()
        assertEquals(mapOf(100100L to 1L), numbers.servants)
    }

    @Test fun fallbackDownloadKeepsExistingHashFile() = runBlocking {
        var time = 1_000L
        var online = true
        val cache = GameNames(directory, { url ->
            if (url == AtlasVersions.INFO_URL) {
                if (!online) error("offline") else infoJson("kr1", "jp1")
            } else {
                """[{"id":100100,"name":"서번트","collectionNo":1}]"""
            }
        }) { time }
        cache.ensureChaldeaCollectionLists()
        cache.resolve(listOf(RunGains(bond = mapOf(100100L to 1L))))
        assertEquals("kr1", File(directory, "basic_servant_details.hash").readText())
        assertEquals("kr1", File(directory, "basic_servant.json.hash").readText())

        // 8 days later, /info is offline -> fallback refreshes and keeps existing hash files.
        online = false
        time += 8 * 86_400_000L
        cache.ensureChaldeaCollectionLists()
        cache.resolve(listOf(RunGains(bond = mapOf(100100L to 1L, 999999L to 1L))))
        assertEquals("kr1", File(directory, "basic_servant_details.hash").readText())
        assertEquals("kr1", File(directory, "basic_servant.json.hash").readText())
    }

    @Test fun versionModeDownloadsKrNameMapAfterCollectionListsUpdatedDetailsHash() = runBlocking {
        var time = 1_000L
        var krHash = "kr1"
        var hasNewServant = false
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> infoJson(krHash, "jp1")
                url.endsWith("/KR/basic_servant.json") -> if (hasNewServant) {
                    """[{"id":10,"name":"구 서번트","collectionNo":1},{"id":99,"name":"새 한국 서번트","collectionNo":2}]"""
                } else {
                    """[{"id":10,"name":"구 서번트","collectionNo":1}]"""
                }
                url.endsWith("/KR/basic_equip.json") -> """[{"id":9,"name":"예장","collectionNo":11}]"""
                url.endsWith("/KR/basic_command_code.json") -> """[{"id":80001,"name":"코드","collectionNo":1}]"""
                else -> """[{"id":100,"name":"과일","collectionNo":1}]"""
            }
        }
        val names = GameNames(directory, downloader) { time }
        assertEquals("구 서번트", names.resolve(listOf(RunGains(bond = mapOf(10L to 1L)))).servant(10))
        assertEquals("kr1", File(directory, "basic_servant_details.hash").readText())
        assertEquals("kr1", File(directory, "basic_servant.json.hash").readText())

        // Atlas updates KR; ensureChaldeaCollectionLists downloads details and writes basic_servant_details.hash = kr2.
        time += AtlasVersions.CHECK_INTERVAL_MS
        krHash = "kr2"
        hasNewServant = true
        urls.clear()
        assertTrue(names.ensureChaldeaCollectionLists())
        assertEquals("kr2", File(directory, "basic_servant_details.hash").readText())
        assertEquals("kr1", File(directory, "basic_servant.json.hash").readText())

        // Later resolve() sees basic_servant.json.hash != kr2, downloads the KR name map, and shows the KR name.
        urls.clear()
        val resolved = names.resolve(listOf(RunGains(bond = mapOf(99L to 1L))))
        assertEquals("새 한국 서번트", resolved.servant(99))
        assertEquals("kr2", File(directory, "basic_servant.json.hash").readText())
        assertTrue(urls.any { it.endsWith("/KR/basic_servant.json") })
    }

    @Test fun failedNameMapWriteLeavesNoNewNameMapHash() = runBlocking {
        // Blocking basic_servant.json with a non-empty directory makes rename/write fail.
        File(directory, "basic_servant.json").apply {
            mkdirs()
            File(this, "blocker").writeText("keep")
        }
        val names = GameNames(directory, { url ->
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr1", "jp1")
                url.endsWith("/KR/basic_servant.json") -> """[{"id":10,"name":"서번트"}]"""
                else -> """[{"id":999,"name":"JP"}]"""
            }
        }) { 1_000L }

        val first = names.resolve(listOf(RunGains(bond = mapOf(10L to 1L))))
        assertEquals("Servant #10", first.servant(10))
        assertFalse(File(directory, "basic_servant.json.hash").exists())

        // Existing name-map hash is also preserved when a later write fails.
        File(directory, "basic_servant.json.hash").writeText("kr0")
        val second = GameNames(directory, { url ->
            when {
                url == AtlasVersions.INFO_URL -> infoJson("kr2", "jp1")
                url.endsWith("/KR/basic_servant.json") -> """[{"id":10,"name":"서번트"}]"""
                else -> """[{"id":999,"name":"JP"}]"""
            }
        }) { 1_000L + AtlasVersions.CHECK_INTERVAL_MS }.resolve(listOf(RunGains(bond = mapOf(10L to 1L))))
        assertEquals("Servant #10", second.servant(10))
        assertEquals("kr0", File(directory, "basic_servant.json.hash").readText())
    }

    @Test fun ensureChaldeaCollectionListsStopsEarlyWhenShouldStopBecomesTrue() = runBlocking {
        var stop = false
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { url ->
            if (url == AtlasVersions.INFO_URL) error("offline")
            urls += url
            stop = true
            """[{"id":9300010,"collectionNo":11}]"""
        }) { 1_000L }

        assertTrue(names.ensureChaldeaCollectionLists { stop })
        assertEquals(listOf("https://api.atlasacademy.io/export/KR/basic_equip.json"), urls)
    }
}
