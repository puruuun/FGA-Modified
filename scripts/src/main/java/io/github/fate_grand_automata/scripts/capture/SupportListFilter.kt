package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** The servants (and optionally CEs) the owner wants to see in the support list; matched by svtId. */
data class SupportListCriteria(val servantIds: Set<Long>, val ceIds: Set<Long> = emptySet())

/** Counts only, for the debug log: no ids and no names. */
data class SupportListCounts(val friendsBefore: Int, val friendsAfter: Int, val slotsBefore: Int, val slotsAfter: Int)

/** [wire] is the body as it goes to the game: filtered and encoded like the original. */
class SupportListFiltered(val wire: ByteArray, val counts: SupportListCounts)

/**
 * Experiment (#636): leaves the support slots that do not match out of a follower/list response.
 * It only removes entries. Every call returns null ("unchanged") on anything unexpected, so the caller
 * forwards the original bytes; nothing is thrown.
 *
 * The result is the compact JSON of the parsed tree. Key order is kept (the tree keeps insertion order) and
 * numbers are written as the digits they were read with, but whitespace is dropped and string escapes are
 * normalised, so the bytes differ from the original even though the content is the same.
 */
object SupportListFilter {
    const val PATH = "follower/list"

    /**
     * A response body is held in memory only up to this size. The tree of a parsed JSON text is many times
     * the text, and the phone's heap is shared with the game's capture work, so a larger body is passed through.
     */
    const val MAX_WIRE_BYTES = 2 * 1024 * 1024
    const val MAX_DECODED_BYTES = 4 * 1024 * 1024

    private val SLOT_HASHES = listOf("userSvtLeaderHash", "eventUserSvtLeaderHash", "userSvtGrandHash")
    private const val GRAND_HASH = "userSvtGrandHash"

