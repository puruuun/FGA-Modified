package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*

/** Only an own-account row's direct userId can identify its owner. Never inspect nested data. */
object AccountRouting {
    const val CONFLICT = "\u0000conflict"
    private val ownTables = setOf(
        "userGame", "userItem", "userSvt", "userSvtCollection", "userQuest", "userEquip",
        "userEvent", "userCommandCode",
    )

    /** Null means no own-account evidence, rather than a fallback to the current account. */
    fun bodyUserId(target: String, body: JsonObject): String? = userId(target, body, null)

    /** The one id shared by both sides of every friend relation. A single row is ambiguous. */
    fun friendOwner(body: JsonObject): String? {
        val cache = body["cache"] as? JsonObject ?: return null
        val rows = listOf("replaced", "updated").flatMap {
            ((cache[it] as? JsonObject)?.get("tblFriend") as? JsonArray).orEmpty()
        }
        if (rows.size < 2) return null
        val sides = rows.map { element ->
            val row = element as? JsonObject ?: return null
            listOf("userId", "friendId").map { key ->
                (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            }.toSet()
        }
        return sides.reduce { common, ids -> common intersect ids }.singleOrNull()
    }

    fun userId(target: String, body: JsonObject, current: String?): String? = runCatching {
        val cache = body["cache"] as? JsonObject ?: return@runCatching current
        val login = CaptureNames.classify(target) == CaptureNames.ApiPath.Login
        val ids = buildSet {
            for (section in if (login) listOf("replaced") else listOf("replaced", "updated", "deleted")) {
                val tables = cache[section] as? JsonObject ?: continue
                for (table in if (login) setOf("userGame") else ownTables) {
                    val rows = tables[table] as? JsonArray ?: continue
                    for (row in rows) {
                        val id = ((row as? JsonObject)?.get("userId") as? JsonPrimitive)?.contentOrNull
                        if (!id.isNullOrBlank()) add(id)
                    }
                }
            }
        }
        when (ids.size) {
            0 -> current
            1 -> ids.single()
            else -> CONFLICT
        }
    }.getOrDefault(current)
}
