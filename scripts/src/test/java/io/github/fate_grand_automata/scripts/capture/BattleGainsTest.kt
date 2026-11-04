package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

class BattleGainsTest {
    private fun parse(success: String, updated: String = "{}", state: AccountState? = null) = BattleGains.parse(
        Json.parseToJsonElement("""{"response":[{"success":$success}],"cache":{"updated":$updated}}""").jsonObject, state, 100)

    @Test fun itemsAreSummedByTypeAndObjectWithoutCountingQpTwice() {
        val result = assertNotNull(parse("""{"resultDropInfos":[
            {"type":2,"objectId":6510,"num":2,"userSvtId":0},
            {"type":2,"objectId":6510,"num":3}, {"type":3,"objectId":6510,"num":1},
            {"type":2,"objectId":1,"num":1000}, {"type":2,"objectId":1,"num":2000}] }"""))
        assertEquals(listOf(GainedItem(2, 6510, 5), GainedItem(3, 6510, 1)), result.items)
        assertEquals(3000, result.qp)
        assertEquals(100, result.time)
    }

    @Test fun qpIncludesPhaseAndFirstClearButNotRewardInfosAgain() {
        val result = assertNotNull(parse("""{"phaseClearQp":100,"firstClearRewardQp":200,
            "resultDropInfos":[{"type":2,"objectId":1,"num":300}],
            "rewardInfos":[{"type":2,"objectId":1,"num":200}]}"""))
        assertEquals(600, result.qp)
    }

    @Test fun expAndBondUseOldResultRowsWithoutAnAccountSnapshot() {
        val result = assertNotNull(parse("""{"oldUserGame":[{"exp":100}],
            "oldUserSvtCollection":[{"svtId":10,"friendship":1000},{"svtId":20,"friendship":2000}]}""",
            """{"userGame":[{"exp":300}],"userSvtCollection":[
                {"svtId":10,"friendship":1500},{"svtId":20,"friendship":2700}],"userQuest":[{"questId":99}]}"""))
        assertEquals(200, result.masterExp)
        assertEquals(mapOf(10L to 500L, 20L to 700L), result.bond)
        assertEquals(99, result.questId)
    }

    @Test fun previousStateIsFallbackAndResultOldRowsWin() {
        val state = AccountState(mapOf(
            "userGame" to Json.parseToJsonElement("""[{"exp":50}]""").jsonArray,
            "userSvtCollection" to Json.parseToJsonElement("""[{"svtId":10,"friendship":100}]""").jsonArray,
            "battle" to Json.parseToJsonElement("""[{"questId":9}]""").jsonArray,
        ), JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)
        val result = assertNotNull(parse("""{"oldUserSvtCollection":[{"svtId":10,"friendship":200}]}""",
            """{"userGame":[{"exp":80}],"userSvtCollection":[{"svtId":10,"friendship":300}]}""", state))
        assertEquals(30, result.masterExp)
        assertEquals(mapOf(10L to 100L), result.bond)
        assertEquals(9, result.questId)
    }

    @Test fun missingFieldsAreUnknownAndMalformedRowsAreSkipped() {
        val result = assertNotNull(parse("""{"phaseClearQp":{},"resultDropInfos":[null,4,{},
            {"type":2,"objectId":1,"num":[]},{"type":2,"objectId":10,"num":-1}],
            "oldUserSvtCollection":[null,{"svtId":1,"friendship":{}}]}""",
            """{"userGame":[null],"userSvtCollection":[{"svtId":1,"friendship":5}]}"""))
        assertTrue(result.items.isEmpty())
        assertNull(result.qp)
        assertNull(result.masterExp)
        assertTrue(result.bond.isEmpty())
        assertNull(result.questId)
    }

    @Test fun invalidResponseIsNotCountedAsBattle() {
        for (body in listOf("{}", """{"response":{}}""", """{"response":[{"success":null}]}""")) {
            assertNull(BattleGains.parse(Json.parseToJsonElement(body).jsonObject, null))
        }
    }

    @Test fun countersThatMoveBackwardsAreSkipped() {
        val result = assertNotNull(parse("""{"oldUserGame":[{"exp":10}],"oldUserSvtCollection":[{"svtId":1,"friendship":10}]}""",
            """{"userGame":[{"exp":5}],"userSvtCollection":[{"svtId":1,"friendship":2}]}"""))
        assertNull(result.masterExp)
        assertTrue(result.bond.isEmpty())
    }
}
