package io.github.fate_grand_automata.util.chaldea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.io.encoding.Base64

enum class ChaldeaImportError { LINK, DATA, TARGETING, NO_ACTIONS, METADATA, NETWORK, UNSUPPORTED }

class ChaldeaImportException(val error: ChaldeaImportError, val detail: String = "") : Exception(error.name)

internal fun fail(error: ChaldeaImportError, detail: String = ""): Nothing = throw ChaldeaImportException(error, detail)
internal fun JsonObject.obj(key: String) = this[key] as? JsonObject
internal fun JsonObject.array(key: String) = this[key] as? JsonArray
internal fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement.objectOrFail() = this as? JsonObject ?: fail(ChaldeaImportError.DATA)

/** Decodes embedded share data only. The pasted URL is never opened or followed. */
object ChaldeaShare {
    private const val MAX_LINK = 1_000_000
    internal const val MAX_JSON_BYTES = 4_000_000

    fun decode(link: String): JsonObject {
        if (link.length > MAX_LINK) fail(ChaldeaImportError.LINK)
        val encoded = try {
            val uri = URI(link.trim())
            if (uri.scheme !in listOf("https", "http") || uri.host == null) fail(ChaldeaImportError.LINK)
            val params = uri.rawQuery.orEmpty().split('&').map { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }.filter { it.first == "data" }
            if (params.size != 1 || params[0].second.isBlank()) fail(ChaldeaImportError.LINK)
            params[0].second
        } catch (e: ChaldeaImportException) {
            throw e
        } catch (_: Exception) {
            fail(ChaldeaImportError.LINK)
        }

        return try {
            // G: current Chaldea gzip format; D/Z: formats accepted by fgo-converter.
            val payload = if (encoded.first() in "GDZ") encoded.drop(1) else encoded
            val normalized = payload.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val bytes = Base64.Default.decode(padded)
            val input = ByteArrayInputStream(bytes)
            val stream = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(input)
            } else InflaterInputStream(input)
            val json = stream.use { it.readLimited(MAX_JSON_BYTES).toString(Charsets.UTF_8) }
            Json.parseToJsonElement(json).objectOrFail().also { data ->
                val actions = data.array("actions") ?: fail(ChaldeaImportError.DATA)
                if (actions.isEmpty()) fail(ChaldeaImportError.NO_ACTIONS)
                if (actions.size > 10_000) fail(ChaldeaImportError.DATA)
                val team = data.obj("team") ?: fail(ChaldeaImportError.DATA)
                if (team.array("onFieldSvts")?.size != 3 || team.array("backupSvts")?.size != 3) {
                    fail(ChaldeaImportError.DATA)
                }
            }
        } catch (e: ChaldeaImportException) {
            throw e
        } catch (_: Exception) {
            fail(ChaldeaImportError.DATA)
        }
    }
}

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) fail(ChaldeaImportError.DATA)
        output.write(buffer, 0, count)
    }
}

interface ChaldeaMetadata {
    suspend fun servant(id: Int): JsonObject
    suspend fun mysticCode(id: Int): JsonObject
}
