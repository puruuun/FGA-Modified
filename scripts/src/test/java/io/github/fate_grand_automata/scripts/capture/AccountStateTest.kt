package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AccountStateTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun login(replaced: String, code: String = "00") = obj(
        """{"response":[{"nid":"login","resCode":"$code","success":{"x":1}}],
            "cache":{"replaced":$replaced,"serverTime":1000}}""",
    )
    private val tables = """{"userGame":[{"userId":"7","name":"M","lv":10}],
        "userItem":[{"userId":"7","itemId":100,"num":3},{"userId":"7","itemId":16,"num":5}]}"""

    private fun start(now: Long = 50) =
        assertNotNull(AccountState.reduce(null, "/login/top?x=1", login(tables), now))

    private fun items(s: AccountState) = s.tables.getValue("userItem").associate {
        it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject["num"]!!.jsonPrimitive.content
    }

    @Test fun snapshotFromLoginTop() {
        val s = start()
        assertEquals(2, s.tables.getValue("userItem").size)
        assertEquals(1, s.loginResponse.size)
        assertEquals(50, s.snapshotTime)
        assertEquals(50, s.lastUpdateTime)
        assertEquals("1000", s.serverTime.toString())
    }

    @Test fun deltasWithoutSnapshotAreIgnored() {
        val body = obj("""{"cache":{"updated":{"userItem":[{"itemId":1,"num":1}]}}}""")
        assertNull(AccountState.reduce(null, "/shop/buy", body, 5))
    }

    @Test fun failedOrCachelessLoginIsNotASnapshot() {
        assertNull(AccountState.reduce(null, "/login/top", login(tables, code = "99"), 5))
        val prev = start()
        val bare = obj("""{"response":[{"nid":"login","resCode":"00"}],"cache":{}}""")
        assertSame(prev, AccountState.reduce(prev, "/login/top", bare, 9))
    }

    @Test fun upsertReplaceAndDelete() {
        var s = start()
        s = AccountState.reduce(
            s, "/x",
            obj("""{"cache":{"updated":{"userItem":[{"userId":"7","itemId":100,"num":9},{"userId":"7","itemId":200,"num":1}]},"serverTime":2000}}"""),
            60,
        )!!
        assertEquals(mapOf("100" to "9", "16" to "5", "200" to "1"), items(s))
        assertEquals(60, s.lastUpdateTime)
        assertEquals(50, s.snapshotTime)
        assertEquals("2000", s.serverTime.toString())
        s = AccountState.reduce(s, "/x", obj("""{"cache":{"deleted":{"userItem":[{"userId":"7","itemId":16}]}}}"""), 61)!!
        assertEquals(mapOf("100" to "9", "200" to "1"), items(s))
        s = AccountState.reduce(s, "/x", obj("""{"cache":{"replaced":{"userItem":[{"itemId":5,"num":1}]}}}"""), 62)!!
        assertEquals(mapOf("5" to "1"), items(s))
        assertEquals("M", s.tables.getValue("userGame")[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun accountPathsAreNeverApplied() {
        val s = start()
        val r = AccountState.reduce(s, "/NMAccount/x", obj("""{"cache":{"replaced":{"userItem":[]}}}"""), 70)
        assertSame(s, r)
    }

    @Test fun unchangedResponseKeepsLastUpdate() {
        val s = start()
        val same = obj("""{"cache":{"updated":{"userItem":[{"userId":"7","itemId":100,"num":3}]}}}""")
        assertEquals(50, AccountState.reduce(s, "/x", same, 99)!!.lastUpdateTime)
    }

    @Test fun newLoginResetsTables() {
        val s = start()
        val r = AccountState.reduce(s, "/login/top", login("""{"userGame":[{"userId":"7"}],"userItem":[{"itemId":1,"num":1}]}"""), 200)!!
        assertEquals(mapOf("1" to "1"), items(r))
        assertEquals(200, r.snapshotTime)
        assertNotNull(r.tables["userGame"])
    }

    @Test fun loginWithoutUserGameNeverReplacesAnExistingSnapshot() {
        val s = start()
        assertSame(s, AccountState.reduce(s, "/login/top", login("""{"userItem":[]}"""), 200))
        assertNull(AccountState.reduce(null, "/login/top", login("""{}"""), 200))
    }

    @Test fun rowKeyRules() {
        fun k(t: String, j: String) = AccountState.rowKey(t, obj(j))
        assertEquals(listOf("userId" to "1"), k("userGame", """{"userId":1}"""))
        assertEquals(listOf("userId" to "1"), k("tblUserGame", """{"userId":1}"""))
        assertEquals(listOf("itemId" to "2"), k("userItem", """{"userId":1,"itemId":2}"""))
        for (t in listOf("userSvtCollection", "userSvtCoin", "userSvtVoicePlayed"))
            assertEquals(listOf("svtId" to "3"), k(t, """{"userId":1,"svtId":3,"id":9}"""))
        assertEquals(listOf("userSvtId" to "4"), k("userSvtAppendPassiveSkillLv", """{"userSvtId":4,"id":9}"""))
        assertEquals(listOf("questId" to "5"), k("userQuest", """{"questId":5,"id":1}"""))
        assertEquals(listOf("questId" to "5"), k("userQuestInfo", """{"questId":5}"""))
        assertEquals(listOf("eventId" to "6"), k("userEvent", """{"eventId":6,"userId":1}"""))
        assertEquals(listOf("commandCodeId" to "8"), k("userCommandCodeCollection", """{"commandCodeId":8}"""))
        assertEquals(listOf("id" to "9"), k("userOther", """{"id":9,"fooId":1}"""))
        assertEquals(listOf("aId" to "1", "bId" to "2"), k("userOther", """{"userId":7,"bId":2,"aId":1}"""))
        assertNull(k("userOther", """{"userId":7,"name":"x"}"""))
        assertNull(k("userItem", """{"num":1}"""))
    }

    @Test fun jsonRoundTrip() {
        val s = start()
        val back = AccountState.fromJson(s.toJson())
        assertEquals(s, back)
        assertEquals(s.loginResponse, back.loginResponse)
    }

    private val others = listOf("otherUserGame", "userFollower", "userRecommendFollower")

    @Test fun otherPlayersTablesAreNeverKept() {
        val s = start()
        val rows = others.joinToString(",") { """"$it":[{"userId":"99","id":1}]""" }
        val replaced = AccountState.reduce(s, "friend/top", obj("""{"cache":{"replaced":{$rows,"userItem":[{"userId":"7","itemId":100,"num":9}]}}}"""), 60)!!
        val updated = AccountState.reduce(replaced, "follower/list", obj("""{"cache":{"updated":{$rows}}}"""), 70)
        val loginWith = AccountState.reduce(null, "/login/top", login(tables.dropLast(1) + ",$rows}"), 5)!!
        for (state in listOfNotNull(replaced, updated, loginWith)) assertTrue(others.none { it in state.tables.keys })
        assertEquals("9", items(replaced)["100"])
        // A response that only carries other players' rows changes nothing at all.
        assertSame(s, AccountState.reduce(s, "friend/top", obj("""{"cache":{"updated":{$rows}}}"""), 80))
        // Deleting other players' rows is ignored the same way.
        assertSame(s, AccountState.reduce(s, "friend/top", obj("""{"cache":{"deleted":{$rows}}}"""), 90))
    }

    @Test fun savedStatesLoseOtherPlayersTablesOnLoad() {
        val s = start()
        val old = s.copy(tables = s.tables + others.associateWith { kotlinx.serialization.json.JsonArray(listOf(obj("""{"id":1}"""))) })
        val back = AccountState.fromJson(old.toJson())
        assertEquals(s.tables, back.tables)
        assertEquals(s.loginResponse, back.loginResponse)
    }
}
