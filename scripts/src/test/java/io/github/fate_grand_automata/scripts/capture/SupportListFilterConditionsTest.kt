package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every condition of #685, alone and combined, on fictional data in the real shape (3 decks x 9 classes). */
class SupportListFilterConditionsTest {
    private data class Svt(
        val id: Long,
        val limit: Int = 4,
        val disp: Int = 3,
        val skills: List<Int> = listOf(10, 10, 10),
        val appends: List<Pair<Long, Int?>> = emptyList(),
        val np: Int = 5,
        val ce: Long = 0,
        val ceLimit: Int = 4,
        val ce2: Long = 0,
        val ce2Limit: Int = 0,
    )

    private fun slot(svt: Svt, deck: Int, cls: Int): String {
        val appends = svt.appends.joinToString(",") { (id, lv) -> """{"skillId":$id,"skillLv":${lv ?: "null"}}""" }
        return """{"afterTransformSvtInfo":null,"dispLimitCount":${svt.disp},"imageLimitCount":${svt.disp},""" +
            """"equipTarget1":{"userId":9,"svtId":${svt.ce},"limitCount":${svt.ceLimit},"lv":100},""" +
            """"equipTarget2":{"svtId":${svt.ce2},"limitCount":${svt.ce2Limit}},""" +
            """"commandCardParam":[1,2,3,4,5],"appendPassiveSkill":[$appends],"script":{},""" +
            """"userId":555,"supportDeckId":$deck,"classId":$cls,"svtId":${svt.id},"limitCount":${svt.limit},"lv":90,""" +
            """"skillLv1":${svt.skills[0]},"skillLv2":${svt.skills[1]},"skillLv3":${svt.skills[2]},"treasureDeviceLv":${svt.np},"updatedAt":1}"""
    }

    private fun empty(deck: Int, cls: Int) = slot(Svt(0, limit = 0, disp = 1, skills = listOf(0, 0, 0), np = 0, ceLimit = 0), deck, cls)

    /** One filled slot at deck 1, class 1; every other of the 27 slots empty. */
    private fun follower(id: String, svt: Svt?, type: Int = 1, boards: String? = null): String {
        val hash = (1..3).flatMap { d -> (0..8).map { c -> if (d == 1 && c == 1 && svt != null) slot(svt, d, c) else empty(d, c) } }
        val boardPart = boards?.let { ""","userClassBoardInfo":$it""" }.orEmpty()
        return """{"type":$type,"userId":"$id","userSvtLeaderHash":[${hash.joinToString(",")}],"eventUserSvtLeaderHash":[],"userSvtGrandHash":[]$boardPart}"""
    }

    private fun body(vararg followers: String) =
        """{"response":[],"cache":{"updated":{"userFollower":[{"followerInfo":[${followers.joinToString(",")}]}]},"serverTime":1}}"""

    private fun kept(text: String, criteria: SupportListCriteria): List<String>? {
        val r = SupportListFilter.apply(text.toByteArray(), null, criteria) ?: return null
        val root = Json.parseToJsonElement(String(r.wire)).jsonObject
        val infos = root["cache"]!!.jsonObject["updated"]!!.jsonObject["userFollower"]!!.jsonArray[0].jsonObject["followerInfo"] as JsonArray
        return infos.map { it.jsonObject["userId"]!!.jsonPrimitive.content }
    }

    @Test fun anyServantWithoutAServantListKeepsEveryFilledSlot() {
        // Only friends: the non-friend goes, every filled slot of a friend stays.
        assertEquals(listOf("a"), kept(body(follower("a", Svt(100)), follower("n", Svt(200), type = 2)), SupportListCriteria(friendsOnly = true)))
        assertEquals(listOf("a", "n"), kept(body(follower("a", Svt(100)), follower("n", Svt(200), type = 2), follower("e", null)), SupportListCriteria(skillsMax = listOf(true, false, false))))
    }

    @Test fun ceAnyOfWithMlb() {
        val c = SupportListCriteria(ceIds = setOf(7), ceMlb = true)
        val text = body(
            follower("mlb", Svt(100, ce = 7, ceLimit = 4)),
            follower("notMlb", Svt(100, ce = 7, ceLimit = 3)),
            follower("ce2Mlb", Svt(100, ce = 8, ce2 = 7, ce2Limit = 4)),
            follower("other", Svt(100, ce = 8, ceLimit = 4)),
        )
        assertEquals(listOf("mlb", "ce2Mlb"), kept(text, c))
        assertEquals(listOf("mlb", "notMlb", "ce2Mlb"), kept(text, c.copy(ceMlb = false)))
    }

