package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Immutable account cache. Times are Unix seconds; no requests, headers or response history. */
@Serializable
data class AccountState(
    val tables: Map<String, JsonArray>,
    val loginResponse: JsonArray,
    val serverTime: JsonElement? = null,
    val snapshotTime: Long,
    val lastUpdateTime: Long,
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        /** Other players' rows. They live in the friend roster instead, never in the account state. */
        val OTHER_PLAYER_TABLES = setOf("otherUserGame", "userFollower", "userRecommendFollower")

        /** Older saved states may still hold other players' tables; they are dropped on load. */
        fun fromJson(value: String): AccountState = Json.decodeFromString<AccountState>(value).let { state ->
            if (state.tables.keys.any { it in OTHER_PLAYER_TABLES }) state.copy(tables = state.tables - OTHER_PLAYER_TABLES) else state
        }

        /** Before a successful login snapshot, deltas cannot form an account. */
        fun reduce(previous: AccountState?, target: String, body: JsonObject, now: Long): AccountState? {
            val kind = CaptureNames.classify(target)
            if (kind == CaptureNames.ApiPath.Account) return previous
            val cache = body["cache"] as? JsonObject ?: return previous
            val response = body["response"] as? JsonArray
            val login = kind == CaptureNames.ApiPath.Login && response?.any {
                val row = it as? JsonObject
                row?.get("nid")?.jsonPrimitive?.contentOrNull == "login" &&
                    row["resCode"]?.jsonPrimitive?.contentOrNull == "00"
            } == true
            val replaced = cache["replaced"] as? JsonObject
            // A login without its own userGame is not a new account snapshot.
            if (login && (replaced?.get("userGame") as? JsonArray)?.any { it is JsonObject } != true) return previous
            val base = if (login) AccountState(emptyMap(), response, snapshotTime = now, lastUpdateTime = now)
                else previous ?: return null
            val tables = base.tables.toMutableMap()
            replaced?.forEach { (table, rows) -> if (rows is JsonArray && table !in OTHER_PLAYER_TABLES) tables[table] = rows }
            (cache["updated"] as? JsonObject)?.forEach { (table, rows) ->
                if (rows is JsonArray && table !in OTHER_PLAYER_TABLES) {
                    val current = tables[table].orEmpty().toMutableList()
                    for (row in rows) {
                        val key = rowKey(table, row) ?: continue
                        val index = current.indexOfFirst { rowKey(table, it) == key }
                        if (index < 0) current += row else current[index] = row
                    }
                    tables[table] = JsonArray(current)
                }
            }
            (cache["deleted"] as? JsonObject)?.forEach { (table, rows) ->
                if (rows is JsonArray && table !in OTHER_PLAYER_TABLES) {
                    val keys = rows.mapNotNull { rowKey(table, it) }.toSet()
                    tables[table]?.let { tables[table] = JsonArray(it.filterNot { row -> rowKey(table, row) in keys }) }
                }
            }
            val time = cache["serverTime"]?.takeUnless { it == JsonNull } ?: base.serverTime
            if (!login && tables == base.tables && time == base.serverTime) return base
            return base.copy(tables = tables, serverTime = time, lastUpdateTime = now)
        }

        /** Composite fallback is sorted and excludes userId so table-wide updates remain stable. */
        fun rowKey(table: String, row: JsonElement): List<Pair<String, String>>? {
            val obj = row as? JsonObject ?: return null
            val fields = when (table) {
                "userGame", "tblUserGame" -> listOf("userId")
                "userItem" -> listOf("itemId")
                "userSvtCollection", "userSvtCoin", "userSvtVoicePlayed" -> listOf("svtId")
                "userSvtAppendPassiveSkillLv" -> listOf("userSvtId")
                "userQuest", "userQuestInfo" -> listOf("questId")
                "userEvent" -> listOf("eventId")
                "userCommandCodeCollection" -> listOf("commandCodeId")
                else -> if ("id" in obj) listOf("id")
                    else obj.keys.filter { it.endsWith("Id") && it != "userId" }.sorted()
            }
            if (fields.isEmpty()) return null
            return fields.map { field ->
                field to ((obj[field] as? JsonPrimitive)?.contentOrNull ?: return null)
            }
        }
    }
}
