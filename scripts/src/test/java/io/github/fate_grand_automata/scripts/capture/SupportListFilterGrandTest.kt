package io.github.fate_grand_automata.scripts.capture

import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The grand conditions of #704 on fictional data in the shape of a real JP follower/list (structure read 2026-11-25):
 * `userSvtGrandHash` entries without supportDeckId and classId, `equipTarget1..3`, `script.equipTarget2SkillChange`,
 * and `userClassBoardInfo` with boards 1 to 9 and the grand boards 10001 to 10009.
 */
class SupportListFilterGrandTest {
    private val limitKeys = listOf("dispLimitCount", "imageLimitCount", "commandCardLimitCount", "iconLimitCount", "portraitLimitCount")

    private fun equip(ce: Long, limit: Int = 4, lv: Int = 100) = if (ce == 0L) {
        """{"userId":0,"userSvtId":0,"svtId":0,"limitCount":0,"lv":0,"exp":0,"hp":0,"atk":0,"skillId1":0,"skillLv1":0,"skillId2":0,"skillLv2":0,"skillId3":0,"skillLv3":0,"addSkills":[],"updatedAt":0}"""
    } else {
        """{"userId":9,"userSvtId":8,"svtId":$ce,"limitCount":$limit,"lv":$lv,"exp":7,"hp":1,"atk":2,"skillId1":3,"skillLv1":4,"skillId2":0,"skillLv2":0,"skillId3":0,"skillLv3":0,"addSkills":[],"updatedAt":12}"""
    }

    /** A filled slot of userSvtLeaderHash in the real key order. */
    private fun realSlot(svt: Long, deck: Int, cls: Int, ce: Long = 0, user: String = "42"): String {
        val parts = mutableListOf<String>()
        parts += """"afterTransformSvtInfo":null"""
        parts += limitKeys.map { """"$it":4""" }
        parts += """"battleVoice":2,"grandSvt":1,"transformVal":0"""
        parts += """"randomLimitCountTargets":[1,2],"classPassive":[3]"""
        parts += """"equipTarget1":${equip(ce)}"""
        parts += """"commandCode":[],"commandCardParam":[1,2,3,4,5],"appendPassiveSkill":[{"skillId":1,"skillLv":10}],"script":{},"eventSvtPoint":5,"imagePartsGroupInfo":[]"""
        parts += """"userId":555,"supportDeckId":$deck,"classId":$cls,"userSvtId":$user,"svtId":$svt,"limitCount":4,"lv":90,"exp":10,"hp":11,"atk":12,"adjustHp":1,"adjustAtk":2"""
        parts += """"skillId1":1,"skillId2":2,"skillId3":3,"skillLv1":10,"skillLv2":10,"skillLv3":10,"treasureDeviceId":5,"treasureDeviceLv":5,"exceedCount":1,"updatedAt":1708036772"""
        return "{" + parts.joinToString(",") + "}"
    }

    private fun serverEmptySlot(deck: Int, cls: Int): String {
        val parts = mutableListOf<String>()
        parts += """"afterTransformSvtInfo":null"""
        parts += limitKeys.map { """"$it":1""" }
        parts += """"battleVoice":0,"grandSvt":0,"transformVal":0"""
        parts += """"randomLimitCountTargets":[],"classPassive":[]"""
        parts += """"equipTarget1":${equip(0)}"""
        parts += """"commandCode":[],"commandCardParam":[0,0,0,0,0],"appendPassiveSkill":[],"script":{},"eventSvtPoint":0,"imagePartsGroupInfo":[]"""
        parts += """"userId":555,"supportDeckId":$deck,"classId":$cls,"userSvtId":0,"svtId":0,"limitCount":0,"lv":0,"exp":0,"hp":0,"atk":0,"adjustHp":0,"adjustAtk":0"""
        parts += """"skillId1":0,"skillId2":0,"skillId3":0,"skillLv1":0,"skillLv2":0,"skillLv3":0,"treasureDeviceId":0,"treasureDeviceLv":0,"exceedCount":0,"updatedAt":1708036772"""
        return "{" + parts.joinToString(",") + "}"
    }

    /**
     * One entry of userSvtGrandHash with the keys of a real JP entry. [change] is `script.equipTarget2SkillChange`
     * (null: the key is absent, as in 102 of 172 real entries); the real responses only ever had 1 there.
     */
    private fun realGrand(
        svt: Long, ce1: Long = 0, bond: Long = 0, ce3: Long = 0, change: Int? = null,
        ce1Limit: Int = 4, ce3Limit: Int = 4, graph: Long = 10001, user: String = "43",
    ): String {
        val parts = mutableListOf<String>()
        parts += """"afterTransformSvtInfo":null"""
        parts += limitKeys.map { """"$it":4""" }
        parts += """"battleVoice":0,"grandSvt":1,"transformVal":0"""
        parts += """"randomLimitCountTargets":[],"classPassive":[3,4]"""
        parts += """"equipTarget1":${equip(ce1, ce1Limit)},"equipTarget2":${equip(bond, lv = 80)},"equipTarget3":${equip(ce3, ce3Limit)}"""
        parts += """"commandCode":[],"commandCardParam":[1,2,3,4,5],"appendPassiveSkill":[{"skillId":1,"skillLv":10}]"""
        parts += """"script":{${change?.let { """"equipTarget2SkillChange":$it,""" } ?: ""}"isSetClassBoardBaseIds":[]},"eventSvtPoint":0,"imagePartsGroupInfo":[]"""
        parts += """"grandGraphId":$graph,"isSetClassBoardBaseIds":[]"""
        parts += """"userId":555,"userSvtId":$user,"svtId":$svt,"limitCount":4,"lv":120,"exp":10,"hp":11,"atk":12,"adjustHp":1,"adjustAtk":2"""
        parts += """"skillId1":1,"skillId2":2,"skillId3":3,"skillLv1":10,"skillLv2":10,"skillLv3":10,"treasureDeviceId":5,"treasureDeviceLv":5,"exceedCount":1,"updatedAt":1708036772"""
        return "{" + parts.joinToString(",") + "}"
    }

    private fun realHash(set: Map<Pair<Int, Int>, String>) =
        (1..3).flatMap { d -> (0..8).map { c -> set[d to c] ?: serverEmptySlot(d, c) } }

