package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportListShapeTest {
    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    // Fictional values that must never reach the output. Small ones are chosen so that no count or length can equal them.
    private val forbidden = listOf(
        "PRIVATE-USER-ID", "PRIVATE-NAME", "PRIVATE-MESSAGE", "987654321", "PRIVATE-STR", "PRIVATE-KEY-NAME",
        "7654", "7655", "4321", "1700000123", "777", "778", "779", "888", "889", "SECRET-CODE",
    )

    private fun slot(svt: Long, deck: Int, cls: Int, lv: Int = 90, ce: Long = 0, extra: String = "") =
        """{"userId":"PRIVATE-USER-ID","userSvtId":1700000123,"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"limitCount":4,
        "dispLimitCount":4,"imageLimitCount":1,"commandCardLimitCount":1,"iconLimitCount":1,"portraitLimitCount":1,
        "lv":$lv,"skillLv1":10,"skillLv2":9,"skillLv3":8,"treasureDeviceLv":1,"exceedCount":0,"grandSvt":0,"battleVoice":0,
        "eventSvtPoint":0,"transformVal":0,"afterTransformSvtInfo":null,
        "appendPassiveSkill":[{"skillId":888,"skillLv":5},{"skillId":889,"skillLv":10}],"commandCode":[],"classPassive":[1,2,3],
        "equipTarget1":{"svtId":$ce,"limitCount":4,"lv":100,"userSvtId":4321},"equipTarget2":null,
        "script":{"someSvtId":777,"flag":true,"isSetClassBoardBaseIds":[1,2,3],"text":"PRIVATE-STR","privateTime":5},
        "extraStats":{"a":3},"secretCode":{"b":4}$extra}"""

    private fun grandSlot() =
        """{"userId":"PRIVATE-USER-ID","userSvtId":1700000123,"supportDeckId":1,"classId":1,"svtId":7654,"grandSvt":1,"lv":120,
        "afterTransformSvtInfo":{"svtId":778,"lv":3},
        "equipTarget1":{"svtId":7655,"limitCount":4,"lv":100},"equipTarget2":{"svtId":0,"limitCount":0,"lv":1},
        "equipTarget3":{"svtId":779,"limitCount":3,"lv":90},
        "script":{"equipTarget2SkillChange":1,"isSetClassBoardBaseIds":[3,5,1700000123],"PRIVATE-KEY-NAME":2,"big":1700000123}}"""

    private fun hash(key: String, slots: Map<Pair<Int, Int>, String>): String =
        "\"$key\":[" + (1..3).flatMap { d -> (0..8).map { c -> slots[d to c] ?: slot(0, d, c, lv = 1) } }.joinToString(",") + "]"

    private fun friend(type: Int, main: Map<Pair<Int, Int>, String>, grand: String? = null, boards: String? = null) =
        """{"type":$type,"userId":"PRIVATE-USER-ID","userName":"PRIVATE-NAME","message":"PRIVATE-MESSAGE","friendCode":"987654321",
        "mainSupportDeckIds":[1,2,3],"eventSupportDeckIds":[4,5,6],"followerClassStatistics":[{"classId":1,"count":2},{"classId":2,"count":1}],
        ${hash("userSvtLeaderHash", main)},${hash("eventUserSvtLeaderHash", emptyMap())},
        "userSvtGrandHash":${grand?.let { "[$it]" } ?: "{\"1\":${grandSlot()},\"2\":${slot(0, 1, 1)}}"}
        ${boards?.let { ",\"userClassBoardInfo\":$it" } ?: ""}}"""

    private val boards = """[{"classBoardBaseId":5,"squareIds":[1,2,3],"releasedClassIds":[1,2]},
        {"classBoardBaseId":6,"squareIds":[1],"releasedClassIds":[3]},{"classBoardBaseId":1700000123,"squareIds":[],"releasedClassIds":[]}]"""

    private val fixture: JsonObject = parse(
        """{"response":[{"resCode":"00","fail":{}}],"cache":{"replaced":{},"updated":{"userFollower":[
        {"userId":"PRIVATE-USER-ID","followerInfo":[
          ${friend(1, mapOf((1 to 1) to slot(100, 1, 1, ce = 1), (2 to 3) to slot(200, 2, 3)), boards = boards)},
          ${friend(2, emptyMap(), grand = grandSlot())}]},
        {"followerInfo":[${friend(2, mapOf((3 to 8) to slot(300, 3, 8)))}]}]},
        "other":1},"serverTime":1700000123,"PRIVATE-KEY-NAME":"x"}"""
    )

    private val built = SupportListShape.build(fixture, "KR", 55)!!
    private val text = built.toString()
    private fun hist(group: String, field: String) = built["histograms"]!!.jsonObject[group]!!.jsonObject[field]!!.jsonObject
    private fun values(group: String, field: String): Map<String, Int> =
        hist(group, field)["values"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
    private fun shape(path: String) = built["shape"]!!.jsonObject[path]!!.jsonObject

    @Test fun shapeTreeMergesArrayElementsAndIdKeyedMaps() {
        val followers = shape("$.cache.updated.userFollower[].followerInfo")
        assertEquals(listOf("array"), followers["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, followers["len"]!!.jsonObject["min"]!!.jsonPrimitive.int)
        assertEquals(2, followers["len"]!!.jsonObject["max"]!!.jsonPrimitive.int)
        val grandMap = shape("$.cache.updated.userFollower[].followerInfo[].userSvtGrandHash")
        assertEquals(listOf("array", "map"), grandMap["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNotNull(built["shape"]!!.jsonObject["$.cache.updated.userFollower[].followerInfo[].userSvtGrandHash{*}"])
        assertNotNull(built["shape"]!!.jsonObject["$.cache.updated.userFollower[].followerInfo[].userSvtGrandHash[]"])
        val hash = shape("$.cache.updated.userFollower[].followerInfo[].userSvtLeaderHash")
        assertEquals(27, hash["len"]!!.jsonObject["min"]!!.jsonPrimitive.int)
        assertEquals(27, hash["len"]!!.jsonObject["max"]!!.jsonPrimitive.int)
        assertEquals(listOf("null"), shape("$.cache.updated.userFollower[].followerInfo[].userSvtLeaderHash[].equipTarget2")["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNotNull(built["shape"]!!.jsonObject["$.cache.updated.userFollower[].followerInfo[].userClassBoardInfo[].squareIds[]"])
    }

    @Test fun topLevelAndCacheKeysAreListedWithoutIdentifierLikeOddities() {
        assertEquals(listOf("cache", "response", "serverTime"), built["topKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("other", "replaced", "updated"), built["cacheKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("KR", built["server"]!!.jsonPrimitive.content)
    }

    @Test fun countsAreOfTheWholeResponse() {
        val counts = built["counts"]!!.jsonObject
        assertEquals(3, counts["followerInfo"]!!.jsonPrimitive.int)
        val filled = counts["filledSlots"]!!.jsonObject
        assertEquals(3, filled["userSvtLeaderHash"]!!.jsonPrimitive.int)
        assertEquals(0, filled["eventUserSvtLeaderHash"]!!.jsonPrimitive.int)
        assertEquals(3, filled["userSvtGrandHash"]!!.jsonPrimitive.int)
    }

    @Test fun followerHistograms() {
        assertEquals(mapOf("1" to 1, "2" to 2), values("follower", "type"))
        assertEquals(mapOf("3" to 3), values("follower", "mainSupportDeckIds.len"))
        assertEquals(mapOf("3" to 3), values("follower", "eventSupportDeckIds.len"))
        assertEquals(mapOf("2" to 3), values("follower", "followerClassStatistics.len"))
        assertEquals(mapOf("1" to 1, "2" to 2), values("follower", "userSvtGrandHash.len"))
    }

    @Test fun slotHistogramsAreSeparatePerHash() {
        // 3 followers x 27 slots, of which 3 are filled
        assertEquals(mapOf("true" to 78, "false" to 3), values("userSvtLeaderHash", "svtId.isZero"))
        assertEquals(mapOf("true" to 81), values("eventUserSvtLeaderHash", "svtId.isZero"))
        assertEquals(mapOf("false" to 3, "true" to 2), values("userSvtGrandHash", "svtId.isZero"))
        assertEquals(mapOf("90" to 3, "1" to 78), values("userSvtLeaderHash", "lv"))
        assertEquals(mapOf("0" to 2, "1" to 3), values("userSvtGrandHash", "grandSvt"))
        assertEquals(mapOf("true" to 81), values("userSvtLeaderHash", "afterTransformSvtInfo.isNull"))
        assertEquals(mapOf("2" to 81), values("userSvtLeaderHash", "appendPassiveSkill.len"))
        assertEquals(mapOf("5" to 81, "10" to 81), values("userSvtLeaderHash", "appendPassiveSkill[].skillLv"))
        assertEquals(mapOf("0" to 81), values("userSvtLeaderHash", "commandCode.len"))
        assertEquals(mapOf("3" to 81), values("userSvtLeaderHash", "classPassive.len"))
        assertEquals(mapOf("false" to 3, "true" to 2), values("userSvtGrandHash", "afterTransformSvtInfo.isNull"))
    }

    @Test fun equipTargetsAreRecordedAsFlagsAndSmallLevelsOnly() {
        assertEquals(mapOf("false" to 81), values("userSvtLeaderHash", "equipTarget1.isNull"))
        assertEquals(mapOf("true" to 81), values("userSvtLeaderHash", "equipTarget2.isNull"))
        assertEquals(mapOf("true" to 80, "false" to 1), values("userSvtLeaderHash", "equipTarget1.svtId.isZero"))
        assertEquals(mapOf("4" to 81), values("userSvtLeaderHash", "equipTarget1.limitCount"))
        assertEquals(mapOf("100" to 81), values("userSvtLeaderHash", "equipTarget1.lv"))
        assertEquals(mapOf("false" to 3), values("userSvtGrandHash", "equipTarget3.isNull"))
        assertEquals(mapOf("true" to 3), values("userSvtGrandHash", "equipTarget2.svtId.isZero"))
        assertEquals(mapOf("3" to 3), values("userSvtGrandHash", "equipTarget3.limitCount"))
    }

    @Test fun scriptAndOtherObjectsGetKeyValueHistograms() {
        assertEquals(mapOf("true" to 81), values("userSvtLeaderHash", "script.flag"))
        assertEquals(mapOf("3" to 81), values("userSvtLeaderHash", "script.isSetClassBoardBaseIds.len"))
        assertEquals(mapOf("1" to 81, "2" to 81, "3" to 81), values("userSvtLeaderHash", "script.isSetClassBoardBaseIds[]"))
        assertEquals(mapOf("1" to 3), values("userSvtGrandHash", "script.equipTarget2SkillChange"))
        assertEquals(3, hist("userSvtGrandHash", "script.isSetClassBoardBaseIds[]")["large"]!!.jsonPrimitive.int)
        assertEquals(3, hist("userSvtGrandHash", "script.big")["large"]!!.jsonPrimitive.int)
        assertEquals(mapOf("3" to 81), values("userSvtLeaderHash", "extraStats.a"))
        val fields = built["histograms"]!!.jsonObject["userSvtLeaderHash"]!!.jsonObject.keys
        assertFalse(fields.any { it.contains("secretCode") || it.contains("someSvtId") || it.contains("privateTime") || it.contains("text") })
    }

    @Test fun classBoardsAreKeyedBySmallBaseIdsOnly() {
        assertEquals(mapOf("3" to 1), values("classBoards", "perFollower"))
        assertEquals(mapOf("5" to 1, "6" to 1), values("classBoards", "classBoardBaseId"))
        assertEquals(1, hist("classBoards", "classBoardBaseId")["large"]!!.jsonPrimitive.int)
        assertEquals(mapOf("3" to 1), values("classBoards", "squareIds.len@5"))
        assertEquals(mapOf("0" to 1), values("classBoards", "squareIds.len@large"))
        assertEquals(mapOf("1" to 1, "2" to 1), values("classBoards", "releasedClassIds@5"))
    }

    @Test fun noForbiddenValueAppearsInTheOutput() {
        for (value in forbidden) assertFalse(value in text, value)
    }

    @Test fun manyDistinctValuesFallBackToMinMaxAndCount() {
        val slots = (1..60).joinToString(",") { """{"svtId":1,"lv":$it}""" }
        val root = parse("""{"cache":{"updated":{"userFollower":[{"followerInfo":[{"type":1,"userSvtLeaderHash":[$slots]}]}]}}}""")
        val lv = SupportListShape.build(root, "JP", 1)!!["histograms"]!!.jsonObject["userSvtLeaderHash"]!!.jsonObject["lv"]!!.jsonObject
        assertNull(lv["values"])
        assertEquals(1, lv["min"]!!.jsonPrimitive.int)
        assertEquals(60, lv["max"]!!.jsonPrimitive.int)
        assertEquals(60, lv["n"]!!.jsonPrimitive.int)
    }

    @Test fun malformedResponsesGiveNothingAndThrowNothing() {
        for (text in listOf(
            "{}", """{"cache":1}""", """{"cache":{"updated":[]}}""", """{"cache":{"updated":{"userFollower":{}}}}""",
            """{"cache":{"updated":{"other":[1]}}}""",
        )) assertNull(SupportListShape.build(parse(text), "KR", 1), text)
        val odd = parse("""{"cache":{"updated":{"userFollower":["x",1,null,{"followerInfo":"x"},{"followerInfo":[1,"y",null,
            {"type":"1","userSvtLeaderHash":[1,null,"s",{"svtId":"x","lv":"PRIVATE-STR","equipTarget1":5}],"userSvtGrandHash":7}]}]}}}""")
        val out = SupportListShape.build(odd, "KR", 1)
        assertNotNull(out)
        assertFalse("PRIVATE-STR" in out.toString())
    }

    @Test fun diagnosticsDescribeTheOriginalListNotTheFilteredOne() {
        val criteria = SupportListCriteria(setOf(100L))
        val (filtered, counts) = SupportListFilter.filter(fixture, criteria)!!
        val before = SupportListShape.build(fixture, "KR", 55)!!
        assertEquals(counts.followersBefore, before["counts"]!!.jsonObject["followerInfo"]!!.jsonPrimitive.int)
        val filled = before["counts"]!!.jsonObject["filledSlots"]!!.jsonObject.values.sumOf { it.jsonPrimitive.int }
        assertEquals(counts.slotsBefore, filled)
        // The filter built a new tree; the original is untouched and still describes every follower.
        assertEquals(built, before)
        val after = SupportListShape.build(filtered, "KR", 55)!!
        assertTrue(after["counts"]!!.jsonObject["followerInfo"]!!.jsonPrimitive.int < counts.followersBefore)
    }
}
