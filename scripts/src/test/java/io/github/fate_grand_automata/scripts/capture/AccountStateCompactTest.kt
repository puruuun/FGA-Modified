package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Issue 258: the compact AccountState must give exactly what the tree-based one did. */
class AccountStateCompactTest {
    private val login = "https://x/login/top"
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val body = SyntheticAccount.loginBody(megabytes = 0.3, tables = 12)

    private fun both(target: String, text: String, previous: Pair<AccountState?, OldAccountState?>, now: Long): Pair<AccountState?, OldAccountState?> {
        val tree = obj(text)
        return AccountState.reduce(previous.first, target, tree, now) to OldAccountState.reduce(previous.second, target, tree, now)
    }

    private fun assertEqualStates(new: AccountState?, old: OldAccountState?) {
        if (old == null) { assertNull(new); return }
        assertNotNull(new)
        assertEquals(Json.encodeToString(old), new.toJson())
        assertEquals(old.tables, new.tables.toMap())
        assertEquals(old.serverTime, new.serverTime)
        assertEquals(old.lastUpdateTime, new.lastUpdateTime)
        assertEquals(old.snapshotTime, new.snapshotTime)
    }

    private val sequence = listOf(
        "/battle/result" to """{"cache":{"updated":{"userItem":[{"userId":"7001","itemId":100,"num":5},{"userId":"7001","itemId":5000,"num":1}],
            "userGame":[{"userId":"7001","name":"Renamed","lv":121}],"newTable":[{"id":1,"v":1}]},"serverTime":1800000100}}""",
        "/x" to """{"cache":{"updated":{"userItem":[{"userId":"7001","itemId":100,"num":5}]}}}""",
        "/x" to """{"cache":{"updated":{"userItem":[]}}}""",
        "/x" to """{"cache":{"updated":{"emptyNew":[]}}}""",
        "/x" to """{"cache":{"deleted":{"userItem":[{"userId":"7001","itemId":5000}],"missing":[{"id":1}]}}}""",
        "/x" to """{"cache":{"deleted":{"userItem":[{"userId":"7001","itemId":777}]}}}""",
        "/x" to """{"cache":{"replaced":{"userItem":[{"userId":"7001","itemId":1,"num":1}],"notAnArray":{"a":1},"otherUserGame":[{"userId":"9"}]}}}""",
        "/NMAccount/x" to """{"cache":{"updated":{"userItem":[{"itemId":1,"num":9}]}}}""",
        "/x" to """{"nothing":true}""",
    )

    @Test fun savedFileAndEveryReductionEqualTheTreeVersion() {
        var state = both(login, body, null to null, 10)
        assertEqualStates(state.first, state.second)
        var now = 20L
        for ((path, text) in sequence) {
            state = both("https://x$path", text, state, now++)
            assertEqualStates(state.first, state.second)
        }
        // A failed login merges into the previous state, a login without userGame changes nothing.
        val failed = """{"response":[{"nid":"login","resCode":"99"}],"cache":{"replaced":{"userItem":[{"userId":"7001","itemId":3,"num":3}]}}}"""
        state = both(login, failed, state, 90)
        assertEqualStates(state.first, state.second)
        val noGame = """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userItem":[]}}}"""
        state = both(login, noGame, state, 91)
        assertEqualStates(state.first, state.second)
    }

    @Test fun savedTextLoadsInBothDirectionsAndFlagsOtherPlayersTables() {
        val old = OldAccountState.reduce(null, login, obj(body), 10)!!
        val text = Json.encodeToString(old)
        val new = AccountState.fromJson(text)
        assertEquals(text, new.toJson())
        assertEquals(old, OldAccountState.fromJson(new.toJson()))
        assertFalse(AccountState.fromStreamChecked(text.byteInputStream()).second)
        val legacy = text.replaceFirst("\"tables\":{", "\"tables\":{\"otherUserGame\":[{\"userId\":\"11\"}],\"userFollower\":[],")
        val (loaded, dropped) = AccountState.fromStreamChecked(legacy.byteInputStream())
        assertTrue(dropped)
        assertEquals(text, loaded.toJson())
        assertEquals(OldAccountState.fromJson(legacy).tables, loaded.tables.toMap())
        // Also without a server time and with an explicit null one.
        val noTime = text.replace(",\"serverTime\":1800000000", "")
        assertEquals(noTime, AccountState.fromJson(noTime).toJson())
        assertNull(AccountState.fromJson(noTime.replace("\"snapshotTime\"", "\"serverTime\":null,\"snapshotTime\"")).serverTime)
        assertFailsWith<Exception> { AccountState.fromJson("""{"tables":{"a":{}},"loginResponse":[],"snapshotTime":1,"lastUpdateTime":1}""") }
        assertFailsWith<Exception> { AccountState.fromJson("""{"tables":{},"snapshotTime":1,"lastUpdateTime":1}""") }
    }

    private fun oldExport(state: OldAccountState) = buildJsonObject {
        put("response", state.loginResponse)
        putJsonObject("cache") {
            put("updated", JsonObject(emptyMap()))
            put("deleted", JsonObject(emptyMap()))
            put("replaced", JsonObject(state.tables))
            put("serverTime", state.serverTime ?: JsonNull)
        }
    }.toString()

    @Test fun chaldeaExportIsByteIdentical() {
        var state = both(login, body, null to null, 10)
        for ((path, text) in sequence) state = both("https://x$path", text, state, 30)
        assertEquals(oldExport(state.second!!), ChaldeaExport.body(state.first!!))
        assertEquals(oldExport(state.second!!.copy(serverTime = null)), ChaldeaExport.body(state.first!!.copy(serverTime = null)))
    }