    private fun follower(
        id: String, type: Int = 1, main: Map<Pair<Int, Int>, String> = emptyMap(), grand: List<String> = emptyList(), boards: String? = null,
        event: Map<Pair<Int, Int>, String> = emptyMap(),
    ) = """{"type":$type,"userId":"$id","mainSupportDeckIds":[1,2,3],"eventSupportDeckIds":[4],"userSvtLeaderHash":[${realHash(main).joinToString(",")}],""" +
        """"eventUserSvtLeaderHash":[${if (event.isEmpty()) "" else realHash(event).joinToString(",")}],""" +
        """"userSvtGrandHash":[${grand.joinToString(",")}]${boards?.let { ""","userClassBoardInfo":$it""" }.orEmpty()}}"""

    private fun body(vararg followers: String) =
        """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"userId":"me","followerInfo":[${followers.joinToString(",")}]}]},"serverTime":1}}"""

    private fun kept(text: String, criteria: SupportListCriteria): List<String>? {
        val r = SupportListFilter.apply(text.toByteArray(), null, criteria) ?: return null
        val root = Json.parseToJsonElement(String(r.wire)).jsonObject
        return infos(root).map { it.jsonObject["userId"]!!.jsonPrimitive.content }
    }

    private fun infos(root: JsonObject) =
        root["cache"]!!.jsonObject["updated"]!!.jsonObject["userFollower"]!!.jsonArray[0].jsonObject["followerInfo"]!!.jsonArray

    /**
     * The kept followers are exactly the keys of [expected], and each one's grand list holds the received entries at the
     * given positions, as the very parsed objects (#720); a list that keeps every entry is the parsed element itself.
     */
    private fun assertGrandEntries(text: String, criteria: SupportListCriteria, expected: Map<String, List<Int>>) {
        val root = Json.parseToJsonElement(text).jsonObject
        val (filtered, _) = assertNotNull(SupportListFilter.filter(root, criteria))
        val before = infos(root).associateBy { it.jsonObject["userId"]!!.jsonPrimitive.content }
        val after = infos(filtered)
        assertEquals(expected.keys.toList(), after.map { it.jsonObject["userId"]!!.jsonPrimitive.content })
        for (info in after) {
            val id = info.jsonObject["userId"]!!.jsonPrimitive.content
            val was = before[id]!!.jsonObject["userSvtGrandHash"]!!.jsonArray
            val now = info.jsonObject["userSvtGrandHash"]!!.jsonArray
            val positions = expected.getValue(id)
            assertEquals(positions.size, now.size, id)
            positions.forEachIndexed { i, at -> assertSame(was[at], now[i], "$id $i") }
            if (positions.size == was.size) assertSame(was, now, id)
        }
    }

    private val grandOn = SupportListCriteria(setOf(100L), grandServant = true)

