package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class JpNamesTest {
    @TempDir lateinit var directory: File
    private val day = 86_400_000L
    private val lists = setOf("basic_servant", "basic_equip", "nice_item", "basic_mystic_code")
    private fun jpUrls() = lists.map { "https://api.atlasacademy.io/export/JP/$it.json" }.toSet()
    private fun hasJpAccount(server: String): Boolean {
        val root = File(directory, "capture")
        val store = CaptureAccounts().apply { load(root) }
        val host = if (server == "JP") "game.fate-go.jp" else "yfgo-game.netmarble.com"
        store.apply("https://$host/login/top", Json.parseToJsonElement("""{
            "response":[{"nid":"login","resCode":"00"}],
            "cache":{"replaced":{"userGame":[{"userId":7002,"name":"Synthetic"}]}}}""").jsonObject, 10)
        store.save()
        return CaptureAccounts().apply { load(root) }.accounts(20).any { it.server == "JP" }
    }

    @Test fun krOnlyAccountKeepsExistingResultsAndNeverReadsOrDownloadsJpLists() = runBlocking {
        val krState = dashboardState(
            "userGame" to """[{"userId":7001,"name":"합성 KR 계정"}]""",
            "userItem" to """[{"itemId":100,"num":5}]""",
            "userSvt" to """[{"svtId":10},{"svtId":30}]""",
            "userSvtCollection" to """[{"svtId":10,"status":2,"maxLv":90}]""",
            "userEquip" to """[{"equipId":1,"lv":10}]""",
        )
        val urls = mutableListOf<String>()
        val download: (String) -> String = { urls += it; JpNamesFixture.download(it) }
        val cache = GameNames(directory, download) { 1000 }
        val kr = cache.dashboard(listOf(krState), hasJpAccount("KR"))
        assertEquals(cache.dashboard(listOf(krState)), kr)
        assertTrue(urls.all { "/export/KR/" in it })
        assertEquals(listOf(10L), dashboardData(krState, kr).servants!!.map { it.id })
        cache.dashboard(hasJpAccount = true) // Leave JP files on disk, as after deleting a JP account.
        urls.clear()
        assertEquals(kr, cache.dashboard(listOf(krState), hasJpAccount = false))
        assertTrue(urls.isEmpty())
    }

    @Test fun missingJpIdsFallBackToJpListsEvenWhenAccountIsLabelledKrOrNoJpAccountExists() = runBlocking {
        val cache = GameNames(directory, JpNamesFixture::download) { 1000 }
        // Dashboard: state has JP-only servant 20 and CE 40, while hasJpAccount is false (wrong label or no JP account).
        val dashboardNames = cache.dashboard(listOf(JpNamesFixture.state), hasJpAccount = false)
        assertEquals("日本の騎士", dashboardNames.servant(20))
        assertEquals("日本の礼装", dashboardNames.equip(40))
        assertEquals(listOf(10L, 20L), dashboardData(JpNamesFixture.state, dashboardNames).servants!!.map { it.id })

        // Friends: JP-only servant 20 and CE 40 resolve even when hasJpAccount is false.
        val friendNames = GameNames(File(directory, "friends"), JpNamesFixture::download) { 1000 }
            .friends(listOf(10, 20), listOf(30, 40), hasJpAccount = false)
        assertEquals("한국 기사", friendNames.servant(10))
        assertEquals("日本の騎士", friendNames.servant(20))
        assertEquals("한국 예장", friendNames.equip(30))
        assertEquals("日本の礼装", friendNames.equip(40))

        // Log resolve: JP-only servant 20, CE 40, and item 200 resolve even when hasJpAccount is false.
        val run = RunGains(items = listOf(GainedItem(1, 20, 1), GainedItem(3, 40, 1), GainedItem(2, 200, 1)),
            bond = mapOf(20L to 1))
        val logNames = GameNames(File(directory, "logs"), JpNamesFixture::download) { 1000 }
            .resolve(listOf(run), includeCards = true, hasJpAccount = false, cardIds = listOf(20, 40))
        assertEquals("日本の騎士", logNames.servant(20))
        assertEquals("日本の礼装", logNames.equip(40))
        assertEquals("日本の素材", logNames.item(200))
        assertEquals("日本の騎士", logNames.svtData[20]?.name)
        assertEquals("日本の礼装", logNames.svtData[40]?.name)
    }

    @Test fun storedJpAccountAddsJapaneseNamesFacesTypesAndKeepsKrEntries() = runBlocking {
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { urls += it; JpNamesFixture.download(it) }) { 1000 }
            .dashboard(listOf(JpNamesFixture.state), hasJpAccount("JP"))
        assertEquals(jpUrls(), urls.filter { "/JP/" in it }.toSet())
        assertEquals("日本の騎士", names.servant(20))
        assertEquals("normal", names.servantData[20]?.type)
        assertEquals(JpNamesFixture.icon("JP", 20), names.servantData[20]?.icon)
        assertEquals("saber", names.servantData[20]?.className)
        assertEquals(5L, names.servantData[20]?.rarity)
        assertEquals("한국 기사", names.servant(10))
        assertEquals(JpNamesFixture.icon("KR", 10), names.servantData[10]?.icon)
        assertEquals("日本の礼装", names.equip(40))
        assertEquals(JpNamesFixture.icon("JP", 40), names.equipData[40]?.icon)
        assertEquals("한국 예장", names.equip(30))
        assertEquals("日本の制服", names.mysticData[2]?.name)
        assertEquals(JpNamesFixture.icon("JP", 2), names.mysticData[2]?.icon)
        assertEquals("한국 제복", names.mysticData[1]?.name)
        assertEquals("日本の素材", names.item(200))
        assertEquals("skillLvUp", names.itemData[200]?.type)
        lists.forEach {
            assertTrue(File(directory, "jp_${it}_details.json").isFile)
            assertTrue(File(directory, "jp_${it}_details.attempt").isFile)
            assertTrue(File(directory, "jp_${it}_details.fetched").isFile)
            assertFalse(File(directory, "jp_${it}_details.json").readText().contains("discarded"))
        }
    }

    @Test fun dashboardIncludesNormalAndHeroineButExcludesJpEnemyAndCe() = runBlocking {
        val state = dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2},{"svtId":20,"status":2},{"svtId":21,"status":2},
            {"svtId":22,"status":2},{"svtId":40,"status":2}]""")
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.dashboard(listOf(state), true)
        assertEquals(listOf(10L, 20L, 22L), dashboardData(state, names).servants!!.map { it.id })
    }

    @Test fun itemsUseKrThenJpListThenPerIdFallbackEvenWithExistingFallbackCache() = runBlocking {
        File(directory, "jp_item_fallback.json").writeText("""{"100":{"name":"old"},"200":{"name":"old"}}""")
        val urls = mutableListOf<String>()
        val state = dashboardState("userItem" to """[{"itemId":100},{"itemId":200},{"itemId":300}]""")
        val names = GameNames(directory, { urls += it; JpNamesFixture.download(it) }) { 1000 }.dashboard(listOf(state), true)
        assertEquals("한국 과일", names.item(100))
        assertEquals(JpNamesFixture.icon("KR", 100), names.itemIcons[100])
        assertEquals("日本の素材", names.item(200))
        assertEquals(JpNamesFixture.icon("JP", 200), names.itemIcons[200])
        assertEquals("個別アイテム", names.item(300))
        assertEquals(JpNamesFixture.icon("JP", 300), names.itemIcons[300])
        assertEquals(listOf("https://api.atlasacademy.io/nice/JP/item/300"), urls.filter { "/nice/JP/" in it })
    }

    @Test fun friendsUseJpListsAndKeepKrNames() = runBlocking {
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.friends(listOf(10, 20), listOf(30, 40), true)
        assertEquals("한국 기사", names.servant(10))
        assertEquals("日本の騎士", names.servant(20))
        assertEquals("한국 예장", names.equip(30))
        assertEquals("日本の礼装", names.equip(40))
    }

    @Test fun jpListFillsMoreItemsThanThePerIdLookupCap() = runBlocking {
        val ids = (1000L..1148L).toList()
        val urls = mutableListOf<String>()
        val state = dashboardState("userItem" to ids.joinToString(",", "[", "]") { """{"itemId":$it,"num":1}""" })
        val names = GameNames(directory, { url ->
            urls += url
            if (url.endsWith("JP/nice_item.json")) ids.joinToString(",", "[", "]") {
                """{"id":$it,"name":"日本の素材 $it","type":"skillLvUp","icon":"${JpNamesFixture.icon("JP", it)}"}"""
            } else JpNamesFixture.download(url)
        }) { 1000 }.dashboard(listOf(state), true)
        ids.forEach {
            assertEquals("日本の素材 $it", names.item(it))
            assertEquals(JpNamesFixture.icon("JP", it), names.itemIcons[it])
        }
        assertTrue(urls.none { "/nice/JP/" in it })
    }

    @Test fun krEntryWithoutAnIconStillWinsOverTheJpEntry() = runBlocking {
        val names = GameNames(directory, { url ->
            val body = JpNamesFixture.download(url)
            if ("/KR/" in url) body.replace("https://static.atlasacademy.io/KR/", "https://private.test/") else body
        }) { 1000 }.dashboard(hasJpAccount = true)
        assertEquals("한국 과일", names.item(100))
        assertNull(names.itemIcons[100])
        assertEquals("한국 기사", names.servantData[10]?.name)
        assertNull(names.servantData[10]?.icon)
    }

    @Test fun logDropBondAndCardNamesUseJpListsWithKrPrecedence() = runBlocking {
        val run = RunGains(items = listOf(GainedItem(1, 20, 1), GainedItem(3, 40, 1), GainedItem(2, 200, 1)),
            bond = mapOf(20L to 1))
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.resolve(listOf(run), includeCards = true, hasJpAccount = true)
        assertEquals("日本の騎士", names.drop(1, 20))
        assertEquals("日本の騎士", names.servant(20))
        assertEquals("日本の礼装", names.drop(3, 40))
        assertEquals("日本の素材", names.drop(2, 200))
        assertEquals("日本の騎士", names.svtData[20]?.name)
        assertEquals("日本の礼装", names.svtData[40]?.name)
        assertEquals(JpNamesFixture.icon("JP", 40), names.svtData[40]?.icon)
        assertEquals("한국 기사", names.svtData[10]?.name)
        assertEquals("한국 코드", names.commandCodeData[50]?.name)
    }

    @Test fun freshKrMarkersCannotSuppressJpFetchAndJpNeverWritesKrCaches() = runBlocking {
        GameNames(directory, JpNamesFixture::download) { 1000 }.dashboard()
        val krFiles = directory.listFiles()!!.associate { it.name to it.readText() }
        val urls = mutableListOf<String>()
        GameNames(directory, { urls += it; JpNamesFixture.download(it) }) { 1001 }.dashboard(hasJpAccount = true)
        assertEquals(jpUrls(), urls.toSet())
        krFiles.forEach { (file, content) -> assertEquals(content, File(directory, file).readText(), file) }
    }

    @Test fun jpCachesRefreshAfterSevenDaysAndPersistAcrossInstances() = runBlocking {
        var time = 1000L
        val urls = mutableListOf<String>()
        val download: (String) -> String = { urls += it; JpNamesFixture.download(it) }
        val first = GameNames(directory, download) { time }.dashboard(hasJpAccount = true)
        time += 7 * day - 1
        urls.clear()
        assertEquals(first, GameNames(directory, download) { time }.dashboard(hasJpAccount = true))
        assertTrue(urls.isEmpty())
        time++
        GameNames(directory, download) { time }.dashboard(hasJpAccount = true)
        assertEquals(jpUrls(), urls.filter { "/JP/" in it }.toSet())
    }

    @Test fun jpFailuresRetryDailyPerListAndKeepLastGoodData() = runBlocking {
        var time = 1000L
        GameNames(directory, JpNamesFixture::download) { time }.dashboard(hasJpAccount = true)
        time += 7 * day
        val calls = mutableListOf<String>()
        val offline: (String) -> String = {
            if ("/JP/" in it) { calls += it; error("offline") } else JpNamesFixture.download(it)
        }
        val names = GameNames(directory, offline) { time }.dashboard(hasJpAccount = true)
        assertEquals("日本の騎士", names.servant(20))
        assertEquals(jpUrls(), calls.toSet())
        time += day - 1
        GameNames(directory, offline) { time }.dashboard(hasJpAccount = true)
        assertEquals(4, calls.size)
        time++
        GameNames(directory, offline) { time }.dashboard(hasJpAccount = true)
        assertEquals(8, calls.size)
        lists.forEach { assertEquals("1000", File(directory, "jp_${it}_details.fetched").readText()) }
    }

    @Test fun failedJpListDoesNotBlockOthersAndFreshJpMarkersCannotSuppressKr() = runBlocking {
        val calls = mutableListOf<String>()
        val download: (String) -> String = {
            calls += it
            if (it.endsWith("JP/basic_servant.json")) error("offline") else JpNamesFixture.download(it)
        }
        val names = GameNames(directory, download) { 1000 }.dashboard(hasJpAccount = true)
        assertEquals("日本の礼装", names.equip(40))
        assertEquals("日本の素材", names.item(200))
        assertEquals("日本の制服", names.mysticData[2]?.name)
        assertEquals(jpUrls(), calls.filter { "/JP/" in it }.toSet())
        File(directory, "basic_servant_details.fetched").writeText("0")
        File(directory, "basic_servant_details.attempt").writeText("0")
        lists.forEach { File(directory, "jp_${it}_details.attempt").writeText((8 * day).toString()) }
        calls.clear()
        GameNames(directory, download) { 8 * day }.dashboard(hasJpAccount = true)
        assertTrue(calls.any { it.endsWith("KR/basic_servant.json") })
        assertFalse(calls.any { "/JP/" in it })
    }

    @Test fun jpListCannotSuppressLaterKrRefreshOnSameInstance() = runBlocking {
        var time = 1000L
        var krKnowsId = false
        val cache = GameNames(directory, {
            if (krKnowsId && it.endsWith("KR/basic_servant.json"))
                """[{"id":20,"name":"새 한국 기사","type":"normal"}]""" else JpNamesFixture.download(it)
        }) { time }
        assertEquals("日本の騎士", cache.friends(listOf(20), emptyList(), true).servant(20))
        krKnowsId = true
        time += day
        assertEquals("새 한국 기사", cache.friends(listOf(20), emptyList(), true).servant(20))
    }

    @Test fun jpOnlyItemIconsAreDownloadedStoredCountedAnd404RemainsMissing() = runBlocking {
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.dashboard(hasJpAccount = true)
        val targets = iconPrefetchTargets(names)
        assertEquals(JpNamesFixture.icon("JP", 200), targets.single { it.id == 200L }.address)
        val downloaded = ConcurrentHashMap.newKeySet<String>()
        val job = IconPrefetchJob(this, { names }, { target ->
            IconFileStore(File(directory, "icons/${target.directory}"), {
                downloaded += it
                byteArrayOf(1)
            }).fetch(target.id, target.address).second
        })
        job.start().join()
        assertTrue(JpNamesFixture.icon("JP", 200) in downloaded)
        assertTrue(File(directory, "icons/200.png").isFile)
        assertEquals(IconPrefetchProgress.Ready(targets.size, 0), job.state.value)
        val missing = IconFileStore(File(directory, "missing"), { throw IconNotFoundException() })
        assertEquals(IconOutcome.Missing, missing.fetch(200, names.itemIcons[200]).second)
        val missingJob = IconPrefetchJob(this, { names }, { target ->
            if (target.id == 200L) missing.fetch(target.id, target.address).second else IconOutcome.Ready
        })
        missingJob.start().join()
        assertEquals(IconPrefetchProgress.Ready(targets.size - 1, 0), missingJob.state.value)
    }
}
