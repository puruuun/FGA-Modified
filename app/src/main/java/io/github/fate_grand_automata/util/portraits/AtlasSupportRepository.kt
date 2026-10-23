package io.github.fate_grand_automata.util.portraits

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

interface SupportCatalogSource {
    suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord>
    suspend fun detail(kind: SupportCatalogKind, region: String, id: Int): String?
    suspend fun servantNames(region: String): Map<Int, Set<String>>
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
        for (region in SupportImageCatalog.lookupRegions) {
            val records = catalog(region)
            includeNames(region)
            val names = SupportServantNames.merge(nameTables.values)
            val entries = SupportImageCatalog.merge(kind, records, localized, jp)
            val results = SupportImageCatalog.search(entries.map {
                it.copy(aliases = it.aliases + names[it.id].orEmpty(),
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
class AtlasSupportRepository @Inject constructor(@param:ApplicationContext context: Context) : SupportCatalogSource {
    private val cache = File(context.cacheDir, "support-catalogs")

    override suspend fun servantNames(region: String): Map<Int, Set<String>> = withContext(Dispatchers.IO) {
        require(region in SupportImageCatalog.regions)
        cache.mkdirs()
        val file = File(cache, "$region-servant-names-v1.json")
        val cached = if (file.isFile) runCatching { SupportServantNames.parseCache(file.readText()) }.getOrNull() else null
        if (cached != null && System.currentTimeMillis() - file.lastModified() in 0L..86_400_000L) return@withContext cached
        val names = try {
            val parts = coroutineScope {
                ServantNameTable.entries.map { table -> async {
                    // Atlas's public Git API serves current raw tables for each game region.
                    val url = "https://git.atlasacademy.io/api/v1/repos/atlasacademy/fgo-game-data/raw/master/${table.file}.json?ref=$region"
                    val data = download(url, 8_388_608)?.toString(Charsets.UTF_8)
                        ?: throw PortraitImportException(PortraitImportError.Network)
                    SupportServantNames.parse(data, table)
                } }.awaitAll()
            }
            SupportServantNames.merge(parts)
        } catch (e: PortraitImportException) {
            if (cached != null && e.reason == PortraitImportError.Network) return@withContext cached
            throw e
        }
        runCatching {
            val tmp = File(cache, "${file.name}.tmp")
            tmp.writeText(SupportServantNames.encodeCache(names))
            tmp.renameTo(file)
        }
        names
    }

    override suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord> = withContext(Dispatchers.IO) {
        require(region in SupportImageCatalog.regions)
        cache.mkdirs()
        val file = File(cache, "$region-${kind.endpoint}.json")
        val cached = if (file.isFile) runCatching { SupportImageCatalog.parse(file.readText(), kind) }.getOrNull() else null
        if (cached != null && System.currentTimeMillis() - file.lastModified() in 0L..86_400_000L) return@withContext cached
        val data = try {
            download("https://api.atlasacademy.io/export/$region/basic_${kind.endpoint}.json", 8_388_608)?.toString(Charsets.UTF_8)
                ?: throw PortraitImportException(PortraitImportError.Network)
        } catch (e: PortraitImportException) {
            if (cached != null && e.reason == PortraitImportError.Network) return@withContext cached
            throw e
        }
        val records = SupportImageCatalog.parse(data, kind)
        // Cache failure must not prevent a successful online search.
        runCatching { val tmp = File(cache, "${file.name}.tmp"); tmp.writeText(data); tmp.renameTo(file) }
        records
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

    private suspend fun download(url: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FGA-support-images")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                else -> throw PortraitImportException(PortraitImportError.Network)
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw PortraitImportException(PortraitImportError.Metadata)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (e: IOException) {
            throw PortraitImportException(PortraitImportError.Network)
        } finally { connection.disconnect() }
    }
}
