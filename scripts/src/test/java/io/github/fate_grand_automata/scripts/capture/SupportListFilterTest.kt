package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportListFilterTest {
    /** One slot as the server sends it; svtId 0 is an empty slot. */
    private fun slot(svt: Long, deck: Int, cls: Int, ce: Long = 0, ce2: Long = 0) =
        """{"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"lv":90,"equipTarget1":{"svtId":$ce,"limitCount":4,"lv":100},"equipTarget2":{"svtId":$ce2}}"""

    /** 3 decks x classes 0..8 (27 slots), all empty except the ones in [set] keyed by (deck, class). */
    private fun hash(set: Map<Pair<Int, Int>, String> = emptyMap()): List<String> =
        (1..3).flatMap { d -> (0..8).map { c -> set[d to c] ?: slot(0, d, c) } }

    private fun friend(
        id: String,
        type: Int = 1,
        main: Map<Pair<Int, Int>, String> = emptyMap(),
        event: Map<Pair<Int, Int>, String> = emptyMap(),
        grand: List<String> = emptyList(),
    ) = """{"type":$type,"userId":"$id","userName":"fictional $id","mainSupportDeckIds":[1,2,3],"eventSupportDeckIds":[4,5,6],""" +
        """"userSvtLeaderHash":[${hash(main).joinToString(",")}],"eventUserSvtLeaderHash":[${hash(event).joinToString(",")}],""" +
        """"userSvtGrandHash":[${grand.joinToString(",")}],"extra":1.50}"""

    private fun body(vararg friends: String) =
        """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"userId":"me","other":7,"followerInfo":[${friends.joinToString(",")}]}],"other":[1,2]},"serverTime":99}}"""

    private val criteria = SupportListCriteria(setOf(100L, 200L))

    private fun run(text: String, c: SupportListCriteria = criteria, encoding: String? = null, wire: ByteArray = text.toByteArray()) =
        SupportListFilter.apply(wire, encoding, c)

    private fun infos(r: SupportListFiltered, encoding: String? = null): JsonArray {
        val root = Json.parseToJsonElement(String(BodyDecoder.decode(r.wire, encoding))).jsonObject
        return root["cache"]!!.jsonObject["updated"]!!.jsonObject["userFollower"]!!.jsonArray[0].jsonObject["followerInfo"]!!.jsonArray
    }

    private fun gzip(text: String) =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun hit(svt: Long = 100, deck: Int = 1, cls: Int = 1, ce: Long = 0, ce2: Long = 0) =
        mapOf((deck to cls) to slot(svt, deck, cls, ce, ce2))

    private fun ids(r: SupportListFiltered, encoding: String? = null) = infos(r, encoding).map { it.jsonObject["userId"]!!.jsonPrimitive.content }

    @Test fun nonFriendsAreFilteredLikeFriends() {
        val r = run(body(friend("f1"), friend("n1", type = 2), friend("f2", main = hit()), friend("n2", type = 2, main = hit())))!!
        assertEquals(listOf("f2", "n2"), ids(r))
        assertEquals(SupportListCounts(4, 2, 2, 2), r.counts)
    }

    @Test fun followerMatchingInOneDeckClassOrOtherHashStaysWhole() {
        val inEvent = friend("ev", event = hit(200, deck = 3, cls = 8))
        val grandOnly = friend("gr", grand = listOf("""{"grandGraphId":1,"svtId":100,"equipTarget1":{"svtId":0},"equipTarget3":{"svtId":0}}"""))
        val oneClass = friend("cl", main = hit(100, deck = 3, cls = 0))
        val r = run(body(friend("none"), inEvent, grandOnly, oneClass))!!
        assertEquals(listOf("ev", "gr", "cl"), ids(r))
        assertEquals(listOf(inEvent, grandOnly, oneClass).map { Json.parseToJsonElement(it) }, infos(r).toList())
    }

    @Test fun emptySlotNeverMatches() {
        // Even an id of 0 in the chosen set must not keep a follower whose slots are all empty.
        val c = SupportListCriteria(setOf(0L, 100L))
        assertEquals(0, infos(run(body(friend("e1"), friend("e2")), c)!!).size)
        assertEquals(listOf("a"), ids(run(body(friend("a", main = hit(100)), friend("empty")), c)!!))
    }

    @Test fun nothingToRemoveIsUnchanged() {
        assertNull(run(body(friend("a", main = hit()), friend("n", type = 2, event = hit(200)))))
    }

    @Test fun everyFollowerRemovedLeavesAnEmptyListAndKeepsOtherKeys() {
        val r = run(body(friend("a"), friend("b", type = 2)))!!
        assertEquals(0, infos(r).size)
        assertEquals(SupportListCounts(2, 0), r.counts)
        val text = String(r.wire)
        assertTrue(text.contains(""""userFollower":[{"userId":"me","other":7,"followerInfo":[]}]"""))
        assertTrue(text.contains(""""serverTime":99"""))
    }

    @Test fun otherTypesAreKeptUnchangedEvenWhenEveryFriendAndNonFriendIsRemoved() {
        val npc = friend("npc", type = 3)
        val noHash = """{"type":4,"userId":"fixed"}"""
        val r = run(body(friend("a"), npc, friend("b", type = 2), noHash))!!
        assertEquals(listOf("npc", "fixed"), ids(r))
        assertEquals(listOf(npc, noHash).map { Json.parseToJsonElement(it) }, infos(r).toList())
        assertEquals(SupportListCounts(4, 2), r.counts)
        // Only other types: nothing is a candidate, so nothing is removed.
        assertNull(run(body(npc, noHash)))
    }

    @Test fun missingOrNonIntegerTypeIsKept() {
        val missing = """{"userId":"m","userSvtLeaderHash":[]}"""
        val text = """{"type":"1","userId":"s"}"""
        val dec = """{"type":1.5,"userId":"d"}"""
        val nul = """{"type":null,"userId":"n"}"""
        val r = run(body(friend("a"), missing, text, dec, nul))!!
        assertEquals(listOf("m", "s", "d", "n"), ids(r))
        assertNull(run(body(missing, text, dec, nul)))
    }

    @Test fun grandSlotsUseCe1AndCe3WhileBondCeStaysOut() {
        fun grand(svt: Long, e1: Long, e2: Long, e3: Long) =
            """{"grandGraphId":1,"svtId":$svt,"equipTarget1":{"svtId":$e1},"equipTarget2":{"svtId":$e2},"equipTarget3":{"svtId":$e3}}"""
        val c = SupportListCriteria(setOf(100L), setOf(7L))
        val r = run(body(friend("ce1", grand = listOf(grand(100, 7, 0, 0))), friend("ce3", grand = listOf(grand(100, 0, 0, 7))),
            friend("bond", grand = listOf(grand(100, 0, 7, 0))), friend("svt", grand = listOf(grand(999, 7, 0, 0)))), c)!!
        assertEquals(listOf("ce1", "ce3"), ids(r))
    }

    @Test fun normalSlotsUseCe1AndCe2() {
        val c = SupportListCriteria(setOf(100L), setOf(7L))
        val r = run(body(friend("c1", main = hit(ce = 7)), friend("c2", main = hit(ce2 = 7)), friend("no", main = hit(ce = 8, ce2 = 9)), friend("wrongSvt", main = hit(999, ce = 7))), c)!!
        assertEquals(listOf("c1", "c2"), ids(r))
    }

    @Test fun hashKeyedByDeckIsReadAndBlankedKeepingItsKeys() {
        val keyed = """{"type":1,"userId":"a","userSvtLeaderHash":{"z":${slot(999, 1, 1)},"a":${slot(100, 1, 2)},"m":${slot(0, 1, 3)}}}"""
        val r = run(body(keyed, friend("b")))!!
        val hash = infos(r)[0].jsonObject["userSvtLeaderHash"]!!.jsonObject
        assertEquals(listOf("z", "a", "m"), hash.keys.toList())
        assertEquals("0", hash["z"]!!.jsonObject["svtId"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(slot(100, 1, 2)), hash["a"])
        assertEquals(Json.parseToJsonElement(slot(0, 1, 3)), hash["m"])
    }

    @Test fun everyOtherKeyIsKeptInOrderWithNumbersAsRead() {
        val r = run(body(friend("a", main = hit()), friend("b")))!!
        val text = String(r.wire)
        assertTrue(text.startsWith("""{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"userId":"me","other":7,"followerInfo":[{"type":1,"userId":"a","userName":"fictional a","mainSupportDeckIds":[1,2,3]"""))
        assertTrue(text.endsWith("""}]}],"other":[1,2]},"serverTime":99}}"""))
        assertTrue(text.contains(""""extra":1.50"""))
        val odd = run(body(friend("a", main = hit()), friend("b")).replace(""""other":[1,2]""", """"other":[1e5,-0,12345678901234567890]"""))!!
        assertTrue(String(odd.wire).contains(""""other":[1e5,-0,12345678901234567890]"""))
    }

    @Test fun aSignKeyIfPresentIsKept() {
        val r = run(body(friend("a", main = hit()), friend("b")).dropLast(1) + ""","sign":"keep-me"}""")!!
        assertTrue(String(r.wire).endsWith(""","sign":"keep-me"}"""))
    }

    @Test fun noCriteriaIsUnchanged() {
        assertNull(run(body(friend("a")), SupportListCriteria(emptySet())))
    }

    @Test fun unknownShapeAndInvalidJsonAreUnchanged() {
        assertNull(run("""{"cache":{"updated":{"userFollower":"x"}}}"""))
        assertNull(run("""{"cache":{}}"""))
        assertNull(run("[1,2]"))
        assertNull(run("not json"))
        assertNull(run(body(friend("a")).dropLast(5)))
    }

    @Test fun unknownEncodingIsUnchanged() {
        assertNull(run(body(friend("a")), encoding = "br"))
        assertNull(run(body(friend("a")), encoding = "gzip, br"))
    }

    @Test fun bodyOverTheWireLimitIsUnchanged() {
        val pad = " ".repeat(SupportListFilter.MAX_WIRE_BYTES)
        assertNull(run(body(friend("a")) + pad))
    }

    @Test fun decodedBodyOverTheLimitIsUnchanged() {
        val text = body(friend("a")) + " ".repeat(SupportListFilter.MAX_DECODED_BYTES)
        val gz = gzip(text)
        assertTrue(gz.size < SupportListFilter.MAX_WIRE_BYTES)
        assertNull(run("", encoding = "gzip", wire = gz))
    }

    @Test fun gzipAndBothDeflateFlavoursAreEncodedAgain() {
        val text = body(friend("a", main = hit()), friend("b"))
        val g = run("", encoding = "gzip", wire = gzip(text))!!
        assertEquals(1, infos(g, "gzip").size)
        for (raw in listOf(false, true)) {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
            val wire = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o, d).use { it.write(text.toByteArray()) } }.toByteArray()
            d.end()
            val r = run("", encoding = "deflate", wire = wire)!!
            assertEquals(1, infos(r, "deflate").size)
            // The same flavour goes back: raw stays raw, zlib stays zlib.
            val zlibHeader = (r.wire[0].toInt() and 0x0F) == 8 &&
                (((r.wire[0].toInt() and 0xFF) shl 8) or (r.wire[1].toInt() and 0xFF)) % 31 == 0
            assertEquals(!raw, zlibHeader)
        }
    }

    @Test fun followerWithoutAnyReadableSlotHashLeavesEverythingUnchanged() {
        val good = friend("a", main = hit())
        for (type in listOf(1, 2)) {
            for (shape in listOf(
                """{"type":$type,"userId":"x"}""",
                """{"type":$type,"userId":"x","userSvtLeaderHash":null,"eventUserSvtLeaderHash":"s","userSvtGrandHash":5}""",
            )) {
                assertNull(run(body(good, friend("b"), shape)), shape)
            }
        }
        // Keys that are present but hold only empty lists are a known shape: the follower has nothing to show.
        val empty = """{"type":1,"userId":"x","userSvtLeaderHash":[]}"""
        assertEquals(listOf("a"), ids(run(body(good, empty))!!))
    }

    @Test fun invalidUtf8IsUnchanged() {
        val bytes = body(friend("a", main = hit()), friend("b")).toByteArray()
        val marker = "fictional a".toByteArray()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("fictional a")
        val broken = bytes.copyOf().also { it[at + marker.size - 1] = 0xFF.toByte() }
        assertNull(run("", wire = broken))
    }

    @Test fun loneSurrogateEscapeIsUnchanged() {
        val text = body(friend("a", main = hit()), friend("b")).replace("fictional a", """x\ud800y""")
        assertNull(run(text))
        assertNull(run(text.replace("""\ud800""", """\udc00""")))
        // A proper pair is fine.
        assertNotNull(run(text.replace("""\ud800""", """😀""")))
    }

    @Test fun duplicateKeysAreUnchanged() {
        val text = body(friend("a", main = hit()), friend("b"))
        assertNull(run(text.replace(""""serverTime":99""", """"serverTime":99,"serverTime":100""")))
        assertNull(run(text.replace(""""type":1,""", """"type":1,"type":2,""")))
        // The same key in different objects is not a duplicate.
        assertNotNull(run(text))
    }

    @Test fun headersBoundToTheOriginalBodyAreNotRewritten() {
        for (name in listOf("ETag", "etag", "Content-MD5", "Digest", "Trailer")) {
            val raw = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n$name: v\r\n\r\n".toByteArray()
            assertNull(SupportListFilter.rewriteHead(raw, 1), name)
        }
    }

    @Test fun rewriteHeadReplacesLengthAndDropsChunkedKeepingOtherHeaders() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\ncontent-length: 5\r\nContent-Encoding: gzip\r\n\r\n".toByteArray()
        val head = String(assertNotNull(SupportListFilter.rewriteHead(raw, 42)))
        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: 42\r\n\r\n", head)
        assertNull(SupportListFilter.rewriteHead("HTTP/1.1 200 OK\r\nA: b\r\n".toByteArray(), 1))
    }

    /** The five keys the server sets to 1 in a slot with no servant. */
    private val limitKeys = listOf("dispLimitCount", "imageLimitCount", "commandCardLimitCount", "iconLimitCount", "portraitLimitCount")

    /** A filled slot in the real key order; JP adds transformVal and grand adds the extra keys. */
    private fun realSlot(svt: Long, deck: Int, cls: Int, ce: Long = 0, jp: Boolean = false, grand: Boolean = false, user: Long = 555, at: Long = 1708036772): String {
        val parts = mutableListOf<String>()
        parts += """"afterTransformSvtInfo":null"""
        parts += limitKeys.map { """"$it":4""" }
        parts += """"battleVoice":2,"grandSvt":1"""
        if (jp) parts += """"transformVal":5"""
        parts += """"randomLimitCountTargets":[1,2],"classPassive":[{"id":3}]"""
        parts += """"equipTarget1":{"userId":9,"userSvtId":8,"svtId":$ce,"limitCount":4,"lv":100,"exp":7,"hp":1,"atk":2,"skillId1":3,"skillLv1":4,"skillId2":0,"skillLv2":0,"skillId3":0,"skillLv3":0,"addSkills":[1],"updatedAt":12}"""
        if (grand) {
            parts += """"equipTarget2":{"userId":9,"svtId":77,"lv":1,"addSkills":[],"updatedAt":5}"""
            parts += """"equipTarget3":{"userId":9,"svtId":78,"lv":1,"addSkills":[],"updatedAt":5}"""
        }
        parts += """"commandCode":[{"id":1}],"commandCardParam":[1,2,3,4,5],"appendPassiveSkill":[{"skillId":1}],"script":{},"eventSvtPoint":5,"imagePartsGroupInfo":[1]"""
        if (grand) parts += """"grandGraphId":7,"isSetClassBoardBaseIds":[1,2]"""
        parts += """"userId":$user,"supportDeckId":$deck,"classId":$cls,"userSvtId":42,"svtId":$svt,"limitCount":4,"lv":90,"exp":10,"hp":11,"atk":12,"adjustHp":1,"adjustAtk":2"""
        parts += """"skillId1":1,"skillId2":2,"skillId3":3,"skillLv1":10,"skillLv2":10,"skillLv3":10,"treasureDeviceId":5,"treasureDeviceLv":5,"exceedCount":1,"updatedAt":$at"""
        return "{" + parts.joinToString(",") + "}"
    }

    /** What the server sends for a slot with nobody in it, with the same optional keys as [realSlot]. */
    private fun serverEmptySlot(deck: Int, cls: Int, jp: Boolean = false, grand: Boolean = false, user: Long = 555, at: Long = 1708036772): String {
        val zeroEquip = """{"userId":0,"userSvtId":0,"svtId":0,"limitCount":0,"lv":0,"exp":0,"hp":0,"atk":0,"skillId1":0,"skillLv1":0,"skillId2":0,"skillLv2":0,"skillId3":0,"skillLv3":0,"addSkills":[],"updatedAt":0}"""
        val parts = mutableListOf<String>()
        parts += """"afterTransformSvtInfo":null"""
        parts += limitKeys.map { """"$it":1""" }
        parts += """"battleVoice":0,"grandSvt":0"""
        if (jp) parts += """"transformVal":0"""
        parts += """"randomLimitCountTargets":[],"classPassive":[]"""
        parts += """"equipTarget1":$zeroEquip"""
        if (grand) {
            parts += """"equipTarget2":{"userId":0,"svtId":0,"lv":0,"addSkills":[],"updatedAt":0}"""
            parts += """"equipTarget3":{"userId":0,"svtId":0,"lv":0,"addSkills":[],"updatedAt":0}"""
        }
        parts += """"commandCode":[],"commandCardParam":[0,0,0,0,0],"appendPassiveSkill":[],"script":{},"eventSvtPoint":0,"imagePartsGroupInfo":[]"""
        if (grand) parts += """"grandGraphId":0,"isSetClassBoardBaseIds":[]"""
        parts += """"userId":$user,"supportDeckId":$deck,"classId":$cls,"userSvtId":0,"svtId":0,"limitCount":0,"lv":0,"exp":0,"hp":0,"atk":0,"adjustHp":0,"adjustAtk":0"""
        parts += """"skillId1":0,"skillId2":0,"skillId3":0,"skillLv1":0,"skillLv2":0,"skillLv3":0,"treasureDeviceId":0,"treasureDeviceLv":0,"exceedCount":0,"updatedAt":$at"""
        return "{" + parts.joinToString(",") + "}"
    }

    private fun canonical(text: String) = Json.parseToJsonElement(text).toString()

    /** 3 decks x 9 classes of real-shaped slots: empty except [set]. */
    private fun realHash(set: Map<Pair<Int, Int>, String>) =
        (1..3).flatMap { d -> (0..8).map { c -> set[d to c] ?: serverEmptySlot(d, c) } }

    private fun realFollower(id: String, type: Int = 1, main: Map<Pair<Int, Int>, String> = emptyMap(), event: Map<Pair<Int, Int>, String> = emptyMap(), grand: List<String> = emptyList()) =
        """{"type":$type,"userId":"$id","mainSupportDeckIds":[1,2,3],"eventSupportDeckIds":[4,5,6],"userSvtLeaderHash":[${realHash(main).joinToString(",")}],""" +
            """"eventUserSvtLeaderHash":[${realHash(event).joinToString(",")}],"userSvtGrandHash":[${grand.joinToString(",")}]}"""

    private fun slotsOf(follower: kotlinx.serialization.json.JsonElement, key: String) = follower.jsonObject[key]!!.jsonArray

    @Test fun blankFormOfAFilledSlotEqualsTheServersEmptySlotFieldForField() {
        val f = realFollower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1), (3 to 2) to realSlot(999, 3, 2, ce = 5)))
        val r = run(body(f))!!
        val blanked = slotsOf(infos(r)[0], "userSvtLeaderHash")[2 * 9 + 2]
        assertEquals(canonical(serverEmptySlot(3, 2)), blanked.toString())
    }

    @Test fun jpAndGrandOnlyKeysAreBlankedByTheTypeRule() {
        val keep = realSlot(100, 1, 1, jp = true)
        val jpOther = realSlot(999, 1, 2, jp = true, user = 77, at = 5)
        val grandOther = realSlot(999, 2, 3, grand = true, user = 88, at = 6)
        val grandKeep = realSlot(100, 1, 0, grand = true)
        val f = realFollower("a", main = mapOf((1 to 1) to keep, (1 to 2) to jpOther), grand = listOf(grandKeep, grandOther))
        val out = infos(run(body(f))!!)[0]
        assertEquals(canonical(serverEmptySlot(1, 2, jp = true, user = 77, at = 5)), slotsOf(out, "userSvtLeaderHash")[2].toString())
        val grand = slotsOf(out, "userSvtGrandHash")
        assertEquals(canonical(grandKeep), grand[0].toString())
        assertEquals(canonical(serverEmptySlot(2, 3, grand = true, user = 88, at = 6)), grand[1].toString())
        val g = grand[1].jsonObject
        assertEquals("""{"userId":0,"svtId":0,"lv":0,"addSkills":[],"updatedAt":0}""", g["equipTarget2"].toString())
        assertEquals("""{"userId":0,"svtId":0,"lv":0,"addSkills":[],"updatedAt":0}""", g["equipTarget3"].toString())
        assertEquals("0", slotsOf(out, "userSvtLeaderHash")[2].jsonObject["transformVal"].toString())
    }

    @Test fun matchingAndAlreadyEmptySlotsStayTheOriginalObjects() {
        val match = realSlot(100, 2, 4)
        val other = realSlot(999, 2, 5)
        val f = realFollower("a", main = mapOf((2 to 4) to match, (2 to 5) to other), event = mapOf((1 to 0) to realSlot(200, 1, 0)))
        val before = Json.parseToJsonElement(f).jsonObject
        val out = infos(run(body(f))!!)[0].jsonObject
        for (key in listOf("userSvtLeaderHash", "eventUserSvtLeaderHash")) {
            val was = before[key]!!.jsonArray
            val now = out[key]!!.jsonArray
            for (i in was.indices) {
                val blanked = was[i].jsonObject["svtId"]!!.jsonPrimitive.content == "999"
                if (blanked) assertEquals("0", now[i].jsonObject["svtId"]!!.jsonPrimitive.content) else assertEquals(was[i], now[i], "$key $i")
            }
        }
        assertEquals(before["mainSupportDeckIds"], out["mainSupportDeckIds"])
        assertEquals(before["userId"], out["userId"])
    }

    @Test fun aKeptFollowerKeepsAll27SlotsInOrder() {
        val f = realFollower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1), (2 to 2) to realSlot(999, 2, 2), (3 to 8) to realSlot(998, 3, 8)))
        val out = infos(run(body(f))!!)[0]
        val slots = slotsOf(out, "userSvtLeaderHash")
        assertEquals(27, slots.size)
        assertEquals(
            (1..3).flatMap { d -> (0..8).map { c -> d to c } },
            slots.map { it.jsonObject["supportDeckId"]!!.jsonPrimitive.content.toInt() to it.jsonObject["classId"]!!.jsonPrimitive.content.toInt() },
        )
        assertEquals(27, slotsOf(out, "eventUserSvtLeaderHash").size)
    }

    @Test fun servantAndCeBothHaveToMatchInOneSlot() {
        val c = SupportListCriteria(setOf(100L), setOf(7L))
        val both = realSlot(100, 1, 1, ce = 7)
        val servantOnly = realSlot(100, 1, 2, ce = 8)
        val ceOnly = realSlot(999, 1, 3, ce = 7)
        val f = realFollower("a", main = mapOf((1 to 1) to both, (1 to 2) to servantOnly, (1 to 3) to ceOnly))
        val out = slotsOf(infos(run(body(f), c)!!)[0], "userSvtLeaderHash")
        assertEquals(Json.parseToJsonElement(both), out[1])
        assertEquals(canonical(serverEmptySlot(1, 2)), out[2].toString())
        assertEquals(canonical(serverEmptySlot(1, 3)), out[3].toString())
        val none = realFollower("x", main = mapOf((1 to 2) to servantOnly, (1 to 3) to ceOnly))
        assertEquals(listOf("a"), ids(run(body(f, none), c)!!))
    }

    @Test fun countsFollowersAndFilledSlotsBeforeAndAfter() {
        val a = realFollower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1), (1 to 2) to realSlot(999, 1, 2)), event = mapOf((1 to 1) to realSlot(998, 1, 1)))
        val b = realFollower("b", type = 2, main = mapOf((1 to 1) to realSlot(997, 1, 1)))
        val npc = realFollower("n", type = 3, main = mapOf((1 to 1) to realSlot(996, 1, 1)))
        val r = run(body(a, b, npc))!!
        assertEquals(SupportListCounts(3, 2, 4, 1), r.counts)
        assertEquals(listOf("a", "n"), ids(r))
    }

    @Test fun otherTypesKeepTheirNonMatchingSlots() {
        val npc = realFollower("n", type = 3, main = mapOf((1 to 1) to realSlot(996, 1, 1)))
        val a = realFollower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1)), event = mapOf((1 to 2) to realSlot(999, 1, 2)))
        val r = run(body(a, npc))!!
        assertEquals(Json.parseToJsonElement(npc), infos(r)[1])
    }

    @Test fun aSlotThatIsNotAnObjectOrHasNoIntegerSvtIdLeavesEverythingUnchanged() {
        val good = realFollower("a", main = mapOf((1 to 1) to realSlot(999, 1, 1)))
        for (bad in listOf("5", "null", "\"x\"", """{"supportDeckId":1}""", """{"svtId":"100"}""", """{"svtId":1.5}""", """{"svtId":null}""")) {
            val f = """{"type":1,"userId":"x","userSvtLeaderHash":[$bad]}"""
            assertNull(run(body(good, f)), bad)
        }
    }

    @Test fun nothingToChangeIsUnchangedEvenWithFilledSlots() {
        val f = realFollower("a", main = mapOf((1 to 1) to realSlot(100, 1, 1), (1 to 2) to realSlot(200, 1, 2)))
        assertNull(run(body(f, realFollower("b", type = 2, event = mapOf((2 to 2) to realSlot(100, 2, 2))))))
    }
}
