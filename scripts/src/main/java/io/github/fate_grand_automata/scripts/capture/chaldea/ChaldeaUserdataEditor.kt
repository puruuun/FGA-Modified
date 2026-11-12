package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

enum class ChaldeaRefusal {
    /** Not valid JSON (or not valid UTF-8). */
    InvalidJson,
    NotObject,

    /** `version` is not the integer 4. */
    UnknownVersion,
    MissingUsers,

    /** Friend code missing or blank. */
    AccountNotIdentified,

    /** The account has no Chaldea user and may not create one (not the server's current account). */
    NoMatchingUser,

    /** A field the patch writes has an unexpected type in the file. */
    UnexpectedShape,

    /** The edited text failed the re-parse check; nothing may be written. */
    VerifyFailed,
    TooLarge,

    /** `userdata.json` and `.bak` are both unusable. */
    Unreadable,
}

sealed interface ChaldeaEditResult {
    class Success(
        /** The new file text, to be written to both `userdata.json` and `.bak`. */
        val output: String,
        val itemCount: Int,
        val mysticCodeCount: Int,
        val boardCount: Int,
        val created: Boolean,
        val tookOverDefault: Boolean,
        val regionMismatch: Boolean,
        val userIndex: Int,
    ) : ChaldeaEditResult

    data class Refused(val reason: ChaldeaRefusal) : ChaldeaEditResult
}

/** Which file the edit started from. */
enum class ChaldeaBaseSource { UserdataJson, Bak }

sealed interface ChaldeaBase {
    class Selected(val source: ChaldeaBaseSource, val text: String) : ChaldeaBase
    data class Refused(val reason: ChaldeaRefusal) : ChaldeaBase
}

/**
 * Edits Chaldea's `userdata.json` as a JSON tree: only the patched keys change, everything else keeps its order and
 * its number literals. Never deletes a key.
 */
object ChaldeaUserdataEditor {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024

    sealed interface Parsed {
        class Ok(val root: JsonObject) : Parsed
        data class Bad(val reason: ChaldeaRefusal) : Parsed
    }

