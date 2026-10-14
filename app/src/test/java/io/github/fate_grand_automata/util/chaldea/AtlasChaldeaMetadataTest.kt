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
