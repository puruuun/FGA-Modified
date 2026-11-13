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
        val coinCount: Int,
        val ceCount: Int,
        val ccCount: Int,
        val created: Boolean,
        val tookOverDefault: Boolean,
        val regionMismatch: Boolean,
        val userIndex: Int,
        val servantCount: Int = 0,
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
            output,
            patch.items?.size ?: 0,
            patch.mysticCodes?.size ?: 0,
            patch.classBoards?.size ?: 0,
            patch.svtCoins?.size ?: 0,
            patch.craftEssences?.size ?: 0,
            patch.commandCodes?.size ?: 0,
            created = match == ChaldeaUserMatcher.Match.Create,
            tookOverDefault = match is ChaldeaUserMatcher.Match.TakeOver,
            regionMismatch = regionMismatch,
            userIndex = index,
            servantCount = patch.servants?.size ?: 0,
        )
    }

    private val defaultSvtTopFields: Map<String, JsonElement> = mapOf(
        "priority" to JsonPrimitive(1),
        "bond" to JsonPrimitive(0),
        "equipCmdCodes" to JsonArray(List(5) { JsonNull }),
        "cmdCardStrengthen" to JsonNull,
        "grandSvt" to JsonPrimitive(false),
    )

    private val defaultSvtCurFields: Map<String, JsonElement> = mapOf(
        "favorite" to JsonPrimitive(false),
        "ascension" to JsonPrimitive(0),
        "skills" to JsonArray(List(3) { JsonPrimitive(1) }),
        "appendSkills" to JsonArray(List(5) { JsonPrimitive(0) }),
        "costumes" to JsonObject(emptyMap()),
        "grail" to JsonPrimitive(0),
        "fouHp5" to JsonPrimitive(0),
        "fouAtk5" to JsonPrimitive(0),
        "fouHp" to JsonPrimitive(0),
        "fouAtk" to JsonPrimitive(0),
        "fouHp3" to JsonPrimitive(20),
        "fouAtk3" to JsonPrimitive(20),
        "bondLimit" to JsonPrimitive(10),
        "npLv" to JsonPrimitive(1),
    )

    private fun svtPatchedTopFields(svt: ChaldeaPatch.SvtStatus): Map<String, JsonElement> = buildMap {
        svt.bond?.let { put("bond", JsonPrimitive(it)) }
        svt.equipCmdCodes?.let { list ->
            put("equipCmdCodes", JsonArray(list.map { id -> id?.let(::JsonPrimitive) ?: JsonNull }))
        }
        svt.cmdCardStrengthen?.let { list ->
            put("cmdCardStrengthen", JsonArray(list.map(::JsonPrimitive)))
        }
        svt.grandSvt?.let { put("grandSvt", JsonPrimitive(it)) }
    }

    private fun svtPatchedCurFields(svt: ChaldeaPatch.SvtStatus): Map<String, JsonElement> = buildMap {
        svt.favorite?.let { put("favorite", JsonPrimitive(it)) }
        svt.ascension?.let { put("ascension", JsonPrimitive(it)) }
        svt.skills?.let { list -> put("skills", JsonArray(list.map(::JsonPrimitive))) }
        svt.appendSkills?.let { list -> put("appendSkills", JsonArray(list.map(::JsonPrimitive))) }
        svt.costumes?.let { map ->
            put("costumes", buildJsonObject {
                for ((k, v) in map.toSortedMap()) put(k.toString(), v)
            })
        }
        svt.grail?.let { put("grail", JsonPrimitive(it)) }
        svt.fouHp5?.let { put("fouHp5", JsonPrimitive(it)) }
        svt.fouAtk5?.let { put("fouAtk5", JsonPrimitive(it)) }
        svt.fouHp?.let { put("fouHp", JsonPrimitive(it)) }
        svt.fouAtk?.let { put("fouAtk", JsonPrimitive(it)) }
        svt.fouHp3?.let { put("fouHp3", JsonPrimitive(it)) }
        svt.fouAtk3?.let { put("fouAtk3", JsonPrimitive(it)) }
        svt.bondLimit?.let { put("bondLimit", JsonPrimitive(it)) }
        svt.npLv?.let { put("npLv", JsonPrimitive(it)) }
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
        fun mergePerField(key: String, updates: Map<String, (existing: JsonObject?) -> Map<String, JsonElement>>?): Boolean {
            if (updates == null) return true
            val existing = when (val e = out[key]) {
                null, is JsonNull -> emptyMap()
                is JsonObject -> e
                else -> return false
            }
            val merged = LinkedHashMap(existing)
            for ((entryKey, fieldsFor) in updates) {
                val entryObj = when (val entry = existing[entryKey]) {
                    null, is JsonNull -> null
                    is JsonObject -> entry
                    else -> return false
                }
                val entryOut = if (entryObj != null) LinkedHashMap(entryObj) else LinkedHashMap()
                entryOut.putAll(fieldsFor(entryObj))
                merged[entryKey] = JsonObject(entryOut)
            }
            out[key] = JsonObject(merged)
            return true
        }
        fun mergeServants(svts: Map<Long, ChaldeaPatch.SvtStatus>?): Boolean {
            if (svts == null) return true
            val existing = when (val e = out["servants"]) {
                null, is JsonNull -> emptyMap()
                is JsonObject -> e
                else -> return false
            }
            val merged = LinkedHashMap(existing)
            for ((colNo, svt) in svts) {
                val entryKey = colNo.toString()
                val oldEntry = when (val entry = existing[entryKey]) {
                    null, is JsonNull -> null
                    is JsonObject -> entry
                    else -> return false
                }
                val oldCur = if (oldEntry == null) {
                    null
                } else {
                    when (val cur = oldEntry["cur"]) {
                        null, is JsonNull -> null
                        is JsonObject -> cur
                        else -> return false
                    }
                }
                if (oldCur != null) {
                    val oldCostumes = oldCur["costumes"]
                    if (oldCostumes != null && oldCostumes !is JsonNull && oldCostumes !is JsonObject) return false
                }
                val curOut = if (oldCur != null) LinkedHashMap(oldCur) else LinkedHashMap(defaultSvtCurFields)
                curOut.putAll(svtPatchedCurFields(svt))
                val entryOut = if (oldEntry != null) LinkedHashMap(oldEntry) else LinkedHashMap(defaultSvtTopFields)
                entryOut["cur"] = JsonObject(curOut)
                entryOut.putAll(svtPatchedTopFields(svt))
                merged[entryKey] = JsonObject(entryOut)
            }
            out["servants"] = JsonObject(merged)
            return true
        }
        if (!merge("items", itemsOf(patch))) return null
        if (!merge("mysticCodes", mysticOf(patch))) return null
        if (!merge("classBoards", boardsOf(patch))) return null
        if (!mergePerField("craftEssences", ceUpdatesOf(patch))) return null
        if (!mergePerField("cmdCodes", ccUpdatesOf(patch))) return null
        if (!mergeServants(patch.servants)) return null
        return JsonObject(out)
    }

    private fun itemsOf(p: ChaldeaPatch): Map<String, JsonElement>? {
        if (p.items == null && p.svtCoins == null) return null
        val out = LinkedHashMap<String, JsonElement>()
        p.items?.forEach { (id, num) -> out[id.toString()] = JsonPrimitive(num) }
        p.svtCoins?.forEach { (id, num) -> out[id.toString()] = JsonPrimitive(num) }
        return out
    }

    private fun mysticOf(p: ChaldeaPatch) = p.mysticCodes?.entries?.associate { it.key.toString() to JsonPrimitive(it.value) }
    private fun boardsOf(p: ChaldeaPatch) = p.classBoards?.entries?.associate { it.key.toString() to ChaldeaPatch.boardJson(it.value) }

    private fun ceUpdatesOf(p: ChaldeaPatch): Map<String, (JsonObject?) -> Map<String, JsonElement>>? =
        p.craftEssences?.entries?.associate { (colNo, ce) ->
            colNo.toString() to { existing: JsonObject? ->
                buildMap {
                    put("status", JsonPrimitive(ce.status))
                    if (existing == null) {
                        put("lv", JsonPrimitive(ce.lv ?: 1L))
                        put("limitCount", JsonPrimitive(ce.limitCount ?: 0L))
                    } else {
                        ce.lv?.let { put("lv", JsonPrimitive(it)) }
                        ce.limitCount?.let { put("limitCount", JsonPrimitive(it)) }
                    }
                }
            }
        }

    private fun ccUpdatesOf(p: ChaldeaPatch): Map<String, (JsonObject?) -> Map<String, JsonElement>>? =
        p.commandCodes?.entries?.associate { (colNo, cc) ->
            colNo.toString() to { _: JsonObject? ->
                mapOf(
                    "status" to JsonPrimitive(cc.status),
                    "count" to JsonPrimitive(cc.count),
                )
            }
        }

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
        val perFieldMaps = mapOf("craftEssences" to ceUpdatesOf(patch), "cmdCodes" to ccUpdatesOf(patch))
            .filterValues { it != null }
        val svtPatches = patch.servants?.entries?.associate { it.key.toString() to it.value }

        for (key in before.keys) if (key !in target.keys) return false
        for (key in setFields.keys + maps.keys + perFieldMaps.keys) if (key !in target.keys) return false
        if (svtPatches != null && "servants" !in target.keys) return false
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
                key in perFieldMaps -> {
                    val oldMap = (before[key] as? JsonObject) ?: JsonObject(emptyMap())
                    val updates = perFieldMaps.getValue(key)!!
                    val nowMap = value as? JsonObject ?: return false
                    if (nowMap.keys != oldMap.keys + updates.keys) return false
                    for ((entryKey, entryVal) in nowMap) {
                        val fieldsFor = updates[entryKey]
                        if (fieldsFor == null) {
                            if (entryVal != oldMap[entryKey]) return false
                        } else {
                            val oldEntry = oldMap[entryKey] as? JsonObject
                            val nowEntry = entryVal as? JsonObject ?: return false
                            val expectedFields = fieldsFor(oldEntry)
                            val base = oldEntry ?: JsonObject(emptyMap())
                            if (nowEntry.keys != base.keys + expectedFields.keys) return false
                            for ((fk, fv) in nowEntry) if (fv != (expectedFields[fk] ?: base[fk])) return false
                        }
                    }
                }
                key == "servants" && svtPatches != null -> {
                    val oldMap = (before["servants"] as? JsonObject) ?: JsonObject(emptyMap())
                    val nowMap = value as? JsonObject ?: return false
                    if (nowMap.keys != oldMap.keys + svtPatches.keys) return false
                    for ((entryKey, entryVal) in nowMap) {
                        val svtPatch = svtPatches[entryKey]
                        if (svtPatch == null) {
                            if (entryVal != oldMap[entryKey]) return false
                        } else {
                            val oldRaw = oldMap[entryKey]
                            val isNewEntry = oldRaw == null || oldRaw is JsonNull
                            val oldEntry = if (isNewEntry) null else oldRaw as? JsonObject ?: return false
                            val nowEntry = entryVal as? JsonObject ?: return false
                            val patchedTop = svtPatchedTopFields(svtPatch)
                            val patchedCur = svtPatchedCurFields(svtPatch)
                            val baseTop = oldEntry ?: defaultSvtTopFields
                            if (nowEntry.keys != baseTop.keys + "cur" + patchedTop.keys) return false
                            for ((fk, fv) in nowEntry) {
                                when {
                                    fk == "cur" -> {
                                        val nowCur = fv as? JsonObject ?: return false
                                        val oldCurRaw = oldEntry?.get("cur")
                                        val useDefaultCur = oldCurRaw == null || oldCurRaw is JsonNull
                                        val oldCur = if (useDefaultCur) null else oldCurRaw as? JsonObject ?: return false
                                        val baseCur = oldCur ?: defaultSvtCurFields
                                        if (nowCur.keys != baseCur.keys + patchedCur.keys) return false
                                        for ((ck, cv) in nowCur) {
                                            if (cv != (patchedCur[ck] ?: baseCur[ck])) return false
                                        }
                                    }
                                    fk in patchedTop -> if (fv != patchedTop[fk]) return false
                                    else -> if (fv != baseTop[fk]) return false
                                }
                            }
                        }
                    }
                }
                key == "id" && created -> {}
                else -> if (before[key] != value) return false
            }
        }
        return true
    }
}
