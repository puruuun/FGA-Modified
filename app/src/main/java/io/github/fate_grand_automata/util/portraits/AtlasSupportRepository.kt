package io.github.fate_grand_automata.util.portraits

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import timber.log.Timber

interface SupportCatalogSource {
    suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord>
    suspend fun detail(kind: SupportCatalogKind, region: String, id: Int): String?
    suspend fun servantNames(region: String): Map<Int, Set<String>>
    /** Bundled Korean names for servants that are not released on KR yet, keyed by internal servant ID. */
    suspend fun koreanNames(): Map<Int, Set<String>>
}

/** Pure coordinator, also exercised with deterministic regional fixtures in unit tests. */
class SupportImageLookup(private val source: SupportCatalogSource) {
    /** Share links carry internal IDs; collection numbers can collide across regions. */
    suspend fun byId(kind: SupportCatalogKind, id: Int, language: String): SupportCatalogEntry? {
        val catalogs = mutableMapOf<String, List<SupportCatalogRecord>>()
        suspend fun catalog(region: String) = catalogs[region] ?: source.catalog(kind, region).also { catalogs[region] = it }
        val jp = catalog("JP")
        val localized = catalog(SupportImageCatalog.languageRegion(language))
        for (region in SupportImageCatalog.lookupRegions) {
            val record = catalog(region).firstOrNull { it.id == id } ?: continue
            return SupportImageCatalog.merge(kind, listOf(record), localized, jp).single()
                .copy(sourceRegion = if (jp.any { it.id == id }) null else region)
        }
        return null
    }

