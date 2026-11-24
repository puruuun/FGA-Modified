package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The support conditions as ids, ready for the proxy thread (#685). Each field is one condition; its default means
 * "not set". A filled slot matches when every condition that is set holds. Built outside the proxy from a battle config
 * or the manual filter, never per response.
 */
data class SupportListCriteria(
    /** Empty: any servant. */
    val servantIds: Set<Long> = emptySet(),
    /** Empty: any CE, or none. */
    val ceIds: Set<Long> = emptySet(),
    /** Only with [ceIds]: the matching CE has limitCount 4. */
    val ceMlb: Boolean = false,
    val maxAscended: Boolean = false,
    /** Per svtId, the accepted dispLimitCount values; a servant without an entry shows in any look. */
    val displayedAscensions: Map<Long, Set<Int>> = emptyMap(),
    /** Skills 1..3 at level 10. */
    val skillsMax: List<Boolean> = List(3) { false },
    /** Appends 1..5 at level 10, numbered as [FriendRoster.appendSlot] numbers them. */
    val appendsMax: List<Boolean> = List(5) { false },
    /** 0: no NP level condition. */
    val minimumNpLevel: Int = 0,
    val classScore62: Boolean = false,
    /** svtId to the game's class id, for [classScore62]; a servant missing here never matches that condition. */
    val servantClassIds: Map<Long, Int> = emptyMap(),
    /** Followers that are not friends (type 2) are left out. */
    val friendsOnly: Boolean = false,
) {
    /** False when nothing is set: the proxy then relays the list untouched. */
    val hasConditions: Boolean
        get() = servantIds.isNotEmpty() || ceIds.isNotEmpty() || maxAscended || displayedAscensions.isNotEmpty() ||
            skillsMax.any { it } || appendsMax.any { it } || minimumNpLevel > 0 || classScore62 || friendsOnly
}

/**
 * The displayed ascension (#685) as the user picks it and as the slot's `dispLimitCount` carries it. The only place
 * that knows the mapping: 0, 1, 2, 3 are stage 1, 2, 3 and final, and 11 and above are costume (value - 10). This is
 * the planner's reading of real KR data and still awaits a device check; change it here if the check disagrees.
 */
object DisplayedAscension {
    /** Stage 1, 2, 3 and final. */
    val STAGES = listOf("s1", "s2", "s3", "s4")
    const val MAX_COSTUMES = 6
    val COSTUMES = (1..MAX_COSTUMES).map { "c$it" }

    private val TOKEN = Regex("([sc])([1-9][0-9]?)")

    /** The dispLimitCount for a stored token, or null for a token this version does not know. */
    fun dispLimitCount(token: String): Int? {
        val (kind, number) = TOKEN.matchEntire(token)?.destructured ?: return null
        val n = number.toInt()
        return when (kind) {
            "s" -> if (n in 1..4) n - 1 else null
            else -> if (n in 1..MAX_COSTUMES) 10 + n else null
        }
    }

    /** The stored form of one choice for one servant entry. A servant entry may hold any character, so it goes last. */
    fun entry(token: String, servant: String) = "$token|$servant"

    /** The token and the servant entry of a stored choice, or null when it is not one. */
    fun parse(entry: String): Pair<String, String>? {
        val token = entry.substringBefore('|', "")
        if (dispLimitCount(token) == null) return null
        return token to entry.substring(token.length + 1)
    }
}

/**
 * Counts only, for the debug log and the settings line: no ids and no names. Slots are the filled ones
 * (svtId > 0) of the followers that are filtered (type 1 and 2).
 */
data class SupportListCounts(
    val followersBefore: Int,
    val followersAfter: Int,
    val slotsBefore: Int = 0,
    val slotsAfter: Int = 0,
)

/** [wire] is the body as it goes to the game: filtered and encoded like the original. */
class SupportListFiltered(val wire: ByteArray, val counts: SupportListCounts)

/**
 * In a follower/list response (#636, #685), leaves out the followers that have no matching support and,
 * in the followers that stay, replaces every filled slot that does not match with the server's own empty-slot
 * form (see [blankSlot]). A slot that matches, a slot that is already empty and every other key stay as received.
 * Every call returns null ("unchanged") on anything unexpected, so the caller forwards the original bytes;
 * nothing is thrown.
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
    private val FILTERED_TYPES = setOf(1L, 2L)
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
        if (!criteria.hasConditions || wire.isEmpty() || wire.size > MAX_WIRE_BYTES) return null
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

    /** The filtered tree and its counts, or null when nothing was changed or the shape is not the expected one. */
    internal fun filter(root: JsonObject, criteria: SupportListCriteria): Pair<JsonObject, SupportListCounts>? {
        if (!criteria.hasConditions) return null
        val cache = root["cache"] as? JsonObject ?: return null
        val updated = cache["updated"] as? JsonObject ?: return null
        val followers = updated["userFollower"] as? JsonArray ?: return null
        var before = 0
        var after = 0
        var slotsBefore = 0
        var slotsAfter = 0
        var changed = false
        // A follower or slot of a shape this code has not seen must not be counted as empty, removed or blanked.
        var unknownShape = false
        val newFollowers = followers.map { follower ->
            val entry = follower as? JsonObject ?: return@map follower
            val infos = entry["followerInfo"] as? JsonArray ?: return@map follower
            var entryChanged = false
            val kept = ArrayList<JsonElement>(infos.size)
            for (info in infos) {
                val candidate = info as? JsonObject
                if (candidate == null) {
                    kept += info
                    continue
                }
                before++
                // Story quests may send other kinds (NPC, fixed support) that the game needs; only friends and non-friends are filtered.
                if (!isFilterable(candidate)) {
                    after++
                    kept += candidate
                    continue
                }
                val result = reduce(candidate, criteria, excluded = criteria.friendsOnly && !isFriend(candidate))
                if (result == null) {
                    unknownShape = true
                    kept += candidate
                    continue
                }
                slotsBefore += result.filledBefore
                if (!result.anyMatch) {
                    entryChanged = true
                    changed = true
                    continue
                }
                after++
                slotsAfter += result.filledAfter
                if (result.follower !== candidate) {
                    entryChanged = true
                    changed = true
                }
                kept += result.follower
            }
            if (entryChanged) JsonObject(entry + ("followerInfo" to JsonArray(kept))) else entry
        }
        if (unknownShape || !changed) return null
        val newUpdated = JsonObject(updated + ("userFollower" to JsonArray(newFollowers)))
        val newCache = JsonObject(cache + ("updated" to newUpdated))
        return JsonObject(root + ("cache" to newCache)) to SupportListCounts(before, after, slotsBefore, slotsAfter)
    }

    /** type 1 (friend) and 2 (not a friend) as a JSON integer; a string "1" or a missing type is not. */
    private fun isFilterable(follower: JsonObject): Boolean {
        val type = follower["type"] as? JsonPrimitive ?: return false
        return !type.isString && type.longOrNull in FILTERED_TYPES
    }

    private fun isFriend(follower: JsonObject) = follower.long("type") == 1L

    private class Reduced(val follower: JsonObject, val anyMatch: Boolean, val filledBefore: Int, val filledAfter: Int)

    /**
     * The follower with its non-matching filled slots blanked, or null when no slot hash is readable or a slot
     * has an unexpected shape. When nothing in it changes, [Reduced.follower] is the same object that came in.
     */
    private fun reduce(follower: JsonObject, criteria: SupportListCriteria, excluded: Boolean): Reduced? {
        var readable = false
        var anyMatch = false
        var filledBefore = 0
        var filledAfter = 0
        var failed = false
        var changed = false
        val entries = LinkedHashMap<String, JsonElement>(follower)
        // Read once per follower, and only when a condition needs it.
        val boards = if (criteria.classScore62 && !excluded) classBoards(follower) else Boards.Missing
        if (boards == Boards.Odd) return null

        fun reduceSlot(slot: JsonElement, grand: Boolean): JsonElement {
            val row = slot as? JsonObject
            val svtId = row?.long("svtId")
            if (row == null || svtId == null) {
                failed = true
                return slot
            }
            if (svtId <= 0L) return slot
            filledBefore++
            val matches = if (excluded) false else passes(row, grand, criteria, boards)
            if (matches == null) {
                failed = true
                return slot
            }
            if (matches) {
                filledAfter++
                anyMatch = true
                return slot
            }
            changed = true
            return blankSlot(row)
        }

        for (key in SLOT_HASHES) {
            val grand = key == GRAND_HASH
            entries[key] = when (val hash = follower[key]) {
                is JsonArray -> JsonArray(hash.map { reduceSlot(it, grand) })
                // A hash keyed by deck or seat.
                is JsonObject -> JsonObject(hash.mapValues { reduceSlot(it.value, grand) })
                else -> continue
            }
            readable = true
        }
        if (!readable || failed) return null
        // The rebuilt copy is only needed when a slot was blanked in a follower that stays.
        return Reduced(if (changed && anyMatch) JsonObject(entries) else follower, anyMatch, filledBefore, filledAfter)
    }

    /**
     * A filled slot matches when every condition that is set holds. Null when a field a set condition reads has a shape
     * this code has not seen, so the caller passes the response through. [boards] is the follower's class boards;
     * without them a slot that needs the class score does not match.
     */
    private fun passes(row: JsonObject, grand: Boolean, criteria: SupportListCriteria, boards: Boards): Boolean? {
        val svtId = row.long("svtId")
        if (svtId == null || svtId <= 0L) return false
        if (criteria.servantIds.isNotEmpty() && svtId !in criteria.servantIds) return false
        if (criteria.ceIds.isNotEmpty()) {
            // Grand slots hold their CEs in equipTarget1 and equipTarget3; equipTarget2 is the bond CE and stays out of the match.
            val ceKeys = if (grand) listOf("equipTarget1", "equipTarget3") else listOf("equipTarget1", "equipTarget2")
            var matched = false
            for (key in ceKeys) {
                val ce = ceId(row, key)
                if (ce == UNKNOWN_CE) return null
                if (ce == null || ce !in criteria.ceIds) continue
                if (!criteria.ceMlb) {
                    matched = true
                    continue
                }
                val limit = (row[key] as JsonObject).long("limitCount") ?: return null
                if (limit == 4L) matched = true
            }
            if (!matched) return false
        }
        if (criteria.maxAscended && (row.long("limitCount") ?: return null) != 4L) return false
        criteria.displayedAscensions[svtId]?.let { accepted ->
            val shown = row.long("dispLimitCount") ?: return null
            if (shown !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || shown.toInt() !in accepted) return false
        }
        criteria.skillsMax.forEachIndexed { index, required ->
            if (required && (row.long("skillLv${index + 1}") ?: return null) != 10L) return false
        }
        if (criteria.appendsMax.any { it }) {
            val levels = appendLevels(row) ?: return null
            criteria.appendsMax.forEachIndexed { index, required ->
                if (required && levels[index + 1] != 10L) return false
            }
        }
        if (criteria.minimumNpLevel > 0 && (row.long("treasureDeviceLv") ?: return null) < criteria.minimumNpLevel) return false
        if (criteria.classScore62) {
            val classId = criteria.servantClassIds[svtId] ?: return false
            if (boards !is Boards.Read || boards.full[classId] != true) return false
        }
        return true
    }

    /** The number of squares of a full class board, which the support screen shows as "+62". */
    private const val CLASS_SCORE_SQUARES = 62

    /** Append slot (1..5) to level. Null when the array or one of its entries has a shape this code has not seen. */
    private fun appendLevels(row: JsonObject): Map<Int, Long>? {
        val skills = row["appendPassiveSkill"] as? JsonArray ?: return null
        val levels = HashMap<Int, Long>()
        for (skill in skills) {
            val entry = skill as? JsonObject ?: return null
            val id = entry.long("skillId") ?: return null
            val level = when (val lv = entry["skillLv"]) {
                // An append that is not unlocked has no level yet.
                null, is JsonNull -> 0L
                else -> (lv as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            }
            levels[FriendRoster.appendSlot(id)] = level
        }
        return levels
    }

    /** A follower's class boards (`userClassBoardInfo`) as the class score condition reads them. */
    private sealed interface Boards {
        /** No board list (the key is absent or null): a slot that needs the class score does not match. */
        data object Missing : Boards

        /** A board list of a shape this code has not seen: the whole response passes unchanged. */
        data object Odd : Boards

        /** Class id to "a board that covers it has all [CLASS_SCORE_SQUARES] squares". */
        class Read(val full: Map<Int, Boolean>) : Boards
    }

    /**
     * The class boards the support screen's "+62" is about: `classBoardBaseId` 1 to 9. JP also sends boards with large
     * base ids and up to 16 squares (#695); those never count, whatever classes they list.
     */
    private val CLASS_BOARD_BASE_IDS = 1L..9L

    /** One entry per board, each with its squares in `squareIds` and the classes it covers in `releasedClassIds`. */
    private fun classBoards(follower: JsonObject): Boards {
        val value = follower["userClassBoardInfo"]
        if (value == null || value is JsonNull) return Boards.Missing
        val boards = value as? JsonArray ?: return Boards.Odd
        val full = HashMap<Int, Boolean>()
        for (board in boards) {
            val entry = board as? JsonObject ?: return Boards.Odd
            val base = integer(entry["classBoardBaseId"] ?: return Boards.Odd) ?: return Boards.Odd
            if (base !in CLASS_BOARD_BASE_IDS) continue
            val squares = entry["squareIds"] as? JsonArray ?: return Boards.Odd
            val classes = entry["releasedClassIds"] as? JsonArray ?: return Boards.Odd
            if (squares.any { integer(it) == null }) return Boards.Odd
            for (element in classes) {
                val id = integer(element) ?: return Boards.Odd
                if (id !in 0L..Int.MAX_VALUE.toLong()) return Boards.Odd
                full[id.toInt()] = full[id.toInt()] == true || squares.size == CLASS_SCORE_SQUARES
            }
        }
        return Boards.Read(full)
    }

    /** An integer as a JSON number; the string "100" is not one. */
    private fun integer(element: JsonElement): Long? =
        (element as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.longOrNull

    private const val UNKNOWN_CE = Long.MIN_VALUE

    /**
     * The CE's svtId, or null when there is no CE (the key or its svtId is missing, or the key is null). A present value
     * that is not a plain integer, or an equipTarget that is not an object, is [UNKNOWN_CE]: it must not count as "no match".
     */
    private fun ceId(row: JsonObject, key: String): Long? {
        val target = row[key] ?: return null
        if (target is JsonNull) return null
        val obj = target as? JsonObject ?: return UNKNOWN_CE
        val value = obj["svtId"] ?: return null
        val number = value as? JsonPrimitive ?: return UNKNOWN_CE
        if (number.isString || number is JsonNull) return UNKNOWN_CE
        return number.longOrNull ?: UNKNOWN_CE
    }

    private val SLOT_OWN_KEYS = setOf("userId", "supportDeckId", "classId", "updatedAt")
    private val LIMIT_COUNT_KEYS = setOf("dispLimitCount", "imageLimitCount", "commandCardLimitCount", "iconLimitCount", "portraitLimitCount")

    /**
     * The server's own form of a slot with no servant, built from the slot it replaces: same keys in the same order;
     * the slot's own userId, supportDeckId, classId and updatedAt stay, the five *LimitCount are 1, commandCardParam is
     * five zeros, afterTransformSvtInfo is null, script is {} and every other value is blanked by type, nested objects included. The game already shows a follower
     * whose slot has this form as "no support".
     */
    internal fun blankSlot(slot: JsonObject): JsonObject = JsonObject(
        slot.mapValues { (key, value) ->
            when {
                key in SLOT_OWN_KEYS -> value
                key in LIMIT_COUNT_KEYS -> JsonPrimitive(1)
                key == "commandCardParam" -> JsonArray(List(5) { JsonPrimitive(0) })
                // The server's empty slot has exactly these two values, whatever a filled slot holds here.
                key == "afterTransformSvtInfo" -> JsonNull
                key == "script" -> JsonObject(emptyMap())
                else -> blankValue(value)
            }
        },
    )

    private fun blankValue(value: JsonElement): JsonElement = when (value) {
        is JsonNull -> JsonNull
        is JsonPrimitive -> when {
            value.isString -> JsonPrimitive("")
            value.booleanOrNull != null -> JsonPrimitive(false)
            else -> JsonPrimitive(0)
        }
        is JsonArray -> JsonArray(emptyList())
        is JsonObject -> JsonObject(value.mapValues { blankValue(it.value) })
    }

    /** An integer as a JSON number; the string "100" is not one. */
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
