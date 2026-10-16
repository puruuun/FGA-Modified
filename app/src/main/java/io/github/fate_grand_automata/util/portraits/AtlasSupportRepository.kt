package io.github.fate_grand_automata.util.portraits

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
}

/** Pure coordinator, also exercised with deterministic regional fixtures in unit tests. */
class SupportImageLookup(private val source: SupportCatalogSource) {
    suspend fun search(kind: SupportCatalogKind, query: String, language: String, gameRegion: String): List<SupportCatalogEntry> {
        // Validate even before downloading a catalog for invalid/empty requests.
        SupportImageCatalog.search(emptyList(), query)
        val nameRegion = SupportImageCatalog.languageRegion(language)
        require(gameRegion in SupportImageCatalog.regions)
        val catalogs = linkedMapOf<String, List<SupportCatalogRecord>>()
        for (region in setOf("JP", nameRegion, gameRegion)) catalogs[region] = source.catalog(kind, region)
        return SupportImageCatalog.search(SupportImageCatalog.merge(kind, catalogs.getValue("JP"),
            catalogs.getValue(nameRegion), catalogs.getValue(gameRegion)), query)
    }

    suspend fun portraits(entry: SupportCatalogEntry, gameRegion: String): List<ServantPortrait> {
        require(gameRegion in SupportImageCatalog.regions)
        val jp = source.detail(entry.kind, "JP", entry.id)
        val local = if (gameRegion == "JP") null else source.detail(entry.kind, gameRegion, entry.id)
        if (jp == null && local == null) throw PortraitImportException(PortraitImportError.NotFound)
        val result = linkedMapOf<String, ServantPortrait>()
        // Retain JP-only costumes; use actual game-region art for matching common stages.
        if (jp != null) SupportAssetMetadata.parse(jp, entry, "JP").forEach { result[it.key] = it }
        if (local != null) SupportAssetMetadata.parse(local, entry, gameRegion).forEach { result[it.key] = it }
        return result.values.distinctBy { it.url }.ifEmpty { throw PortraitImportException(PortraitImportError.Image) }
    }
}

@Singleton
class AtlasSupportRepository @Inject constructor(@param:ApplicationContext context: Context) : SupportCatalogSource {
    private val cache = File(context.cacheDir, "support-catalogs")

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