    @Test fun grandServantMatchesOnlyGrandEntries() {
        val text = body(
            follower("normalOnly", main = mapOf((1 to 1) to realSlot(100, 1, 1))),
            follower("grand", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(999), realGrand(100))),
            follower("otherGrand", grand = listOf(realGrand(999))),
        )
        assertEquals(listOf("grand"), kept(text, grandOn))
        // Off: the normal slots match as before.
        assertEquals(listOf("normalOnly", "grand"), kept(text, grandOn.copy(grandServant = false)))
        assertGrandEntries(text, grandOn, mapOf("grand" to listOf(1)))
    }

    @Test fun withGrandServantOnTheNormalSlotsOfAKeptFollowerAreBlankedEvenForTheChosenServant() {
        val text = body(follower("grand", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(100))))
        val r = assertNotNull(SupportListFilter.apply(text.toByteArray(), null, grandOn))
        val out = infos(Json.parseToJsonElement(String(r.wire)).jsonObject)[0].jsonObject
        assertEquals(Json.parseToJsonElement(serverEmptySlot(1, 1)), out["userSvtLeaderHash"]!!.jsonArray[1])
        assertEquals(SupportListCounts(1, 1, 2, 1), r.counts)
    }

    @Test fun aKrShapedListWithAnEmptyGrandListMatchesNothingWithGrandServantOn() {
        val text = body(
            follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1))),
            follower("b", type = 2, main = mapOf((2 to 3) to realSlot(100, 2, 3))),
        )
        // Nobody matches, so only the first stays, with no servant to show (#740).
        assertEquals(listOf("a"), kept(text, grandOn))
        // Off: both match and nothing changes.
        assertNull(kept(text, grandOn.copy(grandServant = false)))
    }

    /** Boards 1 to 9 with the classes they release on JP, and the grand boards 10001..10009 with [grandSquares] each. */
    private fun jpBoards(grandSquares: Map<Int, Int>, omitGrand: Set<Int> = emptySet(), released: Map<Int, List<Int>> = JP_RELEASED): String {
        val normal = released.map { (base, classes) ->
            """{"classBoardBaseId":$base,"squareIds":[${(1..62).joinToString(",")}],"releasedClassIds":[${classes.joinToString(",")}]}"""
        }
        val grand = (1..9).filter { it !in omitGrand }.map { n ->
            val squares = grandSquares[n] ?: 0
            """{"classBoardBaseId":${10000 + n},"squareIds":[${(1..squares).joinToString(",") { "${n * 100 + it}" }}],"releasedClassIds":[${released[n].orEmpty().joinToString(",") { "${10000 + it}" }}]}"""
        }
        return (normal + grand).joinToString(",", "[", "]")
    }

    private fun grand16(classes: Map<Long, Int>) = SupportListCriteria(classes.keys, grandServant = true, grandScore16 = true, servantClassIds = classes)

    @Test fun grandScoreNeedsTheCompleteGrandBoardOfTheServantsClassBoard() {
        // Class to the board that releases it: saber..berserker 1..7, Extra I 8 and Extra II 9 (Atlas, #704).
        val boardOf = (1..7).associateWith { it } + listOf(8, 9, 11, 23).associateWith { 8 } + listOf(10, 25, 28, 33, 38, 40).associateWith { 9 }
        for ((cls, board) in boardOf) {
            val svt = 1000L + cls
            val c = grand16(mapOf(svt to cls))
            val text = body(
                follower("complete", grand = listOf(realGrand(svt)), boards = jpBoards(mapOf(board to 16))),
                follower("otherBoardsComplete", grand = listOf(realGrand(svt)), boards = jpBoards((1..9).filter { it != board }.associateWith { 16 } + (board to 12))),
                follower("fifteen", grand = listOf(realGrand(svt)), boards = jpBoards(mapOf(board to 15))),
                follower("missing", grand = listOf(realGrand(svt)), boards = jpBoards((1..9).associateWith { 16 }, omitGrand = setOf(board))),
                follower("normalSlot", main = mapOf((1 to 1) to realSlot(svt, 1, 1)), boards = jpBoards(mapOf(board to 16))),
            )
            assertEquals(listOf("complete"), kept(text, c), "class $cls")
        }
    }

    @Test fun grandScoreWithoutBoardsMatchesNothing() {
        val c = grand16(mapOf(125L to 25))
        val text = body(
            follower("complete", grand = listOf(realGrand(125)), boards = jpBoards(mapOf(9 to 16))),
            follower("noBoards", grand = listOf(realGrand(125))),
            follower("nullBoards", grand = listOf(realGrand(125)), boards = "null"),
        )
        assertEquals(listOf("complete"), kept(text, c))
    }

    @Test fun grandScoreFindsTheGrandBoardByTheFixedTableNotByReleasedClassIds() {
        // As 5 of 18 JP followers: board 9 lists only alter ego, while the grand board 10009 is complete.
        val onlyAlterEgo = JP_RELEASED + (9 to listOf(10))
        val c = grand16(mapOf(125L to 25))
        val text = body(
            follower("foreigner", grand = listOf(realGrand(125)), boards = jpBoards(mapOf(9 to 16), released = onlyAlterEgo)),
            follower("incomplete", grand = listOf(realGrand(125)), boards = jpBoards(mapOf(9 to 15), released = onlyAlterEgo)),
        )
        assertEquals(listOf("foreigner"), kept(text, c))
        // A board that lists the class elsewhere does not move it.
        val moved = JP_RELEASED + (1 to listOf(1, 25)) + (9 to listOf(10))
        // Nobody matches: the follower stays without servants (#740).
        assertEquals(listOf("moved"), kept(body(follower("moved", grand = listOf(realGrand(125)), boards = jpBoards(mapOf(1 to 16), released = moved))), c))
        val r = assertNotNull(SupportListFilter.apply(body(follower("moved", grand = listOf(realGrand(125)), boards = jpBoards(mapOf(1 to 16), released = moved))).toByteArray(), null, c))
        assertEquals(SupportListCounts(1, 0, 1, 0, placeholderKept = true), r.counts)
    }

    @Test fun grandScoreDoesNotReadTheClassBoards() {
        val c = grand16(mapOf(100L to 1))
        val grandOnly = """[{"classBoardBaseId":10001,"squareIds":[${(1..16).joinToString(",")}],"releasedClassIds":[10001]}]"""
        val oddNormal = """[{"classBoardBaseId":1,"squareIds":"odd","releasedClassIds":null},""" + grandOnly.removePrefix("[")
        val text = body(
            follower("noNormalBoards", grand = listOf(realGrand(100)), boards = grandOnly),
            follower("oddNormalBoard", grand = listOf(realGrand(100)), boards = oddNormal),
            follower("gone", grand = listOf(realGrand(999)), boards = grandOnly),
        )
        assertEquals(listOf("noNormalBoards", "oddNormalBoard"), kept(text, c))
        // With +62 on as well, the odd normal board is read again and the response passes unchanged.
        assertNull(kept(text, c.copy(classScore62 = true)))
    }

    @Test fun theGrandBoardTableCoversEveryClassTheFilterKnows() {
        val expected = (1..7).associateWith { it } + listOf(8, 9, 11, 23).associateWith { 8 } + listOf(10, 25, 28, 33, 38, 40).associateWith { 9 }
        for ((cls, board) in expected) assertEquals(board, SupportListFilter.grandBoardOf(cls), "class $cls")
        assertNull(SupportListFilter.grandBoardOf(39))
        assertNull(SupportListFilter.grandBoardOf(0))
    }

    @Test fun grandScoreForAServantWithoutAKnownClassMatchesNothing() {
        val c = SupportListCriteria(setOf(100L, 200L), grandServant = true, grandScore16 = true, servantClassIds = mapOf(100L to 1))
        val text = body(
            follower("known", grand = listOf(realGrand(100)), boards = jpBoards(mapOf(1 to 16))),
            follower("unknown", grand = listOf(realGrand(200)), boards = jpBoards((1..9).associateWith { 16 })),
        )
        assertEquals(listOf("known"), kept(text, c))
    }

    @Test fun grandBoardsOfAnOddShapeLeaveTheWholeResponseUnchanged() {
        val c = grand16(mapOf(100L to 1))
        val good = jpBoards(mapOf(1 to 16))
        val odd = listOf(
            "squareIdsNotAnArray" to good.replace(""""classBoardBaseId":10002,"squareIds":[]""", """"classBoardBaseId":10002,"squareIds":3"""),
            "squareIdAString" to good.replace(""""classBoardBaseId":10002,"squareIds":[]""", """"classBoardBaseId":10002,"squareIds":["1"]"""),
            "baseIdOutsideTheGrandRange" to good.replace(""""classBoardBaseId":10002,""", """"classBoardBaseId":10010,"""),
            "anotherLargeBaseId" to good.replace(""""classBoardBaseId":10002,""", """"classBoardBaseId":1700000002,"""),
            "baseIdAString" to good.replace(""""classBoardBaseId":10002,""", """"classBoardBaseId":"10002","""),
        )
        val base = arrayOf(
            follower("ok", grand = listOf(realGrand(100)), boards = good),
            follower("gone", grand = listOf(realGrand(999)), boards = good),
        )
        assertNotNull(kept(body(*base), c))
        for ((name, boards) in odd) {
            assertTrue(boards != good, name)
            assertNull(kept(body(*base, follower("odd", grand = listOf(realGrand(100)), boards = boards)), c), name)
            // Without the grand score the large boards are not looked at, as before.
            assertEquals(listOf("ok", "odd"), kept(body(*base, follower("odd", grand = listOf(realGrand(100)), boards = boards)), c.copy(grandScore16 = false)), name)
        }
    }

    @Test fun grandScoreAndClassScoreTogether() {
        val c = grand16(mapOf(100L to 1)).copy(classScore62 = true)
        val partialSaber = JP_RELEASED.map { (base, classes) ->
            val squares = if (base == 1) 30 else 62
            """{"classBoardBaseId":$base,"squareIds":[${(1..squares).joinToString(",")}],"releasedClassIds":[${classes.joinToString(",")}]}"""
        } + """{"classBoardBaseId":10001,"squareIds":[${(1..16).joinToString(",")}],"releasedClassIds":[10001]}"""
        val text = body(
            follower("both", grand = listOf(realGrand(100)), boards = jpBoards(mapOf(1 to 16))),
            follower("only16", grand = listOf(realGrand(100)), boards = partialSaber.joinToString(",", "[", "]")),
        )
        assertEquals(listOf("both"), kept(text, c))
    }

    private val ceCriteria = SupportListCriteria(setOf(100L), setOf(7L, 8L), grandServant = true)

    @Test fun bondCeEffectReadsEquipTarget2AndItsSkillChange() {
        val text = body(
            follower("np", grand = listOf(realGrand(100, ce1 = 7, bond = 50, change = 1))),
            follower("default", grand = listOf(realGrand(100, ce1 = 7, bond = 50))),
            follower("noBondCe", grand = listOf(realGrand(100, ce1 = 7, bond = 0, change = 1))),
            follower("noBondCeNoChange", grand = listOf(realGrand(100, ce1 = 7))),
            follower("otherCe", grand = listOf(realGrand(100, ce1 = 9, bond = 50, change = 1))),
            follower("normal", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7))),
        )
        assertEquals(listOf("np"), kept(text, ceCriteria.copy(bondCeEffect = BondCEEffectEnum.NP)))
        assertEquals(listOf("default"), kept(text, ceCriteria.copy(bondCeEffect = BondCEEffectEnum.Default)))
        assertEquals(listOf("np", "default", "noBondCe", "noBondCeNoChange"), kept(text, ceCriteria))
        assertGrandEntries(text, ceCriteria.copy(bondCeEffect = BondCEEffectEnum.NP), mapOf("np" to listOf(0)))
    }

    @Test fun aSkillChangeValueOtherThanOneLeavesTheWholeResponseUnchangedWhenTheEffectIsAsked() {
        val base = arrayOf(
            follower("np", grand = listOf(realGrand(100, ce1 = 7, bond = 50, change = 1))),
            follower("default", grand = listOf(realGrand(100, ce1 = 7, bond = 50))),
        )
        for (value in listOf(0, 2)) {
            val text = body(*base, follower("odd", grand = listOf(realGrand(100, ce1 = 7, bond = 50, change = value))))
            assertNull(kept(text, ceCriteria.copy(bondCeEffect = BondCEEffectEnum.NP)), "value $value")
            assertNull(kept(text, ceCriteria.copy(bondCeEffect = BondCEEffectEnum.Default)), "value $value")
            // Ignore does not read it; the follower whose CE does not match is still left out.
            assertEquals(listOf("np", "default"), kept(body(*base, follower("odd", grand = listOf(realGrand(100, ce1 = 9, bond = 50, change = value)))), ceCriteria), "value $value")
        }
        assertEquals(listOf("np"), kept(body(*base), ceCriteria.copy(bondCeEffect = BondCEEffectEnum.NP)))
    }

    @Test fun aNormalSlotNeverMeetsTheGrandCeConditions() {
        // As the script's screen reading: a row without the grand label fails the bond and both-slot conditions.
        val c = SupportListCriteria(ceIds = setOf(7L))
        val text = body(
            follower("normal", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7))),
            follower("grand", grand = listOf(realGrand(100, ce1 = 7, ce3 = 7, bond = 50, change = 1))),
            follower("gone", grand = listOf(realGrand(100, ce1 = 9))),
        )
        assertEquals(listOf("normal", "grand"), kept(text, c))
        assertEquals(listOf("grand"), kept(text, c.copy(bothCeSlots = true)))
        assertEquals(listOf("grand"), kept(text, c.copy(bondCeEffect = BondCEEffectEnum.NP)))
    }

    @Test fun withoutAPreferredCeTheGrandCeConditionsAreNotRead() {
        val c = SupportListCriteria(setOf(100L), grandServant = true, bondCeEffect = BondCEEffectEnum.NP, bothCeSlots = true)
        val text = body(
            follower("a", grand = listOf(realGrand(100))),
            follower("b", grand = listOf(realGrand(999))),
        )
        assertEquals(listOf("a"), kept(text, c))
    }

    @Test fun anOddBondCeShapeLeavesTheWholeResponseUnchangedOnlyWhenTheEffectIsAsked() {
        val ok = realGrand(100, ce1 = 7, bond = 50, change = 1)
        val odd = listOf(
            "bondNotAnObject" to ok.replace(""""equipTarget2":${equip(50, lv = 80)}""", """"equipTarget2":"x""""),
            "bondSvtIdAString" to ok.replace(""""equipTarget2":${equip(50, lv = 80)}""", """"equipTarget2":{"svtId":"50"}"""),
            "scriptNotAnObject" to ok.replace(""""script":{"equipTarget2SkillChange":1,"isSetClassBoardBaseIds":[]}""", """"script":"x""""),
            "changeAString" to ok.replace(""""equipTarget2SkillChange":1""", """"equipTarget2SkillChange":"1""""),
            "changeNotAnInteger" to ok.replace(""""equipTarget2SkillChange":1""", """"equipTarget2SkillChange":1.5"""),
        )
        val base = arrayOf(follower("ok", grand = listOf(ok)), follower("gone", grand = listOf(realGrand(999))))
        val np = ceCriteria.copy(bondCeEffect = BondCEEffectEnum.NP)
        assertEquals(listOf("ok"), kept(body(*base), np))
        for ((name, entry) in odd) {
            assertTrue(entry != ok, name)
            assertNull(kept(body(*base, follower("odd", grand = listOf(entry))), np), name)
            assertNull(kept(body(*base, follower("odd", grand = listOf(entry))), np.copy(bondCeEffect = BondCEEffectEnum.Default)), name)
        }
        // Ignore does not look at the bond CE.
        assertEquals(listOf("ok", "odd"), kept(body(*base, follower("odd", grand = listOf(odd[2].second))), ceCriteria))
    }

    @Test fun bothCeSlotsNeedAChosenCeInEquipTarget1AndEquipTarget3() {
        val c = ceCriteria.copy(bothCeSlots = true)
        val text = body(
            follower("both", grand = listOf(realGrand(100, ce1 = 7, ce3 = 8))),
            follower("sameTwice", grand = listOf(realGrand(100, ce1 = 7, ce3 = 7))),
            follower("only1", grand = listOf(realGrand(100, ce1 = 7, ce3 = 9))),
            follower("only3", grand = listOf(realGrand(100, ce1 = 9, ce3 = 8))),
            // The bond CE is never one of the two slots.
            follower("bondAndOne", grand = listOf(realGrand(100, ce1 = 7, bond = 8))),
            follower("gone", grand = listOf(realGrand(999, ce1 = 7, ce3 = 8))),
        )
        assertEquals(listOf("both", "sameTwice"), kept(text, c))
        assertEquals(listOf("both", "sameTwice", "only1", "only3", "bondAndOne"), kept(text, ceCriteria))
        assertGrandEntries(text, c, mapOf("both" to listOf(0), "sameTwice" to listOf(0)))
    }

    @Test fun bothCeSlotsWithMlbNeedEachMatchedCeAtLimitCount4() {
        val c = ceCriteria.copy(bothCeSlots = true, ceMlb = true)
        val text = body(
            follower("bothMlb", grand = listOf(realGrand(100, ce1 = 7, ce3 = 8))),
            follower("oneNotMlb", grand = listOf(realGrand(100, ce1 = 7, ce3 = 8, ce3Limit = 3))),
            follower("gone", grand = listOf(realGrand(999))),
        )
        assertEquals(listOf("bothMlb"), kept(text, c))
        assertEquals(listOf("bothMlb", "oneNotMlb"), kept(text, c.copy(bothCeSlots = false)))
    }

    @Test fun everyGrandConditionTogetherKeepsOnlyAFollowerWithOneEntryMeetingAll() {
        val c = SupportListCriteria(
            setOf(100L), setOf(7L), ceMlb = true, grandServant = true, grandScore16 = true,
            bondCeEffect = BondCEEffectEnum.NP, bothCeSlots = true, servantClassIds = mapOf(100L to 2),
        )
        val boards = jpBoards(mapOf(2 to 16))
        val all = realGrand(100, ce1 = 7, ce3 = 7, bond = 50, change = 1)
        val text = body(
            follower("all", grand = listOf(realGrand(999), all), boards = boards),
            follower("noNp", grand = listOf(realGrand(100, ce1 = 7, ce3 = 7, bond = 50)), boards = boards),
            follower("split", grand = listOf(realGrand(100, ce1 = 7, bond = 50, change = 1), realGrand(100, ce3 = 7)), boards = boards),
            follower("no16", grand = listOf(all), boards = jpBoards(mapOf(2 to 15))),
        )
        assertEquals(listOf("all"), kept(text, c))
        assertGrandEntries(text, c, mapOf("all" to listOf(1)))
    }

    @Test fun grandConditionsCountAsConditions() {
        assertTrue(SupportListCriteria(grandServant = true).hasConditions)
        assertTrue(SupportListCriteria(grandScore16 = true).hasConditions)
        assertTrue(SupportListCriteria(bondCeEffect = BondCEEffectEnum.Default).hasConditions)
        assertTrue(SupportListCriteria(bothCeSlots = true).hasConditions)
    }

    // #720: in a kept follower, the grand entries that do not match are removed.

    private fun keyedFollower(id: String, main: Map<Pair<Int, Int>, String>, grand: Map<String, String>) =
        follower(id, main = main).replace(""""userSvtGrandHash":[]""", """"userSvtGrandHash":${grand.entries.joinToString(",", "{", "}") { """"${it.key}":${it.value}""" }}""")

    private fun firstOut(text: String, criteria: SupportListCriteria): Pair<JsonObject, JsonObject> {
        val root = Json.parseToJsonElement(text).jsonObject
        val (filtered, _) = assertNotNull(SupportListFilter.filter(root, criteria))
        return infos(root)[0].jsonObject to infos(filtered)[0].jsonObject
    }

    @Test fun nonMatchingGrandEntriesOfAnArrayAreRemovedKeepingTheOthersInOrderAsTheReceivedObjects() {
        val text = body(
            follower("a", grand = listOf(realGrand(100, graph = 10001), realGrand(999, graph = 10002), realGrand(100, graph = 10003), realGrand(998, graph = 10004))),
            follower("gone", grand = listOf(realGrand(997))),
        )
        assertGrandEntries(text, grandOn, mapOf("a" to listOf(0, 2)))
        val (before, after) = firstOut(text, grandOn)
        assertTrue(before["userSvtGrandHash"] !== after["userSvtGrandHash"])
        // The rest of the follower is as received.
        assertEquals(before.keys.toList(), after.keys.toList())
        for (key in before.keys - "userSvtGrandHash") assertEquals(before[key], after[key], key)
    }

    @Test fun nonMatchingGrandEntriesOfAKeyedContainerAreRemovedKeepingTheOtherKeysInOrder() {
        val grand = linkedMapOf("3" to realGrand(100, graph = 10003), "1" to realGrand(999), "7" to realGrand(998, graph = 10007), "2" to realGrand(100, graph = 10002))
        val text = body(keyedFollower("a", emptyMap(), grand), follower("gone"))
        val (before, after) = firstOut(text, grandOn)
        val was = before["userSvtGrandHash"]!!.jsonObject
        val now = after["userSvtGrandHash"]!!.jsonObject
        assertEquals(listOf("3", "2"), now.keys.toList())
        for (key in now.keys) assertSame(was[key], now[key], key)
    }

    @Test fun whenEveryGrandEntryMatchesTheContainerIsTheReceivedElement() {
        // The follower still changes: its normal slot of the chosen servant is blanked under the grand servant condition.
        val text = body(
            follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(100), realGrand(100, graph = 10002))),
            keyedFollower("k", mapOf((1 to 1) to realSlot(100, 1, 1)), linkedMapOf("1" to realGrand(100), "2" to realGrand(100, graph = 10002))),
        )
        val root = Json.parseToJsonElement(text).jsonObject
        val (filtered, _) = assertNotNull(SupportListFilter.filter(root, grandOn))
        for (i in 0..1) {
            val before = infos(root)[i].jsonObject
            val after = infos(filtered)[i].jsonObject
            assertTrue(before["userSvtLeaderHash"] != after["userSvtLeaderHash"])
            assertSame(before["userSvtGrandHash"], after["userSvtGrandHash"])
        }
    }

    @Test fun aFollowerKeptByANormalSlotWhoseGrandEntriesAllFailGetsAnEmptyContainerOfTheSameKind() {
        val c = SupportListCriteria(setOf(100L))
        val text = body(
            follower("array", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(999), realGrand(998, graph = 10002))),
            keyedFollower("keyed", mapOf((1 to 1) to realSlot(100, 1, 1)), linkedMapOf("1" to realGrand(999), "2" to realGrand(998, graph = 10002))),
        )
        val r = assertNotNull(SupportListFilter.apply(text.toByteArray(), null, c))
        val out = infos(Json.parseToJsonElement(String(r.wire)).jsonObject)
        assertEquals("[]", out[0].jsonObject["userSvtGrandHash"].toString())
        assertEquals("{}", out[1].jsonObject["userSvtGrandHash"].toString())
        // The normal slot that matched stays; nothing else of the follower changes.
        assertEquals(Json.parseToJsonElement(realSlot(100, 1, 1)), out[0].jsonObject["userSvtLeaderHash"]!!.jsonArray[1])
        assertEquals(SupportListCounts(2, 2, 6, 2), r.counts)
    }

    @Test fun aFollowerWithoutAnyMatchStillLeavesWithAllItsGrandEntries() {
        val text = body(
            follower("stays", grand = listOf(realGrand(100))),
            follower("gone", main = mapOf((1 to 1) to realSlot(999, 1, 1)), grand = listOf(realGrand(999), realGrand(998, graph = 10002))),
        )
        assertGrandEntries(text, grandOn, mapOf("stays" to listOf(0)))
        assertEquals(SupportListCounts(2, 1, 4, 1), assertNotNull(SupportListFilter.apply(text.toByteArray(), null, grandOn)).counts)
    }

    @Test fun nothingToRemoveAnywhereLeavesTheBytesAsReceived() {
        val text = body(
            follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(100), realGrand(100, graph = 10002))),
            follower("b", type = 2, grand = listOf(realGrand(100))),
        )
        assertNull(SupportListFilter.apply(text.toByteArray(), null, SupportListCriteria(setOf(100L))))
    }

    @Test fun criteriaWithoutAGrandConditionRemoveNonMatchingGrandEntriesToo() {
        val servantOnly = SupportListCriteria(setOf(100L))
        val text = body(
            follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(999), realGrand(100, graph = 10002))),
            follower("b", grand = listOf(realGrand(100, ce1 = 7), realGrand(100, ce1 = 9, graph = 10002))),
        )
        assertGrandEntries(text, servantOnly, mapOf("a" to listOf(1), "b" to listOf(0, 1)))
        // A preferred CE only: the grand entry without it goes, and "a" holds the CE nowhere.
        assertGrandEntries(text, SupportListCriteria(ceIds = setOf(7L)), mapOf("b" to listOf(0)))
    }

    @Test fun theLeaderHashKeepsTheServersBlankFormNextToRemovedGrandEntries() {
        val text = body(follower("a", main = mapOf((1 to 1) to realSlot(999, 1, 1), (2 to 3) to realSlot(998, 2, 3)), grand = listOf(realGrand(999), realGrand(100, graph = 10002))))
        val r = assertNotNull(SupportListFilter.apply(text.toByteArray(), null, grandOn))
        val out = infos(Json.parseToJsonElement(String(r.wire)).jsonObject)[0].jsonObject
        val leaders = out["userSvtLeaderHash"]!!.jsonArray
        assertEquals(Json.parseToJsonElement(serverEmptySlot(1, 1)), leaders[1])
        assertEquals(Json.parseToJsonElement(serverEmptySlot(2, 3)), leaders[9 + 3])
        // No grand entry is ever written in a blank form.
        val grand = out["userSvtGrandHash"]!!.jsonArray
        assertEquals(listOf(Json.parseToJsonElement(realGrand(100, graph = 10002))), grand.toList())
    }

    @Test fun anEmptyGrandEntryStaysAsReceivedAndIsNotCounted() {
        for (svt in listOf("0", "-1")) {
            val empty = realGrand(100).replace(""""svtId":100,"limitCount":4,"lv":120""", """"svtId":$svt,"limitCount":4,"lv":120""")
            assertTrue(empty != realGrand(100), svt)
            val text = body(follower("z", grand = listOf(realGrand(999), empty, realGrand(100, graph = 10002))), follower("gone", grand = listOf(empty)))
            assertGrandEntries(text, grandOn, mapOf("z" to listOf(1, 2)))
            assertEquals(SupportListCounts(2, 1, 2, 1), assertNotNull(SupportListFilter.apply(text.toByteArray(), null, grandOn)).counts, svt)
        }
    }

    @Test fun aGrandContainerOfAnotherKindLeavesTheWholeResponseUnchanged() {
        val base = arrayOf(follower("ok", grand = listOf(realGrand(100), realGrand(999, graph = 10002))), follower("gone", grand = listOf(realGrand(999))))
        for (odd in listOf("null", "5", "\"x\"", "true")) {
            val f = follower("odd", main = mapOf((1 to 1) to realSlot(100, 1, 1))).replace(""""userSvtGrandHash":[]""", """"userSvtGrandHash":$odd""")
            assertTrue(f.contains(""""userSvtGrandHash":$odd"""), odd)
            assertNull(SupportListFilter.apply(body(*base, f).toByteArray(), null, grandOn), odd)
            assertNull(SupportListFilter.apply(body(*base, f).toByteArray(), null, SupportListCriteria(setOf(100L))), odd)
        }
        // A follower without the key is read from its other hashes, as before.
        val absent = follower("absent", main = mapOf((1 to 1) to realSlot(100, 1, 1))).replace(""","userSvtGrandHash":[]""", "")
        assertTrue(!absent.contains("userSvtGrandHash"))
        assertEquals(listOf("ok", "absent"), kept(body(*base, absent), SupportListCriteria(setOf(100L))))
    }

    @Test fun aGrandEntryThatIsNotAnObjectLeavesTheWholeResponseUnchanged() {
        val base = arrayOf(follower("ok", grand = listOf(realGrand(100), realGrand(999, graph = 10002))), follower("gone", grand = listOf(realGrand(999))))
        for (odd in listOf("5", "null", "[]", "\"x\"")) {
            assertNull(SupportListFilter.apply(body(*base, follower("odd", grand = listOf(realGrand(100), odd))).toByteArray(), null, grandOn), odd)
            assertNull(SupportListFilter.apply(body(*base, keyedFollower("odd", emptyMap(), linkedMapOf("1" to realGrand(100), "2" to odd))).toByteArray(), null, grandOn), odd)
        }
    }

    @Test fun aKrShapedListBehavesAsBefore() {
        // KR sends userSvtGrandHash as an empty array in every follower.
        val c = SupportListCriteria(setOf(100L))
        val text = body(
            follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1), (2 to 2) to realSlot(999, 2, 2))),
            follower("b", type = 2, main = mapOf((1 to 3) to realSlot(999, 1, 3))),
        )
        val root = Json.parseToJsonElement(text).jsonObject
        val (filtered, counts) = assertNotNull(SupportListFilter.filter(root, c))
        assertEquals(1, infos(filtered).size)
        val before = infos(root)[0].jsonObject
        val after = infos(filtered)[0].jsonObject
        assertSame(before["userSvtGrandHash"], after["userSvtGrandHash"])
        assertEquals(Json.parseToJsonElement(serverEmptySlot(2, 2)), after["userSvtLeaderHash"]!!.jsonArray[9 + 2])
        assertEquals(SupportListCounts(2, 1, 3, 1), counts)
        assertNull(kept(body(follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)))), c))
    }

    // #720, planner decision: a grand entry of the same user servant as a kept leader or event slot is not removed.

    private val ceOnly = SupportListCriteria(setOf(100L), setOf(7L))

    @Test fun aGrandEntryOfTheUserServantOfAMatchingLeaderSlotStaysWhileOtherNonMatchingEntriesGo() {
        val text = body(
            follower(
                "a", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7, user = "501")),
                grand = listOf(
                    realGrand(100, ce1 = 9, user = "501"), realGrand(999, user = "502", graph = 10002),
                    realGrand(100, ce1 = 7, user = "503", graph = 10003), realGrand(100, ce1 = 9, user = "504", graph = 10004),
                ),
            ),
            follower("gone", main = mapOf((1 to 1) to realSlot(999, 1, 1))),
        )
        assertGrandEntries(text, ceOnly, mapOf("a" to listOf(0, 2)))
        // Before: 5 + 1 filled; after: the leader slot, the matching entry and the one it protects.
        assertEquals(SupportListCounts(2, 1, 6, 3), assertNotNull(SupportListFilter.apply(text.toByteArray(), null, ceOnly)).counts)
    }

    @Test fun aLeaderSlotThatDoesNotMatchProtectsNothing() {
        val text = body(
            follower(
                "a", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 8, user = "501")),
                grand = listOf(realGrand(100, ce1 = 9, user = "501"), realGrand(100, ce1 = 7, user = "503", graph = 10002)),
            ),
        )
        assertGrandEntries(text, ceOnly, mapOf("a" to listOf(1)))
    }

    @Test fun withGrandOnlyCriteriaTheBlankedLeaderSlotProtectsNothing() {
        val text = body(
            follower(
                "a", main = mapOf((1 to 1) to realSlot(100, 1, 1, user = "501")),
                grand = listOf(realGrand(999, user = "501"), realGrand(100, user = "502", graph = 10002)),
            ),
        )
        assertGrandEntries(text, grandOn, mapOf("a" to listOf(1)))
    }

    @Test fun aMatchingEventSlotProtectsTheGrandEntryOfItsUserServantToo() {
        val text = body(
            follower("a", event = mapOf((1 to 1) to realSlot(100, 1, 1, user = "601")), grand = listOf(realGrand(999, user = "601"), realGrand(998, user = "602", graph = 10002))),
            follower("b", event = mapOf((1 to 1) to realSlot(100, 1, 1, user = "701")), grand = listOf(realGrand(999, user = "701"))),
            follower("gone", grand = listOf(realGrand(999, user = "601"))),
        )
        assertGrandEntries(text, SupportListCriteria(setOf(100L)), mapOf("a" to listOf(0), "b" to listOf(0)))
    }

    @Test fun leaderSlotsAreDecidedBeforeTheGrandHashWhateverTheKeyOrder() {
        val grandFirst = """{"type":1,"userId":"o","userSvtGrandHash":[${realGrand(999, user = "501")},${realGrand(998, user = "502", graph = 10002)}],""" +
            """"userSvtLeaderHash":[${realSlot(100, 1, 1, user = "501")},${realSlot(997, 1, 2, user = "503")}],"eventUserSvtLeaderHash":[]}"""
        val text = body(grandFirst)
        assertGrandEntries(text, SupportListCriteria(setOf(100L)), mapOf("o" to listOf(0)))
        val (before, after) = firstOut(text, SupportListCriteria(setOf(100L)))
        assertEquals(before.keys.toList(), after.keys.toList())
    }

    @Test fun aMatchingLeaderSlotWithoutAnIntegerUserSvtIdLeavesTheResponseUnchangedOnlyWhenAGrandEntryWouldGo() {
        for (user in listOf("MISSING", "\"501\"", "1.5", "null", "{}")) {
            val slot = realSlot(100, 1, 1, ce = 7, user = user).replace(""""userSvtId":MISSING,""", "")
            val gone = follower("gone", main = mapOf((1 to 1) to realSlot(999, 1, 1)))
            val removing = follower("a", main = mapOf((1 to 1) to slot), grand = listOf(realGrand(100, ce1 = 9, user = "501")))
            assertNull(SupportListFilter.apply(body(removing, gone).toByteArray(), null, ceOnly), user)
            // Nothing to remove from the grand list: the filter runs.
            val keeping = follower("a", main = mapOf((1 to 1) to slot), grand = listOf(realGrand(100, ce1 = 7, user = "501")))
            assertEquals(listOf("a"), kept(body(keeping, gone), ceOnly), user)
            assertEquals(listOf("a"), kept(body(follower("a", main = mapOf((1 to 1) to slot)), gone), ceOnly), user)
        }
    }

    @Test fun aGrandEntryWithoutAnIntegerUserSvtIdLeavesTheResponseUnchangedOnlyWhenAKeptSlotCouldProtectIt() {
        for (user in listOf("MISSING", "\"501\"", "1.5", "null", "[]")) {
            val entry = realGrand(100, ce1 = 9, user = user).replace(""""userSvtId":MISSING,""", "")
            val gone = follower("gone", main = mapOf((1 to 1) to realSlot(999, 1, 1)))
            val withLeader = follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7, user = "501")), grand = listOf(entry))
            assertNull(SupportListFilter.apply(body(withLeader, gone).toByteArray(), null, ceOnly), user)
            // Kept by a grand entry only: no slot can protect it, so it goes.
            val grandOnly = follower("a", grand = listOf(entry, realGrand(100, ce1 = 7, user = "503", graph = 10002)))
            assertGrandEntries(body(grandOnly, gone), ceOnly, mapOf("a" to listOf(1)))
            // A matching entry is never looked at for its userSvtId.
            val matching = realGrand(100, ce1 = 7, user = user).replace(""""userSvtId":MISSING,""", "")
            assertGrandEntries(body(follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7, user = "501")), grand = listOf(matching)), gone), ceOnly, mapOf("a" to listOf(0)))
        }
    }

    // #740: never an empty follower list.

    @Test fun aStrictGrandConfigOnAKrShapedListKeepsTheFirstFollowerWithoutServants() {
        val strictGrand = SupportListCriteria(setOf(100L), setOf(7L), grandServant = true, bondCeEffect = BondCEEffectEnum.NP, bothCeSlots = true)
        val first = follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1, ce = 7)))
        val text = body(
            first,
            follower("b", main = mapOf((1 to 1) to realSlot(999, 1, 1, ce = 7))),
            follower("c", type = 2, main = mapOf((2 to 3) to realSlot(100, 2, 3, ce = 7), (1 to 2) to realSlot(998, 1, 2))),
        )
        for (c in listOf(strictGrand, grandOn, grandOn.copy(grandScore16 = true, servantClassIds = mapOf(100L to 1)))) {
            val (kept, counts) = onlyFollower(text, c)
            assertEquals("a", kept["userId"]!!.jsonPrimitive.content)
            assertWithoutServants(kept, first)
            assertEquals(SupportListCounts(3, 0, 4, 0, placeholderKept = true), counts)
        }
    }
    private fun onlyFollower(text: String, criteria: SupportListCriteria): Pair<JsonObject, SupportListCounts> {
        val r = assertNotNull(SupportListFilter.apply(text.toByteArray(), null, criteria))
        val root = Json.parseToJsonElement(String(r.wire)).jsonObject
        assertEquals(Json.parseToJsonElement(text).jsonObject.keys, root.keys)
        val judged = infos(root).map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content in setOf("1", "2") }
        return judged.single() to r.counts
    }

    private fun assertWithoutServants(follower: JsonObject, received: String) {
        val original = Json.parseToJsonElement(received).jsonObject
        for (key in listOf("userSvtLeaderHash", "eventUserSvtLeaderHash")) {
            val slots = follower[key]!!.jsonArray
            val before = original[key]!!.jsonArray
            slots.forEachIndexed { i, s ->
                val o = before[i].jsonObject
                val filled = o["svtId"]!!.jsonPrimitive.content != "0"
                assertEquals(if (filled) SupportListFilter.blankSlot(o) else o, s, "$key[$i]")
            }
        }
        val keep = setOf("userSvtLeaderHash", "eventUserSvtLeaderHash", "userSvtGrandHash")
        assertEquals(original.filterKeys { it !in keep }, follower.filterKeys { it !in keep })
    }

    @Test fun aServantNobodyHasKeepsTheFirstFollowerWithoutServants() {
        val npc = """{"type":3,"userId":"npc","userSvtLeaderHash":[${realSlot(500, 1, 1)}]}"""
        val first = follower("a", main = mapOf((1 to 1) to realSlot(300, 1, 1)), grand = listOf(realGrand(300), realGrand(0)),
            event = mapOf((1 to 2) to realSlot(301, 1, 2)))
        val text = body(npc, first, follower("b", type = 2, main = mapOf((1 to 1) to realSlot(400, 1, 1))))
        val (kept, counts) = onlyFollower(text, SupportListCriteria(setOf(100L)))
        assertEquals("a", kept["userId"]!!.jsonPrimitive.content)
        assertWithoutServants(kept, first)
        // The empty entry stays; the filled one goes.
        assertEquals(Json.parseToJsonElement("[${realGrand(0)}]"), kept["userSvtGrandHash"])
        assertEquals(SupportListCounts(3, 1, 4, 0, placeholderKept = true), counts)
        val r = SupportListFilter.apply(text.toByteArray(), null, SupportListCriteria(setOf(100L)))!!
        val out = infos(Json.parseToJsonElement(String(r.wire)).jsonObject)
        assertEquals(Json.parseToJsonElement(npc), out[0])
        assertEquals(listOf("npc", "a"), out.map { it.jsonObject["userId"]!!.jsonPrimitive.content })
    }

    @Test fun friendsOnlyWithNoMatchingFriendKeepsTheFirstJudgedFollower() {
        val first = follower("n", type = 2, main = mapOf((1 to 1) to realSlot(100, 1, 1)))
        val text = body(first, follower("f", main = mapOf((1 to 1) to realSlot(999, 1, 1))))
        val (kept, counts) = onlyFollower(text, SupportListCriteria(setOf(100L), friendsOnly = true))
        assertEquals("n", kept["userId"]!!.jsonPrimitive.content)
        assertWithoutServants(kept, first)
        assertEquals(0, counts.followersAfter)
        assertTrue(counts.placeholderKept)
    }

    @Test fun aGrandConfigThatMatchesNobodyOnJpKeepsTheFirstFollowerWithAnEmptyGrandContainer() {
        val first = follower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)), grand = listOf(realGrand(999)))
        val keyed = first.replace(""""userSvtGrandHash":[${realGrand(999)}]""", """"userSvtGrandHash":{"1":${realGrand(999)}}""")
        for ((received, empty) in listOf(first to "[]", keyed to "{}")) {
            val text = body(received, follower("b", grand = listOf(realGrand(998))))
            val (kept, counts) = onlyFollower(text, grandOn)
            assertWithoutServants(kept, received)
            assertEquals(Json.parseToJsonElement(empty), kept["userSvtGrandHash"])
            assertEquals(SupportListCounts(2, 0, 3, 0, placeholderKept = true), counts)
        }
    }

    @Test fun oneMatchingFollowerKeepsTheRulesOfToday() {
        val text = body(follower("a", main = mapOf((1 to 1) to realSlot(999, 1, 1))), follower("b", main = mapOf((1 to 1) to realSlot(100, 1, 1))))
        assertEquals(listOf("b"), kept(text, SupportListCriteria(setOf(100L))))
        assertEquals(false, SupportListFilter.apply(text.toByteArray(), null, SupportListCriteria(setOf(100L)))!!.counts.placeholderKept)
    }

    @Test fun aFirstFollowerThatCannotBeBlankedSafelyPassesTheResponseUnchanged() {
        // An event hash that is JSON null is skipped by the judging, but the blank form has nothing to build it from.
        val odd = follower("a", main = mapOf((1 to 1) to realSlot(999, 1, 1))).replace(""""eventUserSvtLeaderHash":[]""", """"eventUserSvtLeaderHash":null""")
        assertNull(SupportListFilter.apply(body(odd, follower("b", main = mapOf((1 to 1) to realSlot(998, 1, 1)))).toByteArray(), null, SupportListCriteria(setOf(100L))))
        // An unseen shape in the first follower: unchanged as before.
        val unseen = follower("a", main = mapOf((1 to 1) to realSlot(999, 1, 1))).replace(""""svtId":999""", """"svtId":"999"""")
        assertNull(SupportListFilter.apply(body(unseen, follower("b")).toByteArray(), null, SupportListCriteria(setOf(100L))))
    }

    private companion object {
        /** `releasedClassIds` of boards 1 to 9 in a real JP response (2026-11-25). */
        val JP_RELEASED = (1..7).associateWith { listOf(it) } + mapOf(8 to listOf(8, 9, 11, 23), 9 to listOf(10, 25, 28, 33, 38, 40))
    }
}
