package io.github.fate_grand_automata.util.chaldea

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtlasChaldeaMetadataTest {
    @TempDir lateinit var cache: File
    private val data = """{"id":123,"skills":[],"noblePhantasms":[]}"""

    @Test fun `successful game data is reused without another request`() = runBlocking {
        var calls = 0
        val provider = AtlasChaldeaMetadata(cache) { kind, id ->
            calls++
            assertEquals("svt", kind)
            assertEquals(123, id)
            data
        }
        repeat(2) { assertEquals(123, provider.servant(123).int("id")) }
        assertEquals(1, calls)
    }

    @Test fun `failed refresh can use a valid old snapshot`() = runBlocking {
        File(cache, "JP-svt-123.json").apply { writeText(data); setLastModified(1) }
        val provider = AtlasChaldeaMetadata(cache) { _, _ -> throw IOException("offline") }
        assertEquals(123, provider.servant(123).int("id"))
    }

    @Test fun `corrupt cache is fetched again`() = runBlocking {
        File(cache, "JP-MC-123.json").writeText("broken")
        val provider = AtlasChaldeaMetadata(cache) { kind, _ -> assertEquals("MC", kind); data }
        assertEquals(123, provider.mysticCode(123).int("id"))
    }

    @Test fun `unexpected metadata never overwrites the cache`() = runBlocking {
        val provider = AtlasChaldeaMetadata(cache) { _, _ -> data.replace("123", "456") }
        assertEquals(ChaldeaImportError.METADATA, assertFailsWith<ChaldeaImportException> { provider.servant(123) }.error)
        assertEquals(emptyList(), cache.listFiles()!!.toList())
    }

    @Test fun `connection failure without a snapshot is reported`() = runBlocking {
        val provider = AtlasChaldeaMetadata(cache) { _, _ -> throw IOException("offline") }
        assertEquals(ChaldeaImportError.NETWORK, assertFailsWith<ChaldeaImportException> { provider.servant(123) }.error)
    }

    @Test fun `cancellation is not replaced by a cached success`() = runBlocking {
        File(cache, "JP-svt-123.json").apply { writeText(data); setLastModified(1) }
        val provider = AtlasChaldeaMetadata(cache) { _, _ -> throw CancellationException() }
        assertFailsWith<CancellationException> { provider.servant(123) }
        Unit
    }
}

class AtlasChaldeaQuestTest {
    @TempDir lateinit var cache: File
    private val quest = """{"id":94150847,"phase":1,"stages":[{"wave":1}]}"""
    private val unused: suspend (String, Int) -> String = { _, _ -> error("unused") }

    @Test fun `quest data is downloaded once per day and cached by id and phase`() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val provider = AtlasChaldeaMetadata(cache, { id, phase -> requests.add(id to phase); quest }, unused)
        repeat(2) { assertEquals(1, provider.quest(94150847, 1).array("stages")!!.size) }
        assertEquals(listOf(94150847 to 1), requests)
        assertEquals(true, File(cache, "JP-quest-94150847-1.json").exists())
        provider.quest(94150847, 2)
        assertEquals(2, requests.size)
    }

    @Test fun `failed quest refresh keeps and uses the old snapshot`() = runBlocking {
        File(cache, "JP-quest-94150847-1.json").apply { writeText(quest); setLastModified(1) }
        val provider = AtlasChaldeaMetadata(cache, { _, _ -> throw IOException("offline") }, unused)
        assertEquals(1, provider.quest(94150847, 1).array("stages")!!.size)
        assertEquals(quest, File(cache, "JP-quest-94150847-1.json").readText())
    }

    @Test fun `unexpected quest data is rejected and never cached`() = runBlocking {
        val wrongId = AtlasChaldeaMetadata(cache, { _, _ -> quest.replace("94150847", "1") }, unused)
        assertEquals(ChaldeaImportError.METADATA, assertFailsWith<ChaldeaImportException> { wrongId.quest(94150847, 1) }.error)
        val noStages = AtlasChaldeaMetadata(cache, { _, _ -> """{"id":94150847}""" }, unused)
        assertEquals(ChaldeaImportError.METADATA, assertFailsWith<ChaldeaImportException> { noStages.quest(94150847, 1) }.error)
        assertEquals(emptyList(), cache.listFiles()!!.toList())
    }

    @Test fun `quest failure without a snapshot is reported as a network error`() = runBlocking {
        val provider = AtlasChaldeaMetadata(cache, { _, _ -> throw IOException("offline") }, unused)
        assertEquals(ChaldeaImportError.NETWORK, assertFailsWith<ChaldeaImportException> { provider.quest(94150847, 1) }.error)
    }
}