    /** [wire] is the body as received (encoded as [contentEncoding] says). */
    fun apply(wire: ByteArray, contentEncoding: String?, criteria: SupportListCriteria): SupportListFiltered? = try {
        applyUnsafe(wire, contentEncoding, criteria)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun applyUnsafe(wire: ByteArray, contentEncoding: String?, criteria: SupportListCriteria): SupportListFiltered? {
        if (criteria.servantIds.isEmpty() || wire.isEmpty() || wire.size > MAX_WIRE_BYTES) return null
        val encoding = contentEncoding?.trim()?.lowercase().orEmpty()
        if (encoding !in SUPPORTED_ENCODINGS) return null
        val decoded = BodyDecoder.decode(wire, encoding.ifEmpty { null }, MAX_DECODED_BYTES)
        val text = String(decoded, Charsets.UTF_8)
        // Invalid UTF-8 became U+FFFD while decoding: writing the text back would change those bytes.
        if (text.indexOf('�') >= 0 || JsonRisks.found(text)) return null
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val filtered = filter(root, criteria) ?: return null
        // toString, not encodeToString: the serializer round-trips numbers through Double (1.50 becomes 1.5, 1e5 becomes 100000.0).
        val out = filtered.first.toString().toByteArray(Charsets.UTF_8)
        if (out.size > MAX_DECODED_BYTES) return null
        val encoded = encode(out, encoding, deflateIsRaw(wire, encoding)) ?: return null
        return SupportListFiltered(encoded, filtered.second)
    }

    /** The filtered tree and its counts, or null when nothing was left out or the shape is not the expected one. */
    internal fun filter(root: JsonObject, criteria: SupportListCriteria): Pair<JsonObject, SupportListCounts>? {
        if (criteria.servantIds.isEmpty()) return null
        val cache = root["cache"] as? JsonObject ?: return null
        val updated = cache["updated"] as? JsonObject ?: return null
        val followers = updated["userFollower"] as? JsonArray ?: return null
        var friendsBefore = 0
        var friendsAfter = 0
        var slotsBefore = 0
        var slotsAfter = 0
        // A friend with no readable slot hash is a shape this code has not seen: it must not be counted as empty and removed.
        var unknownShape = false
        val newFollowers = followers.map { follower ->
            val entry = follower as? JsonObject ?: return@map follower
            val infos = entry["followerInfo"] as? JsonArray ?: return@map follower
            val kept = infos.mapNotNull { info ->
                val friend = info as? JsonObject ?: return@mapNotNull info
                if (friend.long("type") != 1L) return@mapNotNull friend
                friendsBefore++
                val result = filterFriend(friend, criteria)
                if (!result.readable) unknownShape = true
                slotsBefore += result.before
                slotsAfter += result.after
                if (result.after > 0) friendsAfter++
                result.friend.takeIf { result.after > 0 }
            }
            if (kept.size == infos.size && kept.zip(infos).all { it.first === it.second }) entry
            else JsonObject(entry + ("followerInfo" to JsonArray(kept)))
        }
        if (unknownShape || (slotsAfter == slotsBefore && friendsAfter == friendsBefore)) return null
        val counts = SupportListCounts(friendsBefore, friendsAfter, slotsBefore, slotsAfter)
        val newUpdated = JsonObject(updated + ("userFollower" to JsonArray(newFollowers)))
        val newCache = JsonObject(cache + ("updated" to newUpdated))
        return JsonObject(root + ("cache" to newCache)) to counts
    }

    private class FriendResult(val friend: JsonObject, val before: Int, val after: Int, val readable: Boolean)

    private fun filterFriend(friend: JsonObject, criteria: SupportListCriteria): FriendResult {
        var before = 0
        var after = 0
        var readable = false
        val replaced = LinkedHashMap<String, JsonElement>()
        for (key in SLOT_HASHES) {
            val grand = key == GRAND_HASH
            when (val hash = friend[key]) {
                is JsonArray -> {
                    readable = true
                    val kept = hash.filter { slot -> passes(slot, grand, criteria) }
                    before += hash.size
                    after += kept.size
                    if (kept.size != hash.size) replaced[key] = JsonArray(kept)
                }
                // A hash keyed by deck or seat: removing an entry keeps the other keys and their order.
                is JsonObject -> {
                    readable = true
                    val kept = hash.filterValues { slot -> passes(slot, grand, criteria) }
                    before += hash.size
                    after += kept.size
                    if (kept.size != hash.size) replaced[key] = JsonObject(kept)
                }
                else -> Unit
            }
        }
        return FriendResult(if (replaced.isEmpty()) friend else JsonObject(friend + replaced), before, after, readable)
    }

    /**
     * A slot that is not a readable object does not pass: it carries no servant, so the game shows nothing for it.
     * Grand slots hold their CEs in equipTarget1 and equipTarget3; equipTarget2 is the bond CE and stays out of the match.
     */
    private fun passes(slot: JsonElement, grand: Boolean, criteria: SupportListCriteria): Boolean {
        val row = slot as? JsonObject ?: return false
        if (row.long("svtId") !in criteria.servantIds) return false
        if (criteria.ceIds.isEmpty()) return true
        val ceKeys = if (grand) listOf("equipTarget1", "equipTarget3") else listOf("equipTarget1", "equipTarget2")
        return ceKeys.any { ((row[it] as? JsonObject)?.long("svtId")) in criteria.ceIds }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private val BODY_BOUND_HEADERS = setOf("trailer", "etag", "content-md5", "digest")
    private val SUPPORTED_ENCODINGS = setOf("", "identity", "gzip", "x-gzip", "deflate")

    /** KR Unity sends raw RFC 1951 for deflate (see [BodyDecoder]); the body is sent back the same way. */
    private fun deflateIsRaw(wire: ByteArray, encoding: String): Boolean {
        if (encoding != "deflate") return false
        if (wire.size < 2) return true
        val zlib = (wire[0].toInt() and 0x0F) == 8 &&
            (((wire[0].toInt() and 0xFF) shl 8) or (wire[1].toInt() and 0xFF)) % 31 == 0
        return !zlib
    }

    private fun encode(text: ByteArray, encoding: String, rawDeflate: Boolean): ByteArray? = when (encoding) {
        "", "identity" -> text
        "gzip", "x-gzip" -> ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text) } }.toByteArray()
        "deflate" -> {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, rawDeflate)
            try {
                ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, deflater).use { it.write(text) } }.toByteArray()
            } finally {
                deflater.end()
            }
        }
        else -> null
    }

    /**
     * The response head for a replaced body: same status line and headers, with Content-Length for the new body.
     * Chunked framing is gone because the new body is sent whole. Null when [raw] is not a head ending in a blank line.
     */
    fun rewriteHead(raw: ByteArray, bodyLength: Int): ByteArray? {
        val text = String(raw, Charsets.ISO_8859_1)
        if (!text.endsWith("\r\n\r\n")) return null
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        // These describe the original bytes or promise trailers, so a rewritten body would contradict them.
        if (lines.drop(1).any { it.substringBefore(':', "").trim().lowercase() in BODY_BOUND_HEADERS }) return null
        val kept = lines.filterIndexed { index, line ->
            index == 0 || run {
                val name = line.substringBefore(':', "").trim()
                !name.equals("Content-Length", true) && !name.equals("Transfer-Encoding", true)
            }
        }
        return (kept + "Content-Length: $bodyLength").joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
    }

}
