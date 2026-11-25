package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*

/** Issue #733: the linear merge of `cache.updated` must give exactly what the per-row scan gave. */
class AccountStateLinearMergeTest {
    private val seed = 733
    private val cases = 1500
    private val stepsPerCase = 6

    /** Table name to the key field it uses; null means the sorted `*Id` fallback, "" means rows have no key field. */
    private val tableKinds = linkedMapOf(
        "userGame" to "userId", "tblUserGame" to "userId", "userLogin" to "userId",
        "userItem" to "itemId",
        "userSvtCollection" to "svtId", "userSvtCoin" to "svtId", "userSvtVoicePlayed" to "svtId",
        "userSvtAppendPassiveSkillLv" to "userSvtId",
        "userQuest" to "questId", "userQuestInfo" to "questId",
        "userEvent" to "eventId",
        "userCommandCodeCollection" to "commandCodeId",
        "userSvt" to "id", "userDeck" to "id",
        "userShop" to null, "userEventMission" to null,
        "userGameCommon" to "",
        "userFollower" to "userId",
    )

    private class Coverage {
        val seen = mutableMapOf<String, Int>()
        fun hit(name: String) { seen[name] = (seen[name] ?: 0) + 1 }
    }

    private fun Random.keyValue(): JsonElement = when (nextInt(10)) {
        0 -> JsonPrimitive(nextInt(4).toString())
        1 -> JsonNull
        2 -> JsonObject(mapOf("v" to JsonPrimitive(1)))
        else -> JsonPrimitive(nextInt(4))
    }

    private fun Random.row(table: String): JsonElement {
        when (nextInt(20)) {
            0 -> return JsonPrimitive(nextInt(3))
            1 -> return listOf(JsonNull, JsonPrimitive("x"), JsonArray(listOf(JsonPrimitive(1)))).random(this)
        }
        val field = tableKinds.getValue(table)
        val fields = LinkedHashMap<String, JsonElement>()
        if (nextInt(3) > 0) fields["userId"] = JsonPrimitive(listOf("7001", "7002")[nextInt(2)])
        when (field) {
            null -> {
                if (nextInt(8) > 0) fields["shopId"] = keyValue()
                if (nextInt(3) == 0) fields["eventId"] = keyValue()
                if (nextInt(10) == 0) fields["id"] = keyValue()
            }
            "" -> Unit
            else -> if (nextInt(10) > 0) fields[field] = keyValue()
        }
        fields["num"] = JsonPrimitive(nextInt(3))
        if (nextInt(4) == 0) fields["flag"] = JsonPrimitive(nextBoolean())
        // Key order changes the stored text but not the key.
        val ordered = if (nextInt(5) == 0) fields.entries.reversed().associate { it.toPair() } else fields
        return JsonObject(ordered)
    }

    private fun Random.rows(table: String, max: Int) = JsonArray(List(nextInt(max + 1)) { row(table) })

    private fun Random.section(maxTables: Int, maxRows: Int, force: Map<String, JsonArray> = emptyMap()): JsonObject {
        val names = tableKinds.keys.shuffled(this).take(nextInt(maxTables + 1))
        val out = LinkedHashMap<String, JsonElement>()
        force.forEach { (k, v) -> out[k] = v }
        // The stream reader discards other players' tables row by row and fails on a non-array one; reduce ignores it.
        for (name in names) out[name] = if (nextInt(25) == 0 && name !in AccountState.OTHER_PLAYER_TABLES) JsonPrimitive("notAnArray")
            else rows(name, maxRows)
        return JsonObject(out)
    }