    suspend fun search(kind: SupportCatalogKind, query: String, language: String): List<SupportCatalogEntry> {
        // Validate even before downloading a catalog for invalid/empty requests.
        SupportImageCatalog.search(emptyList(), query)
        val nameRegion = SupportImageCatalog.languageRegion(language)
        val catalogs = linkedMapOf<String, List<SupportCatalogRecord>>()
        suspend fun catalog(region: String) = catalogs[region] ?: source.catalog(kind, region).also { catalogs[region] = it }
        val jp = catalog("JP")
        val jpIds = jp.map { it.id }.toSet()
        val localized = catalog(nameRegion)
        val nameSearch = kind == SupportCatalogKind.Servant && !SupportImageCatalog.normalized(query).all { it in '0'..'9' }
        val nameTables = linkedMapOf<String, Map<Int, Set<String>>>()
        suspend fun includeNames(region: String) {
            if (nameSearch && region !in nameTables) nameTables[region] = source.servantNames(region)
        }
        includeNames("JP")
        includeNames(nameRegion)
        // The bundled dictionary only covers servants that have no KR record yet.
        val dictionary = if (nameSearch) source.koreanNames() else emptyMap()
        val krIds: Set<Int>? = if (dictionary.isEmpty()) emptySet() else try {
            catalog("KR").map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // KR catalog unavailable: apply the dictionary to every entry rather than failing.
        }
        for (region in SupportImageCatalog.lookupRegions) {
            val records = catalog(region)
            includeNames(region)
            val names = SupportServantNames.merge(nameTables.values)
            val entries = SupportImageCatalog.merge(kind, records, localized, jp)
            val results = SupportImageCatalog.search(entries.map {
                val unreleased = if (krIds == null || it.id !in krIds) dictionary[it.id].orEmpty() else emptySet()
                it.copy(aliases = it.aliases + names[it.id].orEmpty() + unreleased,
                    sourceRegion = if (it.id in jpIds) null else region)
            }, query)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    suspend fun portraits(entry: SupportCatalogEntry): List<ServantPortrait> {
        var found = false
        for (region in SupportImageCatalog.lookupRegions) {
            val data = source.detail(entry.kind, region, entry.id) ?: continue
            found = true
            val pictures = SupportAssetMetadata.parse(data, entry, region)
            if (pictures.isNotEmpty()) return pictures
        }
        throw PortraitImportException(if (found) PortraitImportError.Image else PortraitImportError.NotFound)
    }
}

@Singleton
class AtlasSupportRepository internal constructor(
    private val cache: File,
    private val versionSource: SupportCatalogVersionSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetcher: (suspend (String, Int) -> ByteArray?)? = null,
    private val openAsset: (String) -> InputStream = { error("assets not configured") },
) : SupportCatalogSource {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        versionSource: SupportCatalogVersionSource,
    ) : this(
        cache = File(context.cacheDir, "support-catalogs"),
        versionSource = versionSource,
        now = System::currentTimeMillis,
        fetcher = null,
        openAsset = context.assets::open,
    )

    private val fileMutexes = ConcurrentHashMap<String, Mutex>()
    @Volatile private var koreanNamesCache: Map<Int, Set<String>>? = null

    override suspend fun koreanNames(): Map<Int, Set<String>> {
        koreanNamesCache?.let { return it }
        return withContext(Dispatchers.IO) {
            val names = try {
                SupportKoreanNames.parse(openAsset(SupportKoreanNames.ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Bundled Korean servant names unavailable")
                emptyMap()
            }
            koreanNamesCache = names
            names
        }
    }

    override suspend fun servantNames(region: String): Map<Int, Set<String>> = withContext(Dispatchers.IO) {
        require(region in SupportImageCatalog.regions)
        val file = File(cache, "$region-servant-names-v1.json")
        cachedOrFetch(
            file = file,
            region = region,
            parseCached = SupportServantNames::parseCache,
        ) {
            val parts = coroutineScope {
                ServantNameTable.entries.map { table -> async {
                    // Atlas's public Git API serves current raw tables for each game region.
                    val url = "https://git.atlasacademy.io/api/v1/repos/atlasacademy/fgo-game-data/raw/master/${table.file}.json?ref=$region"
                    val data = download(url, 8_388_608)?.toString(Charsets.UTF_8)
                        ?: throw PortraitImportException(PortraitImportError.Network)
                    SupportServantNames.parse(data, table)
                } }.awaitAll()
            }
            val names = SupportServantNames.merge(parts)
            names to SupportServantNames.encodeCache(names)
        }
    }

    override suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord> = withContext(Dispatchers.IO) {
        require(region in SupportImageCatalog.regions)
        val file = File(cache, "$region-${kind.endpoint}.json")
        cachedOrFetch(
            file = file,
            region = region,
            parseCached = { SupportImageCatalog.parse(it, kind) },
        ) {
            val data = download("https://api.atlasacademy.io/export/$region/basic_${kind.endpoint}.json", 8_388_608)
                ?.toString(Charsets.UTF_8)
                ?: throw PortraitImportException(PortraitImportError.Network)
            SupportImageCatalog.parse(data, kind) to data
        }
    }

    private suspend fun <T> cachedOrFetch(
        file: File,
        region: String,
        parseCached: (String) -> T,
        fetch: suspend () -> Pair<T, String>,
    ): T = fileMutexes.getOrPut(file.name) { Mutex() }.withLock {
        cache.mkdirs()
        val cached = if (file.isFile) runCatching { parseCached(file.readText()) }.getOrNull() else null
        val regionHash = if (region in SupportCatalogFreshness.VERSIONED_REGIONS) {
            versionSource.current()?.get(region)
        } else {
            null
        }
        val hashFile = File(cache, "${file.name}.hash")
        val attemptFile = File(cache, "${file.name}.attempt")
        val nowMs = now()
        when (
            SupportCatalogFreshness.decide(
                region = region,
                hasCached = cached != null,
                lastModifiedMs = file.lastModified(),
                regionHash = regionHash,
                recordedHash = readSidecar(hashFile),
                lastAttemptMs = readSidecar(attemptFile)?.toLongOrNull(),
                nowMs = nowMs,
            )
        ) {
            SupportCatalogAction.UseCache -> cached!!
            SupportCatalogAction.Throttled -> cached ?: throw PortraitImportException(PortraitImportError.Network)
            SupportCatalogAction.Download -> {
                if (regionHash != null) {
                    runCatching { writeSidecar(attemptFile, nowMs.toString()) }
                }
                val (value, encoded) = try {
                    fetch()
                } catch (e: PortraitImportException) {
                    if (cached != null && e.reason == PortraitImportError.Network) return@withLock cached
                    throw e
                }
                // Cache failure must not prevent a successful online search.
                runCatching {
                    writeSidecar(file, encoded)
                    if (nowMs >= 0L) file.setLastModified(nowMs)
                    if (regionHash != null) {
                        writeSidecar(hashFile, regionHash)
                    }
                }
                value
            }
        }
    }

    private fun readSidecar(file: File): String? = runCatching {
        if (!file.isFile) return null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun writeSidecar(target: File, content: String) {
        val tmp = File(cache, "${target.name}.tmp")
        tmp.writeText(content)
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)))
    }

    override suspend fun detail(kind: SupportCatalogKind, region: String, id: Int): String? {
        require(region in SupportImageCatalog.regions && id > 0)
        return download("https://api.atlasacademy.io/nice/$region/${kind.endpoint}/$id", 2_097_152)?.toString(Charsets.UTF_8)
    }

    suspend fun image(url: String): ByteArray {
        if (!SupportAssetMetadata.validUrl(url, "Faces") && !SupportAssetMetadata.validUrl(url, "EquipFaces"))
            throw PortraitImportException(PortraitImportError.Image)
        return download(url, 1_048_576) ?: throw PortraitImportException(PortraitImportError.Image)
    }

    private suspend fun download(url: String, maxBytes: Int): ByteArray? =
        fetcher?.invoke(url, maxBytes)
            ?: withContext(Dispatchers.IO) {
                downloadPublicBytes(url, maxBytes) { coroutineContext.ensureActive() }
            }

    internal companion object {
        fun downloadPublicBytes(
            url: String,
            maxBytes: Int,
            checkActive: () -> Unit = {},
        ): ByteArray? {
            checkActive()
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "FGA-support-images")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> Unit
                    HttpURLConnection.HTTP_NOT_FOUND -> return null
                    else -> throw PortraitImportException(PortraitImportError.Network)
                }
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw PortraitImportException(PortraitImportError.Metadata)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (e: IOException) {
                throw PortraitImportException(PortraitImportError.Network)
            } finally {
                connection.disconnect()
            }
        }
    }
}
