package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Costume and transform mappings extracted from Chaldea's own downloaded game data (`files/game/`).
 * Cached in FGA's sync store so `ChaldeaPatch` can translate game `costumeIds` into Chaldea `battleCharaId` keys.
 */
data class ChaldeaGameData(
    val version: String,
    /** svtId -> (game costumeId -> Chaldea battleCharaId) */
    val costumes: Map<Long, Map<Long, Long>>,
    /** svtId -> saveTransform partner svtId */
    val transforms: Map<Long, Long>,
) {
    fun sameContentAs(other: ChaldeaGameData): Boolean =
        costumes == other.costumes && transforms == other.transforms

    fun toJson(): String = buildJsonObject {
        put("version", version)
        put("costumes", buildJsonObject {
            for ((svtId, map) in costumes.toSortedMap()) {
                put(svtId.toString(), buildJsonObject {
                    for ((costumeId, battleCharaId) in map.toSortedMap()) {
                        put(costumeId.toString(), battleCharaId)
                    }
                })
            }
        })
        put("transforms", buildJsonObject {
            for ((svtId, partnerId) in transforms.toSortedMap()) {
                put(svtId.toString(), partnerId)
            }
        })
    }.toString()

    companion object {
        const val MAX_SERVANT_FILES = 50
        const val MAX_RESULT_CHARS = 128_000
        const val VERSION_FILE = "version.json"

        fun fromJson(text: String): ChaldeaGameData? = try {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
            val version = (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
            val costumesObj = o["costumes"] as? JsonObject ?: return null
            val costumes = LinkedHashMap<Long, Map<Long, Long>>(costumesObj.size)
            for ((svtKey, valEl) in costumesObj) {
                val svtId = svtKey.toLongOrNull() ?: return null
                val mapObj = valEl as? JsonObject ?: return null
                val inner = LinkedHashMap<Long, Long>(mapObj.size)
                for ((cKey, cVal) in mapObj) {
                    val cid = cKey.toLongOrNull() ?: return null
                    val bcId = (cVal as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
                    inner[cid] = bcId
                }
                costumes[svtId] = inner
            }
            val transformsObj = o["transforms"] as? JsonObject ?: return null
            val transforms = LinkedHashMap<Long, Long>(transformsObj.size)
            for ((svtKey, pVal) in transformsObj) {
                val svtId = svtKey.toLongOrNull() ?: return null
                val partnerId = (pVal as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
                transforms[svtId] = partnerId
            }
            ChaldeaGameData(version, costumes, transforms)
        } catch (_: Exception) {
            null
        }
    }
}

sealed interface ChaldeaGameDataResult {
    data class Unchanged(val version: String) : ChaldeaGameDataResult
    data class Loaded(val data: ChaldeaGameData) : ChaldeaGameDataResult
    data class Error(val code: String) : ChaldeaGameDataResult
}

/** Pure JVM parser/reader for Chaldea's `files/game/` folder and binder responses. */
object ChaldeaGameDataReader {
    fun errorJson(code: ChaldeaSyncCode): String =
        buildJsonObject { put("error", code.name) }.toString()

    fun readResponseJson(files: ChaldeaFileAccess, dirExists: Boolean, knownVersion: String?): String {
        if (!dirExists) return errorJson(ChaldeaSyncCode.NotFound)
        if (!files.exists(ChaldeaGameData.VERSION_FILE)) return errorJson(ChaldeaSyncCode.NotFound)
        if (files.length(ChaldeaGameData.VERSION_FILE) > ChaldeaUserdataEditor.MAX_FILE_BYTES) {
            return errorJson(ChaldeaSyncCode.TooLarge)
        }
        val versionBytes = try {
            files.read(ChaldeaGameData.VERSION_FILE)
        } catch (e: ChaldeaIoException) {
            return errorJson(e.toSyncCode())
        }
        val version = sha256Hex(versionBytes)
        if (knownVersion != null && version == knownVersion) {
            return buildJsonObject {
                put("version", version)
                put("unchanged", true)
            }.toString()
        }

        val costumes = LinkedHashMap<Long, Map<Long, Long>>()
        val transforms = LinkedHashMap<Long, Long>()
        var filesRead = 0
        for (index in 1..ChaldeaGameData.MAX_SERVANT_FILES) {
            val name = "servants.$index.json"
            if (!files.exists(name)) break
            if (files.length(name) > ChaldeaUserdataEditor.MAX_FILE_BYTES) {
                return errorJson(ChaldeaSyncCode.TooLarge)
            }
            val bytes = try {
                files.read(name)
            } catch (e: ChaldeaIoException) {
                return errorJson(e.toSyncCode())
            }
            val text = String(bytes, Charsets.UTF_8)
            parseServantsFile(text, costumes, transforms)?.let { return errorJson(it) }
            filesRead++
        }
        if (filesRead == 0) return errorJson(ChaldeaSyncCode.NotFound)

        val resultJson = ChaldeaGameData(version, costumes, transforms).toJson()
        if (resultJson.length > ChaldeaGameData.MAX_RESULT_CHARS) {
            return errorJson(ChaldeaSyncCode.TooLarge)
        }
        return resultJson
    }

    internal fun parseServantsFile(
        text: String,
        costumes: MutableMap<Long, Map<Long, Long>>,
        transforms: MutableMap<Long, Long>,
    ): ChaldeaSyncCode? {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return ChaldeaSyncCode.InvalidJson
        }
        val arr = root as? JsonArray ?: return ChaldeaSyncCode.UnexpectedShape
        for (el in arr) {
            val svt = el as? JsonObject ?: return ChaldeaSyncCode.UnexpectedShape
            val svtId = (svt["id"] as? JsonPrimitive)?.longOrNull ?: continue
            val costumeObj = (svt["profile"] as? JsonObject)?.get("costume") as? JsonObject
            if (costumeObj != null && costumeObj.isNotEmpty()) {
                val map = LinkedHashMap<Long, Long>(costumeObj.size)
                for ((k, v) in costumeObj) {
                    val entry = v as? JsonObject ?: continue
                    val cid = (entry["id"] as? JsonPrimitive)?.longOrNull ?: continue
                    val bcId = (entry["battleCharaId"] as? JsonPrimitive)?.longOrNull ?: k.toLongOrNull() ?: continue
                    map[cid] = bcId
                }
                if (map.isNotEmpty()) costumes[svtId] = map
            }
            val saveTransform = ((svt["script"] as? JsonObject)?.get("transformInfo") as? JsonObject)
                ?.get("saveTransform")?.let { (it as? JsonPrimitive)?.longOrNull }
            if (saveTransform != null && saveTransform > 0L) {
                transforms[svtId] = saveTransform
            }
        }
        return null
    }

    fun parseResponse(text: String?): ChaldeaGameDataResult {
        if (text == null) return ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)
        val o = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)

        (o["error"] as? JsonPrimitive)?.contentOrNull?.let { code ->
            return ChaldeaGameDataResult.Error(code)
        }
        val unchanged = (o["unchanged"] as? JsonPrimitive)?.booleanOrNull == true
        if (unchanged) {
            val version = (o["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            if (version != null) return ChaldeaGameDataResult.Unchanged(version)
            return ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)
        }
        val parsed = ChaldeaGameData.fromJson(text)
            ?: return ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)
        return ChaldeaGameDataResult.Loaded(parsed)
    }

    private fun ChaldeaIoException.toSyncCode(): ChaldeaSyncCode = when (kind) {
        ChaldeaIoException.Kind.NotFound -> ChaldeaSyncCode.NotFound
        ChaldeaIoException.Kind.NoAccess -> ChaldeaSyncCode.NoAccess
        else -> ChaldeaSyncCode.Unreadable
    }
}
