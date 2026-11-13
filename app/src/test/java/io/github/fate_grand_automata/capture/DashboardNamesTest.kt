package io.github.fate_grand_automata.capture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DashboardNamesTest {
    @TempDir lateinit var directory: File
    private val body = """[{"id":10,"name":"Test","type":"normal","className":"saber","rarity":5,"collectionNo":1,"face":"https://static.atlasacademy.io/KR/Faces/fake.png","private":"not retained"}]"""

    @Test fun freshNiceItemAttemptCannotBlockOtherListsAndAllUseOwnMarkers() = runBlocking {
        File(directory,"nice_item.attempt").writeText("1000")
        File(directory,"item_icons.attempt").writeText("1000")
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { urls += it; body }) { 1001 }.dashboard()
        listOf("basic_servant", "basic_equip", "basic_mystic_code").forEach { list ->
            assertTrue(urls.any { it.endsWith("/$list.json") }, list)
            assertTrue(File(directory,"$list.attempt").isFile, list)
        }
        assertEquals("1000",File(directory,"nice_item.attempt").readText())
        assertEquals("Test",names.servantData[10]?.name)
        assertEquals(5L,names.servantData[10]?.rarity)
    }
    @Test fun legacyNameAttemptDoesNotBlockFirstMetadataFetch() = runBlocking {
        listOf("nice_item","basic_servant","basic_equip","basic_mystic_code").forEach {
            File(directory,"$it.json").writeText("""{"10":"Old"}""")
            File(directory,"$it.attempt").writeText("1000")
        }
        File(directory,"item_icons.json").writeText("{}")
        val cache = GameNames(directory, { body }) { 1001 }.dashboard()
        assertEquals("normal", cache.servantData[10]?.type)
        assertEquals("https://static.atlasacademy.io/KR/Faces/fake.png",cache.servantData[10]?.icon)
        assertTrue(File(directory,"basic_servant_details.attempt").isFile)
    }
    @Test fun metadataCacheSurvivesNewInstanceWithoutDownloads() = runBlocking {
        val first = GameNames(directory, { body }) { 1001 }.dashboard()
        val second = GameNames(directory, { error("network forbidden") }) { 1002 }.dashboard()
        assertEquals(first,second)
        directory.listFiles()!!.filter { it.extension=="json" }.forEach { assertFalse(it.readText().contains("not retained")) }
    }
    @Test fun freshServantCacheWithUnknownIdsDoesNotDownloadButAnOldOneDoes() = runBlocking {
        GameNames(directory, { body }) { 1001 }.dashboard()
        val state = dashboardState("userSvtCollection" to """[{"svtId":999,"status":2}]""", "userSvt" to """[{"svtId":998}]""",
            "userEquip" to """[{"equipId":997}]""")
        GameNames(directory, { error("network forbidden") }) { 3_000_000 }.dashboard(listOf(state))
        val urls = mutableListOf<String>()
        GameNames(directory, { urls += it; body }) { 1001 + 7 * 86_400_000L }.dashboard(listOf(state))
        listOf("basic_servant", "basic_equip", "basic_mystic_code").forEach { list -> assertTrue(urls.any { it.endsWith("/$list.json") }, list) }
    }
    @Test fun failuresHaveIndependentPersistentDailyBackoff() = runBlocking {
        val calls = mutableListOf<String>()
        val download: (String)->String = { calls += it; error("offline") }
        GameNames(directory,download) { 1000 }.dashboard()
        val first = calls.toList()
        GameNames(directory,download) { 1001 }.dashboard()
        assertEquals(first,calls)
        GameNames(directory,download) { 86_401_001 }.dashboard()
        assertTrue(calls.size > first.size)
    }
    @Test fun mysticItemAndFaceUrlsUsePublicHttpsOnly() {
        val rows = GameNames.parseBasic("""[
            {"id":1,"name":"Mystic","item":{"male":"https://static.atlasacademy.io/KR/Items/fake.png"}},
            {"id":2,"face":"https://private.test/x"},
            {"id":3,"icon":"http://static.atlasacademy.io/x"},null,{}]""")
        assertEquals(3,rows.size)
        assertNotNull(rows[0].icon); assertNull(rows[1].icon); assertNull(rows[2].icon)
    }

    @Test fun coinIdsAreRequestedAsRequiredNiceItemIdsAndFallBackToJp() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/nice_item.json") ->
                    """[{"id":100,"name":"KR Item","type":"skillLvUp","icon":"https://static.atlasacademy.io/KR/Items/100.png"}]"""
                url.endsWith("/JP/nice_item.json") ->
                    """[{"id":100,"name":"JP Item","type":"skillLvUp"},{"id":1001,"name":"アルトリアのサーヴァントコイン","type":"svtCoin","icon":"https://static.atlasacademy.io/JP/Coins/10.png"}]"""
                else -> """[{"id":10,"name":"Test","type":"normal","className":"saber","rarity":5,"collectionNo":1}]"""
            }
        }
        val zeroOnly = dashboardState("userSvtCoin" to """[{"svtId":20,"num":0}]""")
        GameNames(directory, downloader) { 1000 }.dashboard(listOf(zeroOnly))
        assertFalse(urls.any { it.endsWith("/JP/nice_item.json") })

        urls.clear()
        val withCoin = dashboardState("userSvtCoin" to """[{"svtId":10,"num":30},{"svtId":20,"num":0}]""")
        val names = GameNames(directory, downloader) { 1001 }.dashboard(listOf(withCoin))
        assertTrue(urls.any { it.endsWith("/JP/nice_item.json") })
        assertEquals("アルトリアのサーヴァントコイン", names.item(1001))
        assertEquals("https://static.atlasacademy.io/JP/Coins/10.png", names.itemIcons[1001])
    }
}