    private fun Random.body(): Pair<String, JsonObject> {
        val target = listOf("/login/top", "/login/top", "/home/top", "/shop/buy", "/battle/result", "/NMAccount/x").random(this)
        val response = when (nextInt(4)) {
            0 -> null
            1 -> JsonArray(listOf(JsonObject(mapOf("nid" to JsonPrimitive("login"), "resCode" to JsonPrimitive("99")))))
            else -> JsonArray(listOf(JsonObject(mapOf("nid" to JsonPrimitive("login"), "resCode" to JsonPrimitive("00")))))
        }
        val body = LinkedHashMap<String, JsonElement>()
        response?.let { body["response"] = it }
        if (nextInt(15) > 0) {
            val cache = LinkedHashMap<String, JsonElement>()
            if (nextInt(3) > 0) {
                val game = if (nextInt(4) > 0) mapOf("userGame" to rows("userGame", 3)) else emptyMap()
                cache["replaced"] = section(3, 12, game)
            }
            if (nextInt(5) > 0) cache["updated"] = section(5, 25)
            if (nextInt(3) == 0) {
                val updated = cache["updated"] as? JsonObject
                // Often the same table and the same rows as in `updated`, so that one key is in both.
                val echo = updated?.entries?.firstOrNull { it.value is JsonArray }?.takeIf { nextBoolean() }
                cache["deleted"] = if (echo != null) JsonObject(mapOf(echo.key to JsonArray((echo.value as JsonArray).take(3))))
                    else section(3, 6)
            }
            when (nextInt(4)) {
                0 -> cache["serverTime"] = JsonPrimitive(1_800_000_000 + nextInt(5))
                1 -> cache["serverTime"] = JsonNull
            }
            body["cache"] = JsonObject(cache)
        }
        return "https://x$target" to JsonObject(body)
    }

    private fun note(coverage: Coverage, previous: AccountState?, body: JsonObject) {
        val cache = body["cache"] as? JsonObject ?: return
        val updated = cache["updated"] as? JsonObject ?: return
        val deleted = cache["deleted"] as? JsonObject
        if (cache["replaced"] != null && cache["deleted"] != null) coverage.hit("replaced+updated+deleted")
        for ((table, rows) in updated) {
            if (rows !is JsonArray) continue
            if (table in AccountState.OTHER_PLAYER_TABLES) continue
            val stored = previous?.rawTables?.get(table)?.let { Json.parseToJsonElement(it) as JsonArray }
            if (stored == null) coverage.hit("table absent") else if (stored.isEmpty()) coverage.hit("table empty")
            val storedKeys = stored.orEmpty().mapNotNull { QuadraticMergeReference.rowKey(table, it) }
            if (storedKeys.size != storedKeys.toSet().size) coverage.hit("duplicate key stored")
            val keys = rows.map { QuadraticMergeReference.rowKey(table, it) }
            if (rows.any { it !is JsonObject }) coverage.hit("row not an object")
            if (rows.any { it is JsonObject } && keys.any { it == null }) coverage.hit("object without key")
            val keyed = keys.filterNotNull()
            if (keyed.size != keyed.toSet().size) coverage.hit("duplicate key in one response")
            if (keyed.any { it in storedKeys }) coverage.hit("key already stored")
            if (keyed.any { it !in storedKeys }) coverage.hit("new key")
            if (rows.any { it in stored.orEmpty() && QuadraticMergeReference.rowKey(table, it) != null }) coverage.hit("unchanged row")
            val deletedKeys = (deleted?.get(table) as? JsonArray)?.mapNotNull { QuadraticMergeReference.rowKey(table, it) }.orEmpty()
            if (keyed.any { it in deletedKeys }) coverage.hit("same key updated and deleted")
            coverage.hit("table $table")
        }
    }

    private fun assertSameResult(
        label: String, previousNew: AccountState?, previousRef: AccountState?, new: AccountState?, reference: AccountState?,
    ) {
        if (reference == null) { assertNull(new, label); return }
        assertNotNull(new, label)
        assertEquals(reference.toJson(), new.toJson(), label)
        assertEquals(reference.rawTables.keys.toList(), new.rawTables.keys.toList(), label)
        assertEquals(reference === previousRef, new === previousNew, "$label: returns the previous state")
    }