    @Test fun finalAscension() {
        val text = body(follower("final", Svt(100, limit = 4)), follower("third", Svt(100, limit = 3)))
        assertEquals(listOf("final"), kept(text, SupportListCriteria(setOf(100), maxAscended = true)))
    }

    @Test fun displayedAscensionIsPerServant() {
        val c = SupportListCriteria(setOf(100, 200), displayedAscensions = mapOf(100L to setOf(0, 12)))
        val text = body(
            follower("stage1", Svt(100, disp = 0)),
            follower("costume2", Svt(100, disp = 12)),
            follower("final", Svt(100, disp = 3)),
            follower("otherServantAnyLook", Svt(200, disp = 3)),
        )
        assertEquals(listOf("stage1", "costume2", "otherServantAnyLook"), kept(text, c))
    }

    @Test fun displayedAscensionTokensMapInOnePlace() {
        assertEquals(listOf(0, 1, 2, 3), DisplayedAscension.STAGES.map { DisplayedAscension.dispLimitCount(it) })
        assertEquals(11, DisplayedAscension.dispLimitCount("c1"))
        assertEquals(16, DisplayedAscension.dispLimitCount("c6"))
        assertNull(DisplayedAscension.dispLimitCount("s5"))
        assertNull(DisplayedAscension.dispLimitCount("c0"))
        assertNull(DisplayedAscension.dispLimitCount("x1"))
        assertEquals("c2" to "No.1 a|b", DisplayedAscension.parse(DisplayedAscension.entry("c2", "No.1 a|b")))
        assertNull(DisplayedAscension.parse("q|x"))
    }

    @Test fun eachSkillAtTen() {
        val text = body(follower("all", Svt(100)), follower("s2low", Svt(100, skills = listOf(10, 9, 10))))
        assertEquals(listOf("all"), kept(text, SupportListCriteria(setOf(100), skillsMax = listOf(false, true, false))))
        assertNull(kept(text, SupportListCriteria(setOf(100), skillsMax = listOf(true, false, true))))
    }

    @Test fun appendsUseFriendRosterNumbering() {
        // 3001000 is append 1, 3002000 append 2, any other id append 3 (class attack up), 3033000 append 4, 3034000 append 5.
        val c = SupportListCriteria(setOf(100), appendsMax = listOf(false, true, true, false, false))
        val text = body(
            follower("both", Svt(100, appends = listOf(3002000L to 10, 3112000L to 10))),
            follower("only2", Svt(100, appends = listOf(3002000L to 10, 3112000L to 9))),
            follower("locked", Svt(100, appends = listOf(3002000L to null, 3112000L to 10))),
            follower("missing", Svt(100, appends = listOf(3112000L to 10))),
        )
        assertEquals(listOf("both"), kept(text, c))
    }

    @Test fun npLevelAtLeast() {
        val text = body(follower("np5", Svt(100, np = 5)), follower("np3", Svt(100, np = 3)), follower("np2", Svt(100, np = 2)))
        assertEquals(listOf("np5", "np3"), kept(text, SupportListCriteria(setOf(100), minimumNpLevel = 3)))
    }

    private fun boards(vararg entries: Pair<List<Int>, Int>) =
        entries.joinToString(",", "[", "]") { (classes, squares) ->
            """{"classBoardBaseId":1,"squareIds":[${(1..squares).joinToString(",")}],"releasedClassIds":[${classes.joinToString(",")}]}"""
        }

    @Test fun classScoreNeedsAFullBoardOfTheServantsClass() {
        val c = SupportListCriteria(classScore62 = true, servantClassIds = mapOf(100L to 1, 200L to 9))
        val text = body(
            follower("saber62", Svt(100), boards = boards(listOf(1) to 62, listOf(9, 10) to 30)),
            follower("saber61", Svt(100), boards = boards(listOf(1) to 61)),
            follower("ruler62", Svt(200), boards = boards(listOf(1) to 30, listOf(9, 10) to 62)),
            follower("noBoards", Svt(100)),
            follower("unknownClass", Svt(300), boards = boards(listOf(1) to 62)),
        )
        assertEquals(listOf("saber62", "ruler62"), kept(text, c))
    }

