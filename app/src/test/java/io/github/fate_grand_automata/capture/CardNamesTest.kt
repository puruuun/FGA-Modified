package io.github.fate_grand_automata.capture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class CardNamesTest {
    @TempDir lateinit var directory: File
    private val day = 86_400_000L
    private val body = """[
        {"id":10,"name":"예지의 맹화","type":"combineMaterial","className":"all","rarity":4,"face":"https://static.atlasacademy.io/KR/Faces/synthetic.png","secret":"discard"},
        {"id":11,"name":"합성 예장","type":"servantEquip"},
        {"id":12,"name":"합성 포우","type":"statusUp"},
        {"id":13,"name":"합성 서번트","type":"normal"}]
    """
    private fun cache(time: Long, urls: MutableList<String>, failCards: Boolean = false) = GameNames(directory, { url ->
        urls += url
        if (failCards && (url.endsWith("/basic_svt.json") || url.endsWith("/basic_command_code.json"))) error("offline")
        body
    }) { time }
    private fun cardUrls(urls: List<String>) = urls.filter { it.endsWith("/basic_svt.json") || it.endsWith("/basic_command_code.json") }

    @Test fun allCardTypesAndCommandCodeFacesAreCachedWithOnlyPublicMetadata() = runBlocking {
        val urls = mutableListOf<String>()
        val names = cache(1000, urls).resolve(emptyList(), includeCards = true)
        assertEquals(setOf(10L, 11L, 12L, 13L), names.svtData.keys)
        assertEquals("예지의 맹화", names.svtData[10]?.name)
        assertEquals("https://static.atlasacademy.io/KR/Faces/synthetic.png", names.commandCodeData[10]?.icon)
        assertEquals(setOf("https://api.atlasacademy.io/export/KR/basic_svt.json",
            "https://api.atlasacademy.io/export/KR/basic_command_code.json"), cardUrls(urls).toSet())
        listOf("basic_svt", "basic_command_code").forEach {
            assertFalse(File(directory, "${it}_details.json").readText().contains("discard"))
        }
    }

    @Test fun freshCacheSurvivesInstancesAndRefreshesAtSevenDays() = runBlocking {
        val urls = mutableListOf<String>()
        val first = cache(1000, urls).resolve(emptyList(), includeCards = true)
        urls.clear()
        val fresh = cache(1000 + 7 * day - 1, urls).resolve(emptyList(), includeCards = true)
        assertEquals(first.svtData, fresh.svtData)
        assertEquals(first.commandCodeData, fresh.commandCodeData)
        assertTrue(cardUrls(urls).isEmpty())
        cache(1000 + 7 * day, urls).resolve(emptyList(), includeCards = true)
        assertEquals(2, cardUrls(urls).size)
    }

    @Test fun eachMissingFileHasOneAttemptPerDayAndRecoversIndependently() = runBlocking {
        val urls = mutableListOf<String>()
        val failed = cache(1000, urls, failCards = true).resolve(emptyList(), includeCards = true)
        assertTrue(failed.svtData.isEmpty())
        assertTrue(failed.commandCodeData.isEmpty())
        assertEquals(2, cardUrls(urls).size)
        cache(1000 + day - 1, urls).resolve(emptyList(), includeCards = true)
        assertEquals(2, cardUrls(urls).size)
        cache(1000 + day, urls).resolve(emptyList(), includeCards = true)
        assertEquals(4, cardUrls(urls).size)
        File(directory, "basic_command_code_details.json").delete()
        cache(1000 + 2 * day, urls).resolve(emptyList(), includeCards = true)
        assertEquals(5, cardUrls(urls).size)
        assertTrue(cardUrls(urls).last().endsWith("/basic_command_code.json"))
    }

    @Test fun staleDownloadFailureKeepsGoodMetadataAndDailyBudget() = runBlocking {
        val urls = mutableListOf<String>()
        val first = cache(1000, urls).resolve(emptyList(), includeCards = true)
        urls.clear()
        val stale = cache(1000 + 7 * day, urls, failCards = true).resolve(emptyList(), includeCards = true)
        assertEquals(first.svtData, stale.svtData)
        assertEquals(first.commandCodeData, stale.commandCodeData)
        cache(1000 + 7 * day + 1, urls).resolve(emptyList(), includeCards = true)
        assertEquals(2, cardUrls(urls).size)
    }

    @Test fun battleOnlyResolutionNeverRequestsCardExports() = runBlocking {
        val urls = mutableListOf<String>()
        val names = cache(1000, urls).resolve(emptyList())
        assertTrue(cardUrls(urls).isEmpty())
        assertTrue(names.svtData.isEmpty())
        assertTrue(names.commandCodeData.isEmpty())
    }

    @Test fun cardFaceParserUsesExistingHostGuard() {
        val rows = GameNames.parseBasic("""[
            {"id":1,"face":"https://static.atlasacademy.io/KR/Faces/synthetic.png"},
            {"id":2,"face":"http://static.atlasacademy.io/x"},
            {"id":3,"face":"https://private.test/x"},
            {"id":4,"face":"https://static.atlasacademy.io:444/x"},
            {"id":5,"face":"https://user@static.atlasacademy.io/x"}]
        """)
        assertNotNull(rows.first().icon)
        assertTrue(rows.drop(1).all { it.icon == null })
    }
}