    @Test fun streamedLoginEqualsTheNormalReducerForEveryShape() {
        val prev = both(login, body, null to null, 10).first
        val variants = listOf(
            body,
            """{"response":[{"nid":"login","resCode":"99"}],"cache":{"replaced":{"userItem":[{"userId":"7001","itemId":3,"num":3}],"x":[1]}}}""",
            """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userItem":[]}}}""",
            """{"response":[{"nid":"login","resCode":"00"}]}""",
            """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userGame":[{"userId":"8"}],"userFollower":[{"a":1}],"y":[]},
                "updated":{"y":[{"id":1}],"otherUserGame":[{"userId":"3"}]},"deleted":{"userGame":[]},"serverTime":5}}""",
            """{"cache":{"updated":{"userItem":[{"userId":"7001","itemId":100,"num":1}]}}}""",
        )
        for (text in variants) for (previous in listOf(null, prev)) {
            val read = AccountState.readLogin(text.byteInputStream(), login)
            assertEquals(AccountState.reduce(previous, login, obj(text), 77), read.reducer(previous, 77), text)
            assertEquals(AccountRouting.userId(login, obj(text), "cur"), AccountRouting.userId(login, read.routing, "cur"), text)
        }
        assertTrue(AccountState.readLogin(body.byteInputStream(), login).replacesAccount)
        assertFalse(AccountState.readLogin(variants[1].byteInputStream(), login).replacesAccount)
        assertFalse(AccountState.readLogin(variants[3].byteInputStream(), login).replacesAccount)
        assertFailsWith<Exception> { AccountState.readLogin("""{"cache":{"replaced":""".byteInputStream(), login) }
    }

    @Test fun summaryAtAnotherTimeEqualsAFreshSummary() {
        val state = AccountState.reduce(null, login, obj(body), 10)!!
        val at = AccountSummary.from(state, 1_999_990_000)
        for (t in listOf(0L, 1_999_990_001, 1_999_999_999, 2_000_000_000, 2_000_000_001, 2_100_000_000)) {
            assertEquals(AccountSummary.from(state, t), at.atTime(t), "t=$t")
        }
        val json = Json.encodeToString(AccountSummary.serializer(), at)
        assertEquals(at, Json.decodeFromString(AccountSummary.serializer(), json))
    }

    @Test fun rosterStreamsToTheSameBytes() {
        val roster = FriendRoster(5, mapOf("1" to Friend("1", "name", 9, "123", listOf(1), listOf(2), 3, listOf(FriendSlot(1, 2, 3)))))
        val out = java.io.ByteArrayOutputStream()
        roster.writeTo(out)
        assertEquals(roster.toJson(), out.toString(Charsets.UTF_8))
        assertEquals(roster, FriendRoster.fromStream(out.toByteArray().inputStream()))
        assertEquals(roster, FriendRoster.fromJson(roster.toJson()))
    }

    @Test fun followerInfoPrecheckMatchesTheMerge() {
        val withFriend = obj("""{"cache":{"updated":{"userFollower":[{"followerInfo":[{"type":1,"userId":"11"}]}]}}}""")
        val strangers = obj("""{"cache":{"updated":{"userFollower":[{"followerInfo":[{"type":2,"userId":"11"}]}]}}}""")
        val none = obj("""{"cache":{"updated":{"userItem":[]}}}""")
        val roster = FriendRoster(1, mapOf("11" to Friend("11", "a")))
        assertTrue(FriendRoster.hasFollowerInfo(withFriend))
        for (b in listOf(strangers, none)) {
            assertFalse(FriendRoster.hasFollowerInfo(b))
            assertEquals(roster, FriendRoster.mergeFollowers(roster, b))
        }
    }

    private fun probe(heapMb: Int, vararg args: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-Xmx${heapMb}m", "-cp", System.getProperty("java.class.path"),
            "io.github.fate_grand_automata.scripts.capture.MemoryProbe", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test fun largeLoginIsProcessedUnderASmallHeapWhereTheTreeVersionRunsOut() {
        // 12 MB of JSON: the old way needs the String plus the tree (about 10x), far above 64 MB.
        val (newExit, newOut) = probe(64, "new", "12", "login")
        assertEquals(0, newExit, newOut)
        assertTrue(newOut.contains("OK"), newOut)
        val (oldExit, oldOut) = probe(64, "old", "12", "login")
        assertTrue(oldExit != 0 && oldOut.contains("OutOfMemoryError"), "the tree version should not fit: $oldOut")
    }

    @Test fun residentStateAndLoginPeakAreMuchSmallerThanTheTreeVersion() {
        fun heap(text: String) = Regex("""(\d+) MB heap""").find(text)!!.groupValues[1].toLong()
        val newResident = probe(1024, "new", "5", "resident").second.trim()
        val oldResident = probe(1024, "old", "5", "resident").second.trim()
        // The smallest max heap in which a 5 MB login/top is processed; a sampled peak is too noisy to trust.
        fun smallest(mode: String) = listOf(16, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 384).first { probe(it, mode, "5", "login").first == 0 }
        val newPeak = "smallest heap for a 5 MB login/top, new: " + smallest("new") + " MB"
        val oldPeak = "smallest heap for a 5 MB login/top, old: " + smallest("old") + " MB"
        File("build/memory-measurements.txt").writeText(listOf(newResident, oldResident, newPeak, oldPeak).joinToString(System.lineSeparator()))
        assertTrue(heap(newResident) * 3 < heap(oldResident), "$newResident vs $oldResident")
    }
}
