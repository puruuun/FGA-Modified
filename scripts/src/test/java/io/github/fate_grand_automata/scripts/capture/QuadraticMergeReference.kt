package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*

/**
 * Test-only: [AccountState.reduce] as it was before issue #733, with the per-row scan of `cache.updated`. The linear
 * merge must give exactly what this gives. Not used by production code.
 */
object QuadraticMergeReference {
    private fun parseArray(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    fun reduce(previous: AccountState?, target: String, body: JsonObject, now: Long): AccountState? {
        val kind = CaptureNames.classify(target)
        if (kind == CaptureNames.ApiPath.Account) return previous
        val cache = body["cache"] as? JsonObject ?: return previous
        val response = body["response"] as? JsonArray
        val replaced = (cache["replaced"] as? JsonObject).sectionTables()
        val updated = (cache["updated"] as? JsonObject).sectionTables()
        val deleted = (cache["deleted"] as? JsonObject).sectionTables()
        val time = cache["serverTime"]?.takeUnless { it == JsonNull }
        return reduceCache(previous, kind, response?.toString(), replaced.mapValues { it.value.toString() }, updated, deleted, time, now)
    }

    private fun JsonObject?.sectionTables(): Map<String, JsonArray> =
        this?.entries?.filter { (table, rows) -> rows is JsonArray && table !in AccountState.OTHER_PLAYER_TABLES }
            ?.associate { it.key to it.value as JsonArray }.orEmpty()

    private fun isLoginSuccess(response: JsonArray?) = response?.any {
        val row = it as? JsonObject
        row?.get("nid")?.jsonPrimitive?.contentOrNull == "login" &&
            row["resCode"]?.jsonPrimitive?.contentOrNull == "00"
    } == true

    private fun reduceCache(
        previous: AccountState?, kind: CaptureNames.ApiPath, response: String?,
        replaced: Map<String, String>, updated: Map<String, JsonArray>, deleted: Map<String, JsonArray>,
        serverTime: JsonElement?, now: Long,
    ): AccountState? {
        val responseArray = response?.let(::parseArray)
        val login = kind == CaptureNames.ApiPath.Login && isLoginSuccess(responseArray)
        if (login && replaced["userGame"]?.let(::parseArray)?.any { it is JsonObject } != true) return previous
        val base = if (login) AccountState(emptyMap<String, String>(), response!!, snapshotTime = now, lastUpdateTime = now)
            else previous ?: return null
        val tables = LinkedHashMap(base.rawTables)
        replaced.forEach { (table, rows) -> if (table !in AccountState.OTHER_PLAYER_TABLES) tables[table] = rows }
        updated.forEach { (table, rows) ->
            if (table in AccountState.OTHER_PLAYER_TABLES) return@forEach
            val existing = tables[table]
            val current = existing?.let(::parseArray)?.toMutableList() ?: mutableListOf()
            var changed = existing == null
            for (row in rows) {
                val key = rowKey(table, row) ?: continue
                val index = current.indexOfFirst { rowKey(table, it) == key }
                if (index < 0) { current += row; changed = true }
                else if (current[index] != row) { current[index] = row; changed = true }
            }
            if (changed) tables[table] = JsonArray(current).toString()
        }
        deleted.forEach { (table, rows) ->
            if (table in AccountState.OTHER_PLAYER_TABLES) return@forEach
            val existing = tables[table] ?: return@forEach
            val keys = rows.mapNotNull { rowKey(table, it) }.toSet()
            if (keys.isEmpty()) return@forEach
            val rest = parseArray(existing)
            val kept = rest.filterNot { row -> rowKey(table, row) in keys }
            if (kept.size != rest.size) tables[table] = JsonArray(kept).toString()
        }
        val time = serverTime ?: base.serverTime
        if (!login && tables == base.rawTables && time == base.serverTime) return base
        return AccountState(tables, base.rawLoginResponse, time, base.snapshotTime, now)
    }

    fun rowKey(table: String, row: JsonElement): List<Pair<String, String>>? {
        val obj = row as? JsonObject ?: return null
        val fields = when (table) {
            "userGame", "tblUserGame", "userLogin" -> listOf("userId")
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
