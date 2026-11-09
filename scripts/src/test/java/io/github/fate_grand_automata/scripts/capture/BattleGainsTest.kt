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

    // Synthetic ids only. Servants 10 and 20 are own, 30 is a support, 40 is an NPC follower.
    private fun deckResult(old: String, new: String, deck: String = DECK) = parse(
        """{"myDeck":{"svts":$deck},"oldUserSvtCollection":$old}""", """{"userSvtCollection":$new}""")

    private companion object {
        const val DECK = """[{"id":1,"svtId":10,"userSvtId":1},{"id":2,"svtId":20,"userSvtId":2},
            {"id":3,"svtId":30,"userSvtId":3,"isFollowerSvt":true},{"id":4,"svtId":40,"userSvtId":4,"npcFollowerSvtId":99}]"""
        const val JP_DECK = """[{"id":1,"svtId":10,"userSvtId":1},{"id":3,"svtId":30,"userSvtId":3,"followerType":1}]"""
    }

    @Test fun supportAndNpcRowsAreNotBondEvenWithZeroDelta() {
        val result = assertNotNull(deckResult(
            """[{"svtId":10,"friendship":100},{"svtId":30,"friendship":500},{"svtId":40,"friendship":0}]""",
            """[{"svtId":10,"friendship":300},{"svtId":30,"friendship":500},{"svtId":40,"friendship":0}]"""))
        assertEquals(mapOf(10L to 200L), result.bond)
    }

    @Test fun officialJpFollowerTypeSupportIsExcluded() {
        val result = assertNotNull(deckResult(
            """[{"svtId":10,"friendship":0},{"svtId":30,"friendship":0}]""",
            """[{"svtId":10,"friendship":50},{"svtId":30,"friendship":50}]""", JP_DECK))
        assertEquals(mapOf(10L to 50L), result.bond)
    }

    @Test fun unknownDeckFallsBackToAllRows() {
        val result = assertNotNull(parse("{}", """{"userSvtCollection":[{"svtId":30,"friendship":5}]}""",
            AccountState(mapOf("userSvtCollection" to Json.parseToJsonElement("""[{"svtId":30,"friendship":1}]""").jsonArray),
                JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)))
        assertEquals(mapOf(30L to 4L), result.bond)
    }

    @Test fun reachingRank10WithNoExceedIsMax() {
        val result = assertNotNull(deckResult(
            """[{"svtId":10,"friendship":100,"friendshipRank":9,"friendshipExceedCount":0}]""",
            """[{"svtId":10,"friendship":2555,"friendshipRank":10,"friendshipExceedCount":0}]"""))
        assertEquals(setOf(10L), result.bondMax)
    }

    @Test fun reachingRank12WithTwoExceedIsMax() {
        val result = assertNotNull(deckResult(
            """[{"svtId":20,"friendship":100,"friendshipRank":11,"friendshipExceedCount":2}]""",
            """[{"svtId":20,"friendship":900,"friendshipRank":12,"friendshipExceedCount":2}]"""))
        assertEquals(setOf(20L), result.bondMax)
    }

    @Test fun servantAlreadyAtCapIsNotMaxAgain() {
        val result = assertNotNull(deckResult(
            """[{"svtId":10,"friendship":100,"friendshipRank":10,"friendshipExceedCount":0}]""",
            """[{"svtId":10,"friendship":400,"friendshipRank":10,"friendshipExceedCount":0}]"""))
        assertEquals(mapOf(10L to 300L), result.bond)
        assertTrue(result.bondMax.isEmpty())
    }

    @Test fun rankIncreaseBelowCapIsNotMax() {
        val result = assertNotNull(deckResult(
            """[{"svtId":20,"friendship":100,"friendshipRank":10,"friendshipExceedCount":2}]""",
            """[{"svtId":20,"friendship":900,"friendshipRank":11,"friendshipExceedCount":2}]"""))
        assertTrue(result.bondMax.isEmpty())
    }

    @Test fun oldRankComesFromPreviousStateWhenResultHasNoOldRow() {
        val state = AccountState(mapOf("userSvtCollection" to Json.parseToJsonElement(
            """[{"svtId":10,"friendship":100,"friendshipRank":9}]""").jsonArray), JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)
        val result = assertNotNull(parse("""{"myDeck":{"svts":$DECK}}""",
            """{"userSvtCollection":[{"svtId":10,"friendship":900,"friendshipRank":10,"friendshipExceedCount":0}]}""", state))
        assertEquals(setOf(10L), result.bondMax)
    }

    @Test fun oldJsonWithoutNewFieldsDecodes() {
        val battle = Json.decodeFromString<BattleGains>("""{"time":1,"bond":{"10":0,"20":5}}""")
        assertTrue(battle.bondMax.isEmpty())
        val run = Json.decodeFromString<RunGains>("""{"bond":{"10":5}}""")
        assertTrue(run.bondMax.isEmpty())
    }

    @Test fun displayDropsZeroRowsAndMarksMax() {
        val battle = BattleGains(1, bond = mapOf(10L to 0L, 20L to 2455L), bondMax = setOf(10L, 20L))
        assertEquals(listOf(BondDisplay(20, 2455, true)), battle.displayBond())
        val run = RunGains(bond = mapOf(1L to 0L, 2L to 100L, 3L to 300L), bondMax = setOf(3L))
        assertEquals(listOf(BondDisplay(3, 300, true), BondDisplay(2, 100, false)), run.displayBond())
    }
}
