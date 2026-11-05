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
}