    @Test fun classBoardsThatAreAbsentOrNullMatchNothing() {
        val c = SupportListCriteria(classScore62 = true, servantClassIds = mapOf(100L to 1))
        val text = body(
            follower("full", Svt(100), boards = boards(listOf(1) to 62)),
            follower("absent", Svt(100)),
            follower("null", Svt(100), boards = "null"),
        )
        assertEquals(listOf("full"), kept(text, c))
    }

    @Test fun classBoardsOfAnOddShapeLeaveEverythingUnchanged() {
        val c = SupportListCriteria(classScore62 = true, servantClassIds = mapOf(100L to 1))
        val full = boards(listOf(1) to 62)
        val squares = (1..62).joinToString(",")
        val odd = listOf(
            "notAnArray" to """{"a":1}""",
            "elementNotAnObject" to "[$full,3]".replace("[[", "[").replace("],3]", ",3]"),
            "squareIdsNotAnArray" to """[{"squareIds":62,"releasedClassIds":[1]}]""",
            "releasedClassIdsNotAnArray" to """[{"squareIds":[$squares],"releasedClassIds":1}]""",
            "squareIdNotAnInteger" to """[{"squareIds":[$squares,"x"],"releasedClassIds":[1]}]""",
            "classIdNotAnInteger" to """[{"squareIds":[$squares],"releasedClassIds":[1.5]}]""",
            "classIdAString" to """[{"squareIds":[$squares],"releasedClassIds":["1"]}]""",
        )
        for ((name, shape) in odd) {
            val text = body(follower("full", Svt(100), boards = full), follower("other", Svt(200), boards = full), follower("odd", Svt(100), boards = shape))
            assertNull(kept(text, c), name)
        }
        // Without the class score condition the boards are not read.
        val text = body(follower("a", Svt(100), boards = """{"a":1}"""), follower("b", Svt(200)))
        assertEquals(listOf("a"), kept(text, SupportListCriteria(setOf(100))))
    }

    @Test fun friendsOnlyLeavesNonFriendsOutAndKeepsOtherTypes() {
        val npc = """{"type":3,"userId":"npc"}"""
        val text = body(follower("f", Svt(100)), follower("n", Svt(100), type = 2), npc)
        assertEquals(listOf("f", "npc"), kept(text, SupportListCriteria(setOf(100), friendsOnly = true)))
    }

    @Test fun everySetConditionHasToHoldInOneSlot() {
        val c = SupportListCriteria(
            servantIds = setOf(100), ceIds = setOf(7), ceMlb = true, maxAscended = true,
            displayedAscensions = mapOf(100L to setOf(3)), skillsMax = listOf(true, true, true),
            appendsMax = listOf(true, false, false, false, false), minimumNpLevel = 5,
            classScore62 = true, servantClassIds = mapOf(100L to 1), friendsOnly = true,
        )
        val good = Svt(100, ce = 7, appends = listOf(3001000L to 10))
        val board = boards(listOf(1) to 62)
        val text = body(
            follower("all", good, boards = board),
            follower("nonFriend", good, type = 2, boards = board),
            follower("ceNotMlb", good.copy(ceLimit = 3), boards = board),
            follower("notFinal", good.copy(limit = 3), boards = board),
            follower("look", good.copy(disp = 11), boards = board),
            follower("skill", good.copy(skills = listOf(10, 10, 9)), boards = board),
            follower("append", good.copy(appends = listOf(3001000L to 9)), boards = board),
            follower("np", good.copy(np = 4), boards = board),
            follower("score", good, boards = boards(listOf(1) to 40)),
            follower("servant", good.copy(id = 101), boards = board),
        )
        assertEquals(listOf("all"), kept(text, c))
    }

    @Test fun aFieldASetConditionNeedsInAnUnknownShapeLeavesEverythingUnchanged() {
        val text = body(follower("a", Svt(100)), follower("b", Svt(200))).replaceFirst(""""skillLv2":10""", """"skillLv2":"10"""")
        assertNull(kept(text, SupportListCriteria(setOf(100), skillsMax = listOf(false, true, false))))
        // The same field is not read without its condition.
        assertEquals(listOf("a"), kept(text, SupportListCriteria(setOf(100))))
    }

    @Test fun criteriaWithNothingSetAreUnchanged() {
        assertTrue(!SupportListCriteria().hasConditions)
        assertNull(kept(body(follower("a", Svt(100)), follower("n", null, type = 2)), SupportListCriteria()))
    }
}
