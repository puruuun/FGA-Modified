package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.*

class JpItemFallbackTest {
    @TempDir lateinit var directory: File
    private val icon = "https://static.atlasacademy.io/JP/Items/94063703.png"
    private val krIcon = "https://static.atlasacademy.io/KR/Items/94143703.png"
    private val id = 94143703L
    private val krBody = """[{"id":100,"name":"Fruit"},{"id":101,"name":"Silver"},{"id":102,"name":"Copper"},{"id":104,"name":"Bronze"}]"""
    private val jpBody get() = """{"name":"維新まんじゅう","type":"eventItem","icon":"$icon","private":"not retained"}"""
    private val cacheFile get() = File(directory, "jp_item_fallback.json")
    private val retryFile get() = File(directory, "jp_item_fallback_retry.json")
    private fun run(vararg ids: Long) = listOf(RunGains(items = ids.map { GainedItem(2, it, 1) }))

    @Test fun missingKrItemUsesJpAndOnlyNameAndPublicIconArePersisted() = runBlocking {
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { url ->
            urls += url
            if (url.contains("/nice/JP/")) jpBody else krBody
        }) { 1000 }.resolve(run(id))
        assertEquals("維新まんじゅう", names.item(id))
        assertEquals(icon, names.itemIcons[id])
        assertEquals(listOf("https://api.atlasacademy.io/nice/JP/item/$id"), urls.filter { it.contains("/nice/JP/") })
        val row = Json.parseToJsonElement(cacheFile.readText()).jsonObject.getValue("$id").jsonObject
        assertEquals(setOf("name", "icon"), row.keys)
        assertEquals("維新まんじゅう", row.getValue("name").jsonPrimitive.content)
        assertFalse(File(directory, "nice_item.json").readText().contains("$id"))
        assertFalse(File(directory, "jp_item_fallback.json.tmp").exists())
        val file = IconFileStore(File(directory, "icons"), { assertEquals(icon, it); byteArrayOf(1) })
            .resolve(id, names.itemIcons[id])
        assertEquals("$id.png", assertNotNull(file).name)
    }

    @Test fun krNameWinsEvenWhenItHasNoIconAndNeverCallsJp() = runBlocking {
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { url ->
            urls += url
            """[{"id":$id,"name":"한국어"}]"""
        }).resolve(run(id), listOf(id))
        assertTrue(urls.none { it.contains("/JP/") })
        assertEquals("한국어", names.item(id))
        assertNull(names.itemIcons[id])
        assertFalse(cacheFile.exists())
    }

    @Test fun laterDailyKrRefreshReplacesAndPrunesJpEvenOnSameInstance() = runBlocking {
        var time = 1000L
        var kr = krBody
        var jpCalls = 0
        val cache = GameNames(directory, { url ->
            if (url.contains("/nice/JP/")) { jpCalls++; jpBody } else kr
        }) { time }
        assertEquals("維新まんじゅう", cache.resolve(run(id)).item(id))
        kr = """[{"id":$id,"name":"한국어","icon":"$krIcon"}]"""
        time += 86_399_999
        assertEquals("維新まんじゅう", cache.resolve(run(id)).item(id))
        time++
        val updated = cache.resolve(run(id))
        assertEquals("한국어", updated.item(id))
        assertEquals(krIcon, updated.itemIcons[id])
        assertEquals(1, jpCalls)
        assertTrue(Json.parseToJsonElement(cacheFile.readText()).jsonObject.isEmpty())
        assertEquals("한국어", GameNames(directory, { error("must use disk") }) { time + 1 }.resolve(run(id)).item(id))
    }

    @Test fun successfulJpCacheIsReusedAcrossRestartsAndDays() = runBlocking {
        GameNames(directory, { if (it.contains("/nice/JP/")) jpBody else krBody }) { 1000 }.resolve(run(id))
        val urls = mutableListOf<String>()
        val cache = GameNames(directory, { url -> urls += url; krBody }) { 1000 + 3 * 86_400_000L }
        assertEquals("維新まんじゅう", cache.resolve(run(id)).item(id))
        assertEquals(icon, cache.resolve(run(id)).itemIcons[id])
        assertTrue(urls.none { it.contains("/JP/") })
    }

    @Test fun notFoundAndFailureHaveIndependentDailyBackoffWhileOtherIdsContinue() = runBlocking {
        var time = 1000L
        val calls = mutableListOf<Long>()
        val download: (String) -> String = { url ->
            if (!url.contains("/JP/")) krBody else {
                val key = url.substringAfterLast('/').toLong()
                calls += key
                when (key) { 1L -> throw IOException("HTTP 404"); 2L -> error("offline"); else -> jpBody }
            }
        }
        GameNames(directory, download) { time }.resolve(run(1))
        time += 100
        assertEquals("維新まんじゅう", GameNames(directory, download) { time }.resolve(run(1, 2, 3)).item(3))
        assertEquals(listOf(1L, 2L, 3L), calls)
        time = 1000 + 86_399_999L
        GameNames(directory, download) { time }.resolve(run(1, 2, 3))
        assertEquals(listOf(1L, 2L, 3L), calls)
        time++
        GameNames(directory, download) { time }.resolve(run(1, 2, 3))
        assertEquals(listOf(1L, 2L, 3L, 1L), calls)
        time += 100
        GameNames(directory, download) { time }.resolve(run(1, 2, 3))
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L), calls)
        assertEquals(setOf("1", "2"), Json.parseToJsonElement(retryFile.readText()).jsonObject.keys)
    }

    @Test fun requestCapCountsFailuresAndLetsLaterIdsProceedOnNextResolve() = runBlocking {
        val calls = mutableListOf<Long>()
        val cache = GameNames(directory, { url ->
            if (!url.contains("/JP/")) krBody else {
                calls += url.substringAfterLast('/').toLong()
                error("HTTP 404")
            }
        }) { 1000 }
        val ids = (1000L..1060L).toList()
        cache.resolve(run(*ids.toLongArray()), ids)
        assertEquals((1000L..1029L).toList(), calls)
        cache.resolve(run(*ids.toLongArray()))
        assertEquals((1000L..1059L).toList(), calls)
        cache.resolve(run(*ids.toLongArray()))
        assertEquals(ids, calls)
    }

    @Test fun nonAtlasIconIsDroppedButNameStillResolvesAndCaches() = runBlocking {
        val names = GameNames(directory, {
            if (it.contains("/JP/")) """{"name":"名前","icon":"https://private.test/icon.png"}""" else krBody
        }).resolve(run(id))
        assertEquals("名前", names.item(id))
        assertNull(names.itemIcons[id])
        assertFalse(cacheFile.readText().contains("private.test"))
    }

    @Test fun onlyPositiveItemIdsFromItemDropsAndOutsideLogEnterFixedUrl() = runBlocking {
        val calls = mutableListOf<String>()
        val names = GameNames(directory, { url ->
            if (url.contains("/nice/JP/")) { calls += url; jpBody } else krBody
        }).resolve(listOf(RunGains(items = listOf(GainedItem(1, 501, 1), GainedItem(3, 502, 1),
            GainedItem(99, 503, 1), GainedItem(2, 0, 1), GainedItem(2, -1, 1), GainedItem(2, id, 1)))),
            listOf(id, 504, Long.MAX_VALUE, 0, -1))
        assertEquals(listOf(id, 504, Long.MAX_VALUE).map { "https://api.atlasacademy.io/nice/JP/item/$it" }, calls)
        assertEquals("維新まんじゅう", names.item(504))
    }

    @Test fun dashboardIncludesMissingPositiveUserItemIdsWithoutSendingAccountFields() = runBlocking {
        val calls = mutableListOf<String>()
        val state = dashboardState("userItem" to """[{"itemId":$id,"num":9,"userId":"private-account"},
            {"itemId":$id,"num":1},{"itemId":0,"num":1},{"itemId":-3,"num":1},{"itemId":100,"num":2}]""")
        val names = GameNames(directory, { url ->
            calls += url
            if (url.contains("/nice/JP/")) jpBody else krBody
        }).dashboard(listOf(state))
        assertEquals(listOf("https://api.atlasacademy.io/nice/JP/item/$id"), calls.filter { it.contains("/nice/JP/") })
        assertEquals("維新まんじゅう", names.item(id))
        assertEquals(icon, names.itemIcons[id])
        assertFalse(cacheFile.readText().contains("private-account"))
    }

    @Test fun dashboardKrMetadataWinsAndPrunesWithoutJpRequest() = runBlocking {
        GameNames(directory, { if (it.contains("/JP/")) jpBody else krBody }) { 1000 }.resolve(run(id))
        File(directory, "nice_item_details.json").writeText("""[{"id":$id,"name":"한국어","icon":"$krIcon"}]""")
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { urls += it; error("offline") }) { 1001 }
            .dashboard(listOf(dashboardState("userItem" to """[{"itemId":$id,"num":1}]""")))
        assertTrue(urls.none { it.contains("/JP/") })
        assertEquals("한국어", names.item(id))
        assertEquals(krIcon, names.itemIcons[id])
        assertTrue(Json.parseToJsonElement(cacheFile.readText()).jsonObject.isEmpty())
    }

    @Test fun dashboardMetadataWithoutKrNameStillUsesJp() = runBlocking {
        val state = dashboardState("userItem" to """[{"itemId":$id,"num":1}]""")
        val names = GameNames(directory, { url ->
            if (url.contains("/JP/")) jpBody else """[{"id":$id,"type":"eventItem"}]"""
        }).dashboard(listOf(state))
        assertEquals("維新まんじゅう", names.item(id))
        assertEquals(icon, names.itemIcons[id])
    }

    @Test fun successfulRetryRemovesFailureMarkerAndPersistsResult() = runBlocking {
        GameNames(directory, { url -> if (url.contains("/JP/")) error("HTTP 404") else krBody }) { 1000 }
            .resolve(run(id))
        val names = GameNames(directory, { url -> if (url.contains("/JP/")) jpBody else krBody }) { 86_401_000 }
            .resolve(run(id))
        assertEquals("維新まんじゅう", names.item(id))
        assertTrue(Json.parseToJsonElement(retryFile.readText()).jsonObject.isEmpty())
        assertEquals("維新まんじゅう", GameNames(directory, { error("must use disk") }) { 86_401_001 }.resolve(run(id)).item(id))
    }
}
