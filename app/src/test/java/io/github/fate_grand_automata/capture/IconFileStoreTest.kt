package io.github.fate_grand_automata.capture

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class IconFileStoreTest {
    @TempDir lateinit var directory: File
    private val url = "https://static.atlasacademy.io/KR/Items/1.png"

    @Test fun downloadsOnceThenReusesAcrossInstancesEvenWithoutUrl() = runBlocking {
        var calls = 0
        val store = IconFileStore(directory, { calls++; byteArrayOf(1, 2, 3) })
        val results = (1..8).map { async { store.resolve(1, url) } }.awaitAll()
        assertEquals(1, calls)
        assertTrue(results.all { it == File(directory, "1.png") })
        assertContentEquals(byteArrayOf(1, 2, 3), results.first()!!.readBytes())
        assertEquals(results.first(), IconFileStore(directory, { error("must reuse disk file") }).resolve(1, null))
        assertFalse(File(directory, "1.tmp").exists())
    }

    @Test fun failedDownloadReturnsFallbackAndBacksOffAcrossRestartThenRetries() = runBlocking {
        var calls = 0
        var time = 1000L
        val offline: (String) -> ByteArray = { calls++; error("offline") }
        assertNull(IconFileStore(directory, offline) { time }.resolve(1, url))
        assertFalse(File(directory, "1.png").exists())
        time++
        assertNull(IconFileStore(directory, offline) { time }.resolve(1, url))
        assertEquals(1, calls)
        time += IconFileStore.RETRY_MS
        assertNotNull(IconFileStore(directory, { calls++; byteArrayOf(9) }) { time }.resolve(1, url))
        assertEquals(2, calls)
    }

    @Test fun rejectsNonHttpsOtherHostsCredentialsPortsAndInvalidIdsBeforeDownload() = runBlocking {
        val store = IconFileStore(directory, { error("must not download") })
        listOf("http://static.atlasacademy.io/x", "https://static.atlasacademy.io.evil.test/x",
            "https://evil.test/x", "file:///private", "https://user@static.atlasacademy.io/x",
            "https://static.atlasacademy.io:444/x", "broken").forEach { assertNull(store.resolve(1, it)) }
        assertNull(store.resolve(-1, url))
        assertNull(store.resolve(1, null))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun emptyResponseNeverPublishesFile() = runBlocking {
        assertNull(IconFileStore(directory, { byteArrayOf() }).resolve(1, url))
        assertFalse(File(directory, "1.png").exists())
    }
}
