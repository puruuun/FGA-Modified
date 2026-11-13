package io.github.fate_grand_automata.util.portraits

import io.github.fate_grand_automata.capture.AtlasVersions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasSupportRepositoryTest {
    @TempDir lateinit var root: File

    private val cacheDir get() = File(root, "support-catalogs")
    private val namesDir get() = File(root, "capture/names")
    private val dayMs = SupportCatalogFreshness.DAY_MS
    private val hourMs = SupportCatalogFreshness.HOUR_MS

    private fun catalogJson(id: Int, number: Int, name: String, kind: SupportCatalogKind = SupportCatalogKind.Servant): String {
        val type = if (kind == SupportCatalogKind.CE) "servantEquip" else "normal"
        return """[{"id":$id,"collectionNo":$number,"type":"$type","name":"$name"}]"""
    }

    private fun servantTableJson(url: String, id: Int, baseName: String, ascName: String): String = when {
        "mstSvt.json" in url -> """[{"id":$id,"name":"$baseName"}]"""
        "mstSvtLimitAdd.json" in url -> """[{"svtId":$id,"script":{"overWriteServantName":"$ascName"}}]"""
        "mstSvtChange.json" in url -> """[]"""
        else -> error("unexpected url: $url")
    }

    @Test fun hashEqualAndOlderThanOneDaySkipsDownloadForCatalogAndServantNames() = runBlocking {
        var time = 1_000_000_000L
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val hashes = mapOf("KR" to "kr1", "JP" to "jp1")
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { hashes },
            now = { time },
            fetcher = { url, _ ->
                urls += url
                when {
                    url.endsWith("/JP/basic_servant.json") -> catalogJson(100600, 90, "ネロ").toByteArray()
                    url.endsWith("/JP/basic_equip.json") -> catalogJson(9400340, 34, "カレイド", SupportCatalogKind.CE).toByteArray()
                    "ref=JP" in url -> servantTableJson(url, 100600, "ネロ", "ブライド").toByteArray()
                    else -> error("unexpected url: $url")
                }
            },
        )

        assertEquals("ネロ", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals("カレイド", repo.catalog(SupportCatalogKind.CE, "JP").single().name)
        assertEquals(setOf("ネロ", "ブライド"), repo.servantNames("JP")[100600])
        assertEquals("jp1", File(cacheDir, "JP-servant.json.hash").readText())
        assertEquals("jp1", File(cacheDir, "JP-equip.json.hash").readText())
        assertEquals("jp1", File(cacheDir, "JP-servant-names-v1.json.hash").readText())

        // Age cache files well beyond 1 day while keeping the same hashes.
        time += 5 * dayMs
        urls.clear()

        assertEquals("ネロ", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals("カレイド", repo.catalog(SupportCatalogKind.CE, "JP").single().name)
        assertEquals(setOf("ネロ", "ブライド"), repo.servantNames("JP")[100600])
        assertTrue(urls.isEmpty(), "Expected no downloads when hash matches, got $urls")
    }

    @Test fun differingHashDownloadsOnceAndBlocksSecondAttemptWithinOneHour() = runBlocking {
        var time = 1_000_000_000L
        var hashes = mapOf("KR" to "kr1", "JP" to "jp1")
        var failDownloads = false
        var version = 1
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { hashes },
            now = { time },
            fetcher = { url, _ ->
                urls += url
                if (failDownloads) throw PortraitImportException(PortraitImportError.Network)
                when {
                    "/basic_servant.json" in url -> catalogJson(100600, 90, "v$version").toByteArray()
                    "mstSvt" in url -> servantTableJson(url, 100600, "base$version", "asc$version").toByteArray()
                    else -> error("unexpected url: $url")
                }
            },
        )

        // Initial fetch populates cache with hash jp1.
        assertEquals("v1", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("base1", "asc1"), repo.servantNames("JP")[100600])

        // Hash changes to jp2, download fails -> attempted once, returns old cache, second attempt within 1h blocked.
        time += hourMs
        hashes = mapOf("KR" to "kr1", "JP" to "jp2")
        failDownloads = true
        urls.clear()

        assertEquals("v1", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("base1", "asc1"), repo.servantNames("JP")[100600])
        assertEquals(1, urls.count { it.endsWith("/JP/basic_servant.json") })
        assertEquals(3, urls.count { "ref=JP" in it })
        assertEquals("jp1", File(cacheDir, "JP-servant.json.hash").readText())
        assertEquals("jp1", File(cacheDir, "JP-servant-names-v1.json.hash").readText())

        // Within 1 hour: blocked without calling fetcher.
        time += hourMs - 1
        urls.clear()
        assertEquals("v1", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("base1", "asc1"), repo.servantNames("JP")[100600])
        assertTrue(urls.isEmpty(), "Expected hourly throttle to block retry, got $urls")

        // After 1 hour: retries and updates cache + hash.
        time += 1
        failDownloads = false
        version = 2
        urls.clear()
        assertEquals("v2", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("base2", "asc2"), repo.servantNames("JP")[100600])
        assertEquals("jp2", File(cacheDir, "JP-servant.json.hash").readText())
        assertEquals("jp2", File(cacheDir, "JP-servant-names-v1.json.hash").readText())
        assertEquals(1, urls.count { it.endsWith("/JP/basic_servant.json") })
        assertEquals(3, urls.count { "ref=JP" in it })

        // Even if hash changes again within the same hour after a successful download, second attempt is blocked.
        time += 1_000L
        hashes = mapOf("KR" to "kr1", "JP" to "jp3")
        version = 3
        urls.clear()
        assertEquals("v2", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("base2", "asc2"), repo.servantNames("JP")[100600])
        assertTrue(urls.isEmpty())
    }

    @Test fun throttledAttemptWithoutCacheThrowsNetworkError() = runBlocking {
        var time = 1_000_000_000L
        var calls = 0
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { mapOf("KR" to "kr1", "JP" to "jp1") },
            now = { time },
            fetcher = { _, _ ->
                calls++
                throw PortraitImportException(PortraitImportError.Network)
            },
        )

        assertEquals(
            PortraitImportError.Network,
            assertFailsWith<PortraitImportException> { repo.catalog(SupportCatalogKind.Servant, "KR") }.reason,
        )
        assertEquals(1, calls)

        time += hourMs - 1
        assertEquals(
            PortraitImportError.Network,
            assertFailsWith<PortraitImportException> { repo.catalog(SupportCatalogKind.Servant, "KR") }.reason,
        )
        assertEquals(1, calls)
    }

    @Test fun oldCacheWithoutRecordedHashDownloadsInVersionMode() = runBlocking {
        val time = 1_000_000_000L
        cacheDir.mkdirs()
        File(cacheDir, "KR-servant.json").apply {
            writeText(catalogJson(100600, 90, "구버전"))
            setLastModified(time)
        }
        File(cacheDir, "KR-servant-names-v1.json").apply {
            writeText("""{"100600":["구이름"]}""")
            setLastModified(time)
        }

        val urls = Collections.synchronizedList(mutableListOf<String>())
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { mapOf("KR" to "kr1", "JP" to "jp1") },
            now = { time },
            fetcher = { url, _ ->
                urls += url
                when {
                    url.endsWith("/KR/basic_servant.json") -> catalogJson(100600, 90, "신버전").toByteArray()
                    "ref=KR" in url -> servantTableJson(url, 100600, "신이름", "이명").toByteArray()
                    else -> error("unexpected url: $url")
                }
            },
        )

        assertEquals("신버전", repo.catalog(SupportCatalogKind.Servant, "KR").single().name)
        assertEquals(setOf("신이름", "이명"), repo.servantNames("KR")[100600])
        assertEquals("kr1", File(cacheDir, "KR-servant.json.hash").readText())
        assertEquals("kr1", File(cacheDir, "KR-servant-names-v1.json.hash").readText())
        assertEquals(1, urls.count { it.endsWith("/KR/basic_servant.json") })
        assertEquals(3, urls.count { "ref=KR" in it })
    }

    @Test fun unknownVersionUsesOneDayRuleExactlyAsBefore() = runBlocking {
        var time = 1_000_000_000L
        cacheDir.mkdirs()
        val catalogFile = File(cacheDir, "JP-servant.json").apply {
            writeText(catalogJson(100600, 90, "old-cat"))
            setLastModified(time)
        }
        val namesFile = File(cacheDir, "JP-servant-names-v1.json").apply {
            writeText("""{"100600":["old-name"]}""")
            setLastModified(time)
        }
        // Pre-existing stale hash files from an earlier versioned run.
        val catalogHash = File(cacheDir, "JP-servant.json.hash").apply { writeText("stale") }
        val namesHash = File(cacheDir, "JP-servant-names-v1.json.hash").apply { writeText("stale") }

        var fail = false
        val calls = AtomicInteger(0)
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { null },
            now = { time },
            fetcher = { url, _ ->
                calls.incrementAndGet()
                if (fail) throw PortraitImportException(PortraitImportError.Network)
                when {
                    "/basic_servant.json" in url -> catalogJson(100600, 90, "new-cat").toByteArray()
                    else -> servantTableJson(url, 100600, "new-name", "new-asc").toByteArray()
                }
            },
        )

        // Within 1 day: uses cache without download even without matching hash.
        time += dayMs
        assertEquals("old-cat", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("old-name"), repo.servantNames("JP")[100600])
        assertEquals(0, calls.get())

        // Past 1 day with failing network: tries on every call (no hourly throttle), falls back to old cache.
        time += 1
        fail = true
        assertEquals("old-cat", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals("old-cat", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(2, calls.get())
        assertEquals(setOf("old-name"), repo.servantNames("JP")[100600])
        assertEquals(5, calls.get())

        // Past 1 day when network recovers: downloads new data and clears stale hash files.
        fail = false
        assertEquals("new-cat", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals(setOf("new-name", "new-asc"), repo.servantNames("JP")[100600])
        assertFalse(catalogHash.exists())
        assertFalse(namesHash.exists())
        assertTrue(catalogFile.isFile)
        assertTrue(namesFile.isFile)
    }

    @Test fun nonKrJpRegionUsesOneDayRuleAndDoesNotQueryVersionSource() = runBlocking {
        var time = 1_000_000_000L
        val versionCalls = AtomicInteger(0)
        val downloads = AtomicInteger(0)
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = {
                versionCalls.incrementAndGet()
                mapOf("KR" to "kr1", "JP" to "jp1")
            },
            now = { time },
            fetcher = { url, _ ->
                val count = downloads.incrementAndGet()
                when {
                    "/NA/basic_servant.json" in url -> catalogJson(100600, 90, "Nero v$count").toByteArray()
                    "ref=NA" in url -> servantTableJson(url, 100600, "Nero", "Bride").toByteArray()
                    else -> error("unexpected url: $url")
                }
            },
        )

        assertEquals("Nero v1", repo.catalog(SupportCatalogKind.Servant, "NA").single().name)
        assertEquals(setOf("Nero", "Bride"), repo.servantNames("NA")[100600])
        assertEquals(0, versionCalls.get())
        assertFalse(File(cacheDir, "NA-servant.json.hash").exists())
        assertFalse(File(cacheDir, "NA-servant-names-v1.json.hash").exists())

        // Within 1 day: uses cache without download or version lookup.
        time += dayMs
        assertEquals("Nero v1", repo.catalog(SupportCatalogKind.Servant, "NA").single().name)
        assertEquals(setOf("Nero", "Bride"), repo.servantNames("NA")[100600])
        assertEquals(4, downloads.get())
        assertEquals(0, versionCalls.get())

        // Past 1 day: downloads again without creating hash files.
        time += 1
        assertEquals("Nero v5", repo.catalog(SupportCatalogKind.Servant, "NA").single().name)
        assertFalse(File(cacheDir, "NA-servant.json.hash").exists())
        assertEquals(0, versionCalls.get())
    }

    @Test fun hashIsRecordedOnlyAfterSuccessfulDownloadAndParse() = runBlocking {
        var time = 1_000_000_000L
        var hashes = mapOf("KR" to "kr1", "JP" to "jp1")
        var mode = "network-fail"
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { hashes },
            now = { time },
            fetcher = { url, _ ->
                when (mode) {
                    "network-fail" -> throw PortraitImportException(PortraitImportError.Network)
                    "bad-json" -> "not-json".toByteArray()
                    "ok" -> when {
                        "/basic_servant.json" in url -> catalogJson(100600, 90, "ok").toByteArray()
                        else -> servantTableJson(url, 100600, "ok", "ok2").toByteArray()
                    }
                    else -> error(mode)
                }
            },
        )

        assertFailsWith<PortraitImportException> { repo.catalog(SupportCatalogKind.Servant, "KR") }
        assertFailsWith<PortraitImportException> { repo.servantNames("KR") }
        assertFalse(File(cacheDir, "KR-servant.json.hash").exists())
        assertFalse(File(cacheDir, "KR-servant-names-v1.json.hash").exists())

        time += hourMs
        mode = "bad-json"
        assertEquals(
            PortraitImportError.Metadata,
            assertFailsWith<PortraitImportException> { repo.catalog(SupportCatalogKind.Servant, "KR") }.reason,
        )
        assertEquals(
            PortraitImportError.Metadata,
            assertFailsWith<PortraitImportException> { repo.servantNames("KR") }.reason,
        )
        assertFalse(File(cacheDir, "KR-servant.json.hash").exists())
        assertFalse(File(cacheDir, "KR-servant-names-v1.json.hash").exists())

        time += hourMs
        mode = "ok"
        assertEquals("ok", repo.catalog(SupportCatalogKind.Servant, "KR").single().name)
        assertEquals(setOf("ok", "ok2"), repo.servantNames("KR")[100600])
        assertEquals("kr1", File(cacheDir, "KR-servant.json.hash").readText())
        assertEquals("kr1", File(cacheDir, "KR-servant-names-v1.json.hash").readText())

        // On later failure when hash updates to kr2, existing kr1 hash is preserved.
        time += hourMs
        hashes = mapOf("KR" to "kr2", "JP" to "jp1")
        mode = "bad-json"
        assertEquals(
            PortraitImportError.Metadata,
            assertFailsWith<PortraitImportException> { repo.catalog(SupportCatalogKind.Servant, "KR") }.reason,
        )
        assertEquals("kr1", File(cacheDir, "KR-servant.json.hash").readText())
    }

    @Test fun repositorySharesAtlasVersionsStateDirectoryAcrossInstances() = runBlocking {
        var time = 1_000_000_000L
        val infoCalls = mutableListOf<String>()
        val gameNamesVersions = AtlasVersions(
            directory = namesDir,
            download = { url ->
                infoCalls += url
                """{"KR":{"hash":"kr1"},"JP":{"hash":"jp1"}}"""
            },
            now = { time },
        )
        assertEquals(mapOf("KR" to "kr1", "JP" to "jp1"), gameNamesVersions.current())
        assertEquals(listOf(AtlasVersions.INFO_URL), infoCalls)

        val supportVersions = AtlasVersions(
            directory = namesDir,
            download = { error("must reuse shared atlas_info.json within the hour: $it") },
            now = { time + hourMs - 1 },
        )
        val repo = AtlasSupportRepository(
            cache = cacheDir,
            versionSource = { supportVersions.current() },
            now = { time + hourMs - 1 },
            fetcher = { url, _ ->
                assertTrue(url.endsWith("/JP/basic_servant.json"))
                catalogJson(100600, 90, "shared").toByteArray()
            },
        )
        assertEquals("shared", repo.catalog(SupportCatalogKind.Servant, "JP").single().name)
        assertEquals("jp1", File(cacheDir, "JP-servant.json.hash").readText())
    }
}
