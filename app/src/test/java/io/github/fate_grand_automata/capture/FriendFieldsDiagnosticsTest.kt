package io.github.fate_grand_automata.capture

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** Synthetic private values must never reach schema diagnostics. */
class FriendFieldsDiagnosticsTest {
    @TempDir lateinit var directory: File
    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject
    private val row = """{"userId":"private-user-id","userName":"PRIVATE-NAME","friendCode":"987654321",
        "message":"PRIVATE-MESSAGE","userSvtLeaderHash":[{"svtId":500,"grandSvt":1},{"svtId":501,"grandSvt":0}],
        "eventUserSvtLeaderHash":{"event":{"svtId":502,"grandSvt":1}},
        "userSvtGrandHash":[{"svtId":503,"grandGraphId":10001,"script":{"equipTarget2SkillChange":1,"privateKey":"PRIVATE-SCRIPT"}},
          {"svtId":504,"script":{}},{"svtId":505,"script":{"equipTarget2SkillChange":"PRIVATE-VALUE"}}]}"""
    private val top = body("""{"cache":{"updated":{"otherUserGame":[$row,$row]}}}""")
    private val file get() = File(directory, "diagnostics/friend-fields.json")

    private fun assertPrivateValuesAbsent() {
        val text = file.readText()
        for (value in listOf("private-user-id", "PRIVATE-NAME", "987654321", "PRIVATE-MESSAGE", "500", "501", "502", "503", "504", "505", "10001", "PRIVATE-SCRIPT", "PRIVATE-VALUE")) {
            assertFalse(value in text, value)
        }
    }

    @Test fun friendTopWritesOnlySortedFieldNamesAndCountsOnKrAndJp() {
        val store = CaptureAccounts().apply { load(directory) }
        for ((host, server) in listOf("yfgo-game.netmarble.com" to "KR", "game.fate-go.jp" to "JP")) {
            store.recordFriendFields("https://$host/friend/top", top, 42)
            val result = body(file.readText())
            assertEquals(setOf("server", "source", "time", "friendFields", "slotFields", "grandValues", "grandFields", "grandScriptKeys", "bondEffectValues"), result.keys)
            assertEquals(JsonPrimitive(server), result["server"])
            assertEquals(JsonPrimitive("friendTop"), result["source"])
            assertEquals(JsonPrimitive(42), result["time"])
            assertEquals(body(row).keys.sorted(), result["friendFields"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("grandSvt", "svtId"), result["slotFields"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(body("""{"0":2,"1":4}"""), result["grandValues"])
            assertEquals(body("""{"1":2,"none":4}"""), result["bondEffectValues"])
            assertEquals(listOf("grandGraphId", "script", "svtId"), result["grandFields"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("equipTarget2SkillChange", "privateKey"), result["grandScriptKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertPrivateValuesAbsent()
        }
    }

    @Test fun followersOverwriteTheFileAndCountAllSlotsWithoutCopyingValues() {
        val store = CaptureAccounts().apply { load(directory) }
        store.recordFriendFields("https://yfgo-game.netmarble.com/friend/top", top, 42)
        val followers = body("""{"cache":{"updated":{"userFollower":[{"followerInfo":[$row]},
            {"followerInfo":[{"userId":"another-private-id","userSvtLeaderHash":[{"grandSvt":0},{"grandSvt":"invalid"},{}]}]}]}}}""")
        for (path in listOf("follower/list", "battle/result")) {
            store.recordFriendFields("https://game.fate-go.jp/$path", followers, 43)
            val result = body(file.readText())
            assertEquals(JsonPrimitive("followers"), result["source"])
            assertEquals(JsonPrimitive(43), result["time"])
            assertEquals(body("""{"0":2,"1":2}"""), result["grandValues"])
            assertPrivateValuesAbsent()
            assertFalse("another-private-id" in file.readText())
        }
    }

    @Test fun grandObjectMapsExposeOnlySortedKeysAndStateCounts() {
        val original = body(row)
        val mapped = JsonObject(original + ("userSvtGrandHash" to JsonObject(
            original["userSvtGrandHash"]!!.jsonArray.mapIndexed { index, value -> index.toString() to value }.toMap())))
        val store = CaptureAccounts().apply { load(directory) }
        store.recordFriendFields("https://yfgo-game.netmarble.com/friend/top", body("""{"cache":{"updated":{"otherUserGame":[$mapped]}}}"""), 42)
        val result = body(file.readText())
        assertEquals(listOf("grandGraphId", "script", "svtId"), result["grandFields"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("equipTarget2SkillChange", "privateKey"), result["grandScriptKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(body("""{"1":1,"none":2}"""), result["bondEffectValues"])
        assertPrivateValuesAbsent()
    }

    @Test fun missingGrandAndEmptyRowsHaveEmptySchemaAndZeroCounts() {
        val store = CaptureAccounts().apply { load(directory) }
        store.recordFriendFields("https://yfgo-game.netmarble.com/friend/top", body("""{"cache":{"updated":{"otherUserGame":[]}}}"""), 1)
        val result = body(file.readText())
        assertEquals(JsonArray(emptyList()), result["friendFields"])
        assertEquals(JsonArray(emptyList()), result["slotFields"])
        assertEquals(body("""{"0":0,"1":0}"""), result["grandValues"])
        assertEquals(JsonArray(emptyList()), result["grandFields"])
        assertEquals(JsonArray(emptyList()), result["grandScriptKeys"])
        assertEquals(body("{}"), result["bondEffectValues"])
        store.recordFriendFields("https://yfgo-game.netmarble.com/battle/result", body("""{"cache":{"updated":{"userItem":[]}}}"""), 2)
        assertEquals(result, body(file.readText()))
    }

    @Test fun diagnosticsWriteFailureIsLoggedAndRosterStillSaves() {
        val warnings = mutableListOf<String>()
        val store = CaptureAccounts(warn = warnings::add).apply { load(directory) }
        File(directory, "diagnostics").writeText("blocked")
        val login = body("""{"response":[{"nid":"login","resCode":"00"}],
            "cache":{"replaced":{"userGame":[{"userId":7001,"name":"Owner"}]}}}""")
        store.apply("https://yfgo-game.netmarble.com/login/top", login, 1)
        val response = body("""{"cache":{"replaced":{"tblFriend":[{"userId":7001,"friendId":11},{"userId":12,"friendId":7001}]},
            "updated":{"otherUserGame":[{"userId":11,"userSvtLeaderHash":[{"svtId":500,"grandSvt":1}]}]}}}""")
        store.recordFriendFields("https://yfgo-game.netmarble.com/friend/top", response, 2)
        assertEquals(listOf("writing friend field diagnostics failed"), warnings)
        assertTrue(store.applyFriends("7001", "https://yfgo-game.netmarble.com/friend/top", response, 2))
        store.save()
        assertTrue(File(directory, "accounts/7001/friends.json").isFile)
        assertTrue(CaptureAccounts().apply { load(directory) }.roster("7001")!!.friends.getValue("11").slots.single().grand)
    }
}