    /** Syntax and shape check: an object with integer `version` 4 and a `users` array. */
    fun parse(text: String): Parsed {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Parsed.Bad(ChaldeaRefusal.InvalidJson)
        }
        val root = element as? JsonObject ?: return Parsed.Bad(ChaldeaRefusal.NotObject)
        val version = root["version"] as? JsonPrimitive
        if (version == null || version.isString || version.content != "4") return Parsed.Bad(ChaldeaRefusal.UnknownVersion)
        if (root["users"] !is JsonArray) return Parsed.Bad(ChaldeaRefusal.MissingUsers)
        return Parsed.Ok(root)
    }

    /**
     * Picks the base like Chaldea's loader: `userdata.json`, or `.bak` when the main file is missing, empty or not
     * valid JSON. A main file that is valid JSON with a wrong shape is refused, not replaced by `.bak`.
     */
    fun selectBase(json: ByteArray?, bak: ByteArray?): ChaldeaBase {
        if ((json?.size ?: 0) > MAX_FILE_BYTES || (bak?.size ?: 0) > MAX_FILE_BYTES) return ChaldeaBase.Refused(ChaldeaRefusal.TooLarge)
        val jsonText = json?.let(::decodeUtf8)
        if (jsonText != null) {
            when (val p = parse(jsonText)) {
                is Parsed.Ok -> return ChaldeaBase.Selected(ChaldeaBaseSource.UserdataJson, jsonText)
                is Parsed.Bad -> if (p.reason != ChaldeaRefusal.InvalidJson) return ChaldeaBase.Refused(p.reason)
            }
        }
        val bakText = bak?.let(::decodeUtf8)
        if (bakText != null && parse(bakText) is Parsed.Ok) return ChaldeaBase.Selected(ChaldeaBaseSource.Bak, bakText)
        return ChaldeaBase.Refused(ChaldeaRefusal.Unreadable)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * @param allowCreate true only for the current account of its server: it allows a new user or the default takeover.
     */
    fun edit(
        text: String,
        patch: ChaldeaPatch,
        allowCreate: Boolean,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): ChaldeaEditResult {
        fun refuse(r: ChaldeaRefusal) = ChaldeaEditResult.Refused(r)
        val friendCode = patch.friendCode?.trim()
        if (friendCode.isNullOrEmpty()) return refuse(ChaldeaRefusal.AccountNotIdentified)
        val root = when (val p = parse(text)) {
            is Parsed.Ok -> p.root
            is Parsed.Bad -> return refuse(p.reason)
        }
        val users = root["users"] as JsonArray
        val match = ChaldeaUserMatcher.match(users, patch)
        if (match !is ChaldeaUserMatcher.Match.Existing && !allowCreate) return refuse(ChaldeaRefusal.NoMatchingUser)

        val newUsers: List<JsonElement>
        val index: Int
        var regionMismatch = false
        when (match) {
            is ChaldeaUserMatcher.Match.Existing -> {
                index = match.index
                regionMismatch = match.regionMismatch
                val merged = applyPatch(users[index] as JsonObject, patch, takeOver = false) ?: return refuse(ChaldeaRefusal.UnexpectedShape)
                newUsers = users.toMutableList().also { it[index] = merged }
            }
            is ChaldeaUserMatcher.Match.TakeOver -> {
                index = match.index
                val merged = applyPatch(users[index] as JsonObject, patch, takeOver = true) ?: return refuse(ChaldeaRefusal.UnexpectedShape)
                newUsers = users.toMutableList().also { it[index] = merged }
            }
            ChaldeaUserMatcher.Match.Create -> {
                index = users.size
                val base = buildJsonObject {
                    put("id", newId())
                    put("name", patch.userName)
                    put("region", patch.server.region)
                    patch.isGirl?.let { put("isGirl", it) }
                    put("lastImportId", friendCode)
                }
                val merged = applyPatch(base, patch, takeOver = false) ?: return refuse(ChaldeaRefusal.UnexpectedShape)
                newUsers = users + merged
            }
        }
        val newRoot = JsonObject(root.mapValues { (k, v) -> if (k == "users") JsonArray(newUsers) else v })
        val output = newRoot.toString()
        if (!verify(root, output, patch, match, index)) return refuse(ChaldeaRefusal.VerifyFailed)
        return ChaldeaEditResult.Success(
            output, patch.items?.size ?: 0, patch.mysticCodes?.size ?: 0, patch.classBoards?.size ?: 0,
            created = match == ChaldeaUserMatcher.Match.Create,
            tookOverDefault = match is ChaldeaUserMatcher.Match.TakeOver,
            regionMismatch = regionMismatch, userIndex = index,
        )
    }

    /** Returns null when a field the patch writes has an unexpected type. */
    private fun applyPatch(user: JsonObject, patch: ChaldeaPatch, takeOver: Boolean): JsonObject? {
        val out = LinkedHashMap(user)
        if (takeOver) {
            out["lastImportId"] = JsonPrimitive(patch.friendCode!!.trim())
            out["name"] = JsonPrimitive(patch.userName)
            out["region"] = JsonPrimitive(patch.server.region)
        }
        patch.isGirl?.let { out["isGirl"] = JsonPrimitive(it) }
        fun merge(key: String, add: Map<String, JsonElement>?): Boolean {
            if (add == null) return true
            val existing = when (val e = out[key]) {
                null, is JsonNull -> emptyMap()
                is JsonObject -> e
                else -> return false
            }
            out[key] = JsonObject(LinkedHashMap(existing).also { it.putAll(add) })
            return true
        }
        if (!merge("items", itemsOf(patch))) return null
        if (!merge("mysticCodes", mysticOf(patch))) return null
        if (!merge("classBoards", boardsOf(patch))) return null
        return JsonObject(out)
    }

    private fun itemsOf(p: ChaldeaPatch) = p.items?.entries?.associate { it.key.toString() to JsonPrimitive(it.value) }
    private fun mysticOf(p: ChaldeaPatch) = p.mysticCodes?.entries?.associate { it.key.toString() to JsonPrimitive(it.value) }
    private fun boardsOf(p: ChaldeaPatch) = p.classBoards?.entries?.associate { it.key.toString() to ChaldeaPatch.boardJson(it.value) }

    /**
     * Re-parses [output] and checks it against [original]: same top level (so same `version` and `curUserKey`), user
     * count unchanged or +1, every other user equal, the target user equal except the patched values.
     */
    fun verify(
        original: JsonObject,
        output: String,
        patch: ChaldeaPatch,
        match: ChaldeaUserMatcher.Match,
        targetIndex: Int,
    ): Boolean = try {
        verifyOrThrow(original, output, patch, match, targetIndex)
    } catch (e: Exception) {
        false
    }

    private fun verifyOrThrow(
        original: JsonObject,
        output: String,
        patch: ChaldeaPatch,
        match: ChaldeaUserMatcher.Match,
        targetIndex: Int,
    ): Boolean {
        val out = Json.parseToJsonElement(output) as? JsonObject ?: return false
        val oldUsers = original["users"] as? JsonArray ?: return false
        val newUsers = out["users"] as? JsonArray ?: return false
        val created = match == ChaldeaUserMatcher.Match.Create
        if (original.keys != out.keys) return false
        if (original.keys.any { it != "users" && original[it] != out[it] }) return false
        if (newUsers.size != oldUsers.size + if (created) 1 else 0) return false
        for (i in oldUsers.indices) if (i != targetIndex && oldUsers[i] != newUsers[i]) return false
        if (created && targetIndex != oldUsers.size) return false
        if (targetIndex !in newUsers.indices) return false
        val target = newUsers[targetIndex] as? JsonObject ?: return false
        val before = if (created) JsonObject(emptyMap()) else oldUsers[targetIndex] as? JsonObject ?: return false

        val setFields = HashMap<String, JsonElement>()
        patch.isGirl?.let { setFields["isGirl"] = JsonPrimitive(it) }
        if (created || match is ChaldeaUserMatcher.Match.TakeOver) {
            setFields["lastImportId"] = JsonPrimitive(patch.friendCode!!.trim())
            setFields["name"] = JsonPrimitive(patch.userName)
            setFields["region"] = JsonPrimitive(patch.server.region)
        }
        if (created && (target["id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) return false
        val maps = mapOf("items" to itemsOf(patch), "mysticCodes" to mysticOf(patch), "classBoards" to boardsOf(patch))
            .filterValues { it != null }

        for (key in before.keys) if (key !in target.keys) return false
        for (key in setFields.keys + maps.keys) if (key !in target.keys) return false
        for ((key, value) in target) {
            when {
                key in setFields -> if (value != setFields[key]) return false
                key in maps -> {
                    val old = (before[key] as? JsonObject) ?: JsonObject(emptyMap())
                    val add = maps.getValue(key)!!
                    val now = value as? JsonObject ?: return false
                    if (now.keys != old.keys + add.keys) return false
                    for ((k, v) in now) if (v != (add[k] ?: old[k])) return false
                }
                key == "id" && created -> {}
                else -> if (before[key] != value) return false
            }
        }
        return true
    }
}