    @Test fun generatedInputsGiveTheSameStoredTextAsTheScan() {
        val random = Random(seed)
        val coverage = Coverage()
        var compared = 0
        repeat(cases) { case ->
            var new: AccountState? = null
            var reference: AccountState? = null
            if (random.nextInt(4) > 0) {
                // A base that already holds rows, with duplicate keys among them.
                val tables = tableKinds.keys.filter { random.nextBoolean() && it !in AccountState.OTHER_PLAYER_TABLES }
                    .associateWith { random.rows(it, 15).toString() }
                val loginResponse = """[{"nid":"login","resCode":"00"}]"""
                new = AccountState(tables, loginResponse, JsonPrimitive(1_799_999_999).takeIf { random.nextBoolean() }, 1, 1)
                reference = new
            }
            for (step in 0 until stepsPerCase) {
                val (target, body) = random.body()
                val now = (100 + case * 10 + step).toLong()
                val label = "case $case step $step $target ${body.toString().take(400)}"
                note(coverage, new, body)
                if (CaptureNames.classify(target) == CaptureNames.ApiPath.Login) coverage.hit("login target") else coverage.hit("other target")
                val nextRef = QuadraticMergeReference.reduce(reference, target, body, now)
                val nextNew = AccountState.reduce(new, target, body, now)
                assertSameResult(label, new, reference, nextNew, nextRef)
                val text = body.toString()
                val streamed = AccountState.readLogin(text.byteInputStream(), target).reducer(new, now)
                assertSameResult("$label (stream)", new, reference, streamed, nextRef)
                compared++
                new = nextNew
                reference = nextRef
            }
        }
        assertEquals(cases * stepsPerCase, compared)
        val required = listOf(
            "table absent", "table empty", "duplicate key stored", "row not an object", "object without key",
            "duplicate key in one response", "key already stored", "new key", "unchanged row",
            "same key updated and deleted", "replaced+updated+deleted", "login target", "other target",
        ) + tableKinds.keys.filter { it !in AccountState.OTHER_PLAYER_TABLES }.map { "table $it" }
        val missing = required.filter { (coverage.seen[it] ?: 0) < 20 }
        assertTrue(missing.isEmpty(), "too few cases for $missing: ${coverage.seen}")
    }

    private fun shopRow(i: Int, num: Int) = JsonObject(mapOf(
        "userId" to JsonPrimitive("7001"), "shopId" to JsonPrimitive(1_000_000 + i), "num" to JsonPrimitive(num),
        "updatedAt" to JsonPrimitive(1_700_000_000 + i), "createdAt" to JsonPrimitive(1_600_000_000),
    ))

    @Test fun wholeShopTableComputesEachKeyOnce() {
        val rows: List<JsonElement> = List(4687) { shopRow(it, 1) }
        var calls = 0
        val key = { row: JsonElement -> calls++; AccountState.rowKey("userShop", row) }

        val empty = mutableListOf<JsonElement>()
        assertTrue(AccountState.mergeUpdatedRows(empty, rows, key))
        assertEquals(rows, empty)
        assertTrue(calls <= rows.size, "empty base: $calls key computations for ${rows.size} rows")

        calls = 0
        val full = rows.toMutableList()
        val changed: List<JsonElement> = List(4687) { shopRow(it, if (it % 2 == 0) 2 else 1) }
        assertTrue(AccountState.mergeUpdatedRows(full, changed, key))
        assertEquals(changed, full)
        assertTrue(calls <= 2 * rows.size, "full base: $calls key computations for ${rows.size} rows")
    }

    @Test fun wholeShopTableLoginEqualsTheScan() {
        val rows = JsonArray(List(4687) { shopRow(it, it % 3) })
        val login = Json.parseToJsonElement("""{"response":[{"nid":"login","resCode":"00"}],
            "cache":{"replaced":{"userGame":[{"userId":"7001"}]},"updated":{"userShop":$rows},"serverTime":1800000000}}""").jsonObject
        val reference = assertNotNull(QuadraticMergeReference.reduce(null, "/login/top", login, 10))
        assertEquals(reference.toJson(), assertNotNull(AccountState.reduce(null, "/login/top", login, 10)).toJson())
        val streamed = AccountState.readLogin(login.toString().byteInputStream(), "/login/top").reducer(null, 10)
        assertEquals(reference.toJson(), assertNotNull(streamed).toJson())

        val again = Json.parseToJsonElement("""{"cache":{"updated":{"userShop":$rows}}}""").jsonObject
        assertSame(reference, QuadraticMergeReference.reduce(reference, "/shop/top", again, 20))
        assertSame(reference, AccountState.reduce(reference, "/shop/top", again, 20))
    }
}
