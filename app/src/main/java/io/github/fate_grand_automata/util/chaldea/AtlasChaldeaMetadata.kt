package io.github.fate_grand_automata.util.chaldea

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** JP data matches the reference converter, independently of the FGA game-server setting. */
class AtlasChaldeaMetadata(
    private val cacheDir: File,
    private val downloadQuest: suspend (Int, Int) -> String = { id, phase -> downloadAtlas("quest/$id/$phase") },
    private val download: suspend (String, Int) -> String = { kind, id -> downloadAtlas("$kind/$id") }
) : ChaldeaMetadata {
    override suspend fun servant(id: Int) = load("svt", id, "skills") { download("svt", id) }
    override suspend fun mysticCode(id: Int) = load("MC", id, "skills") { download("MC", id) }
    override suspend fun quest(id: Int, phase: Int) = load("quest", id, "stages", phase) { downloadQuest(id, phase) }

    private suspend fun load(
        kind: String, id: Int, required: String, phase: Int? = null, fetch: suspend () -> String
    ): JsonObject = withContext(Dispatchers.IO) {
        if (id <= 0 || (phase != null && phase <= 0)) fail(ChaldeaImportError.DATA)
        coroutineContext.ensureActive()
        val file = File(cacheDir, "JP-$kind-$id" + (phase?.let { "-$it" } ?: "") + ".json")
        // Cache errors never prevent a fresh request. Failed downloads never replace good data.
        val cached = runCatching {
            file.inputStream().use { input ->
                parse(input.readLimited(ChaldeaShare.MAX_JSON_BYTES).toString(Charsets.UTF_8), id, required)
            }
        }.getOrNull()
        if (cached != null && System.currentTimeMillis() - file.lastModified() in 0..86_400_000L) {
            return@withContext cached
        }
        try {
            val text = fetch()
            coroutineContext.ensureActive()
            val data = parse(text, id, required)
            runCatching {
                cacheDir.mkdirs()
                val temp = File.createTempFile("atlas-", ".tmp", cacheDir)
                try {
                    temp.writeText(text)
                    temp.renameTo(file)
                } finally {
                    temp.delete()
                }
            }
            data
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            coroutineContext.ensureActive()
            // A previous valid snapshot can still convert known skills without a connection.
            cached ?: if (e is ChaldeaImportException) throw e else fail(ChaldeaImportError.NETWORK)
        }
    }

    private fun parse(text: String, id: Int, required: String): JsonObject {
        val data = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (data == null || data.int("id") != id || data.array(required) == null) {
            fail(ChaldeaImportError.METADATA, "$id")
        }
        return data
    }

    companion object {
        private fun downloadAtlas(path: String): String {
            val connection = URL("https://api.atlasacademy.io/nice/JP/$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 7_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "FGA-mod-Chaldea-import")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) fail(ChaldeaImportError.NETWORK)
                return connection.inputStream.use { it.readLimited(ChaldeaShare.MAX_JSON_BYTES) }.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
    }
}
