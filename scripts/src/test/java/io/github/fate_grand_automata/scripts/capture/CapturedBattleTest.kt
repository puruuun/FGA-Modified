package io.github.fate_grand_automata.scripts.capture

import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import kotlinx.serialization.json.*
import kotlin.test.*

/** Handwritten fictional ids and values, matching only the observed KR field structure. */
class CapturedBattleTest {
    private fun body(success: String, updated: String = "{}") = Json.parseToJsonElement(
        """{"response":[{"success":$success}],"cache":{"updated":$updated}}""").jsonObject

    private fun state(tables: String) = AccountState(Json.parseToJsonElement(tables).jsonObject.mapValues { it.value.jsonArray },
        JsonArray(emptyList()), snapshotTime = 0, lastUpdateTime = 0)

    @Test fun `only type two drops are summed by public item id`() {
        val battle = CapturedBattle.parse(body("""{"resultDropInfos":[
            {"type":2,"objectId":6516,"num":2},{"type":2,"objectId":6516,"num":3},
            {"type":3,"objectId":6516,"num":99},{"type":2,"objectId":6503,"num":1}]}"""), null)!!
        assertEquals(mapOf(6516L to 5L, 6503L to 1L), battle.drops)
        assertNull(battle.bond)
        assertEquals(emptyMap(), CapturedBattle.parse(body("""{"resultDropInfos":[]}"""), null)!!.drops)
    }

    @Test fun `result local ids resolve through setup and preserve own deck order excluding support`() {
        val previous = state("""{"battle":[{"battleInfo":{"myDeck":{"svts":[
            {"id":11,"userSvtId":101,"svtId":901,"initPos":1,"isFollowerSvt":false,"followerType":0},
            {"id":12,"userSvtId":102,"svtId":902,"initPos":2,"isFollowerSvt":true,"followerType":1},
            {"id":13,"userSvtId":103,"svtId":903,"initPos":3,"isFollowerSvt":false,"followerType":0}]}}}]}""")
        val result = CapturedBattle.parse(body("""{"myDeck":{"svts":[{"id":11,"userSvtId":101},{"id":13,"userSvtId":103}]},
            "oldUserSvtCollection":[{"svtId":903,"friendship":100,"friendshipRank":9,"friendshipExceedCount":0},
            {"svtId":901,"friendship":200,"friendshipRank":12,"friendshipExceedCount":2}]}""",
            """{"userSvtCollection":[{"svtId":901,"friendship":200,"friendshipRank":12,"friendshipExceedCount":2},
            {"svtId":903,"friendship":150,"friendshipRank":10,"friendshipExceedCount":1}]}"""), previous)!!
        assertEquals(listOf(CapturedBond(1, 901, 12, 12, 12, 0), CapturedBond(2, 903, 9, 10, 11, 50)), result.bond)
        assertEquals(listOf(1, 2), CaptureBondStop.check(result, emptySet()))
    }

    @Test fun `support flags are excluded and missing rows never shift another slot`() {
        val result = CapturedBattle.parse(body("""{"myDeck":{"svts":[
            {"svtId":1},{"svtId":2,"isFollowerSvt":true},{"svtId":3,"followerType":1},
            {"svtId":4,"npcFollowerSvtId":9},{"svtId":5},null,{"svtId":7}]}}"""), null)!!
        assertEquals(listOf(1L, 5L, null, 7L), result.bond!!.map { it.svtId })
        assertEquals(listOf(1, 2, 3, 4), result.bond.map { it.slot })
        assertNull(CaptureBondStop.check(result, emptySet()))
    }

    @Test fun `snapshot collection is fallback and response old values win`() {
        val previous = state("""{"userSvt":[{"id":101,"svtId":901}],"userSvtCollection":[
            {"svtId":901,"friendship":10,"friendshipRank":8,"friendshipExceedCount":0}]}""")
        val success = """{"myDeck":{"svts":[{"id":11,"userSvtId":101}]},"oldUserSvtCollection":[
            {"svtId":901,"friendship":20,"friendshipRank":9,"friendshipExceedCount":0}]}"""
        val result = CapturedBattle.parse(body(success,"""{"userSvtCollection":[
            {"svtId":901,"friendship":30,"friendshipRank":10,"friendshipExceedCount":0}]}"""), previous)!!
        assertEquals(CapturedBond(1, 901, 9, 10, 10, 10), result.bond!!.single())
    }

    @Test fun `missing malformed and overflowing sections are unknown without throwing`() {
        for (json in listOf("{}", """{"response":{}}""", """{"response":[{"success":null}]}"""))
            assertNull(CapturedBattle.parse(Json.parseToJsonElement(json).jsonObject, null))
        val empty = CapturedBattle.parse(body("{}"), null)!!
        assertNull(empty.drops); assertNull(empty.bond)
        for (drops in listOf("null", "{}", "[null]", "[{\"type\":2,\"objectId\":1,\"num\":-1}]",
            "[{\"type\":2,\"objectId\":1,\"num\":9223372036854775807},{\"type\":2,\"objectId\":1,\"num\":1}]")) {
            assertNull(CapturedBattle.parse(body("""{"resultDropInfos":$drops}"""), null)!!.drops)
        }
        val unknown = CapturedBattle.parse(body("""{"myDeck":{"svts":[{"id":901}]}}"""), null)!!.bond!!.single()
        assertNull(unknown.svtId) // Battle-local id must never become a public servant id.
        assertNull(unknown.cap); assertNull(unknown.gained)
    }

    @Test fun `all materials have distinct public item ids`() {
        assertEquals(MaterialEnum.entries.toSet(), MaterialItemIds.ids.keys)
        assertEquals(MaterialEnum.entries.size, MaterialItemIds.ids.values.toSet().size)
        assertEquals(6516L, MaterialItemIds.ids[MaterialEnum.Bone])
        assertEquals(6207L, MaterialItemIds.ids[MaterialEnum.SkillGoldBerserker])
    }

    @Test fun `cap and ten plus rank ups stop only watched result slots`() {
        val battle = CapturedBattle(null, listOf(
            CapturedBond(1, 901, 10, 10, 11, 5), // Ten with room to grow is not MAX.
            CapturedBond(2, 902, 9, 10, 11, 5),
            CapturedBond(3, 903, 11, 12, 13, 5),
            CapturedBond(4, 904, 12, 12, 12, 0),
            CapturedBond(5, 905, 8, 9, 10, 5)))
        assertEquals(listOf(2, 3, 4), CaptureBondStop.check(battle, emptySet()))
        assertEquals(listOf(3), CaptureBondStop.check(battle, setOf(2, 4)))
        assertEquals(emptyList(), CaptureBondStop.check(battle, setOf(2, 3, 4)))
        assertNull(CaptureBondStop.check(CapturedBattle(null, null), emptySet()))
        assertEquals(emptyList(), CaptureBondStop.check(CapturedBattle(null, null), (1..5).toSet()))
    }
}
