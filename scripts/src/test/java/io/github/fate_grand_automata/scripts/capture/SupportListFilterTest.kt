package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    private fun slot(svt: Long, ce: Long = 0, ce2: Long = 0, deck: Int = 1) =
        """{"supportDeckId":$deck,"classId":1,"svtId":$svt,"lv":90,"equipTarget1":{"svtId":$ce,"limitCount":4,"lv":100},"equipTarget2":{"svtId":$ce2}}"""

    private fun friend(
        id: String,
        type: Int = 1,
        main: List<String> = emptyList(),
        event: List<String> = emptyList(),
        grand: List<String> = emptyList(),
    ) = """{"type":$type,"userId":"$id","userName":"fictional $id","userSvtLeaderHash":[${main.joinToString(",")}],""" +
        """"eventUserSvtLeaderHash":[${event.joinToString(",")}],"userSvtGrandHash":[${grand.joinToString(",")}],"extra":1.50}"""

    private fun body(vararg friends: String, sign: String = "abc123") =
        """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"userId":"me","followerInfo":[${friends.joinToString(",")}]}],"other":[1,2]},"serverTime":99},"sign":"$sign"}"""

    private val criteria = SupportListCriteria(setOf(100L, 200L))

    private fun run(text: String, c: SupportListCriteria = criteria, encoding: String? = null, wire: ByteArray = text.toByteArray()) =
        SupportListFilter.apply(wire, encoding, c)

    private fun infos(r: SupportListFiltered, encoding: String? = null): JsonArray {
        val root = Json.parseToJsonElement(String(BodyDecoder.decode(r.wire, encoding))).jsonObject
        return root["cache"]!!.jsonObject["updated"]!!.jsonObject["userFollower"]!!.jsonArray[0].jsonObject["followerInfo"]!!.jsonArray
    }

    private fun gzip(text: String) =
        ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun keepsMatchingSlotsAndFriendsAndRemovesOthers() {
        val r = run(body(friend("a", main = listOf(slot(100), slot(999)), event = listOf(slot(200, deck = 2))), friend("b", main = listOf(slot(999)))))!!
        val kept = infos(r)
        assertEquals(1, kept.size)
        val a = kept[0].jsonObject
        assertEquals(1, a["userSvtLeaderHash"]!!.jsonArray.size)
        assertEquals(1, a["eventUserSvtLeaderHash"]!!.jsonArray.size)
        assertEquals(SupportListCounts(2, 1, 4, 2), r.counts)
    }

    @Test fun keepsNonFriendEntriesUnchanged() {
        val npc = friend("npc", type = 3, main = listOf(slot(999)))
        val r = run(body(friend("a", main = listOf(slot(100), slot(999))), npc))!!
        val kept = infos(r)
        assertEquals(2, kept.size)
        assertEquals(Json.parseToJsonElement(npc), kept[1])
    }

    @Test fun nothingToRemoveIsUnchanged() {
        assertNull(run(body(friend("a", main = listOf(slot(100))))))
    }

    @Test fun grandSlotsUseCe1AndCe3WhileBondCeStaysOut() {
        fun grand(svt: Long, e1: Long, e2: Long, e3: Long) =
            """{"grandGraphId":1,"svtId":$svt,"equipTarget1":{"svtId":$e1},"equipTarget2":{"svtId":$e2},"equipTarget3":{"svtId":$e3}}"""
        val c = SupportListCriteria(setOf(100L), setOf(7L))
        val grands = listOf(grand(100, 7, 0, 0), grand(100, 0, 0, 7), grand(100, 0, 7, 0), grand(999, 7, 0, 0))
        val r = run(body(friend("a", grand = grands)), c)!!
        assertEquals(2, infos(r)[0].jsonObject["userSvtGrandHash"]!!.jsonArray.size)
        assertEquals(SupportListCounts(1, 1, 4, 2), r.counts)
    }

    @Test fun eitherCeOfASlotMatches() {
        val c = SupportListCriteria(setOf(100L), setOf(7L))
        val r = run(body(friend("a", main = listOf(slot(100, ce = 7), slot(100, ce2 = 7), slot(100, ce = 8, ce2 = 9)))), c)!!
        assertEquals(2, infos(r)[0].jsonObject["userSvtLeaderHash"]!!.jsonArray.size)
    }

    @Test fun hashKeyedByDeckKeepsKeysAndOrder() {
        val keyed = """{"type":1,"userId":"a","userSvtLeaderHash":{"z":${slot(999)},"a":${slot(100)},"m":${slot(200)}}}"""
        val r = run(body(keyed))!!
        assertEquals(listOf("a", "m"), infos(r)[0].jsonObject["userSvtLeaderHash"]!!.jsonObject.keys.toList())
    }

    @Test fun signAndEveryOtherKeyAreKeptInOrderWithNumbersAsRead() {
        val r = run(body(friend("a", main = listOf(slot(100), slot(999))), sign = "keep-me"))!!
        val text = String(r.wire)
        assertTrue(text.startsWith("""{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"userId":"me","followerInfo":[{"type":1,"userId":"a","userName":"fictional a","userSvtLeaderHash":"""))
        assertTrue(text.endsWith(""","serverTime":99},"sign":"keep-me"}"""))
        assertTrue(text.contains(""""other":[1,2]"""))
        assertTrue(text.contains(""""extra":1.50"""))
        val odd = run(body(friend("a", main = listOf(slot(100), slot(999)))).replace(""""other":[1,2]""", """"other":[1e5,-0,12345678901234567890]"""))!!
        assertTrue(String(odd.wire).contains(""""other":[1e5,-0,12345678901234567890]"""))
    }

    @Test fun noCriteriaIsUnchanged() {
        assertNull(run(body(friend("a", main = listOf(slot(999)))), SupportListCriteria(emptySet())))
    }

    @Test fun unknownShapeAndInvalidJsonAreUnchanged() {
        assertNull(run("""{"cache":{"updated":{"userFollower":"x"}}}"""))
        assertNull(run("""{"cache":{}}"""))
        assertNull(run("[1,2]"))
        assertNull(run("not json"))
        assertNull(run(body(friend("a", main = listOf(slot(999)))).dropLast(5)))
    }

    @Test fun unknownEncodingIsUnchanged() {
        assertNull(run(body(friend("a", main = listOf(slot(999)))), encoding = "br"))
        assertNull(run(body(friend("a", main = listOf(slot(999)))), encoding = "gzip, br"))
    }

    @Test fun bodyOverTheWireLimitIsUnchanged() {
        val pad = " ".repeat(SupportListFilter.MAX_WIRE_BYTES)
        assertNull(run(body(friend("a", main = listOf(slot(999)))) + pad))
    }

    @Test fun decodedBodyOverTheLimitIsUnchanged() {
        val text = body(friend("a", main = listOf(slot(999)))) + " ".repeat(SupportListFilter.MAX_DECODED_BYTES)
        val gz = gzip(text)
        assertTrue(gz.size < SupportListFilter.MAX_WIRE_BYTES)
        assertNull(run("", encoding = "gzip", wire = gz))
    }

    @Test fun gzipAndBothDeflateFlavoursAreEncodedAgain() {
        val text = body(friend("a", main = listOf(slot(100), slot(999))))
        val g = run("", encoding = "gzip", wire = gzip(text))!!
        assertEquals(1, infos(g, "gzip")[0].jsonObject["userSvtLeaderHash"]!!.jsonArray.size)
        for (raw in listOf(false, true)) {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
            val wire = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o, d).use { it.write(text.toByteArray()) } }.toByteArray()
            d.end()
            val r = run("", encoding = "deflate", wire = wire)!!
            assertEquals(1, infos(r, "deflate")[0].jsonObject["userSvtLeaderHash"]!!.jsonArray.size)
            // The same flavour goes back: raw stays raw, zlib stays zlib.
            val zlibHeader = (r.wire[0].toInt() and 0x0F) == 8 &&
                (((r.wire[0].toInt() and 0xFF) shl 8) or (r.wire[1].toInt() and 0xFF)) % 31 == 0
            assertEquals(!raw, zlibHeader)
        }
    }

    @Test fun rewriteHeadReplacesLengthAndDropsChunkedKeepingOtherHeaders() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\ncontent-length: 5\r\nContent-Encoding: gzip\r\n\r\n".toByteArray()
        val head = String(assertNotNull(SupportListFilter.rewriteHead(raw, 42)))
        assertEquals("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: 42\r\n\r\n", head)
        assertNull(SupportListFilter.rewriteHead("HTTP/1.1 200 OK\r\nA: b\r\n".toByteArray(), 1))
    }
}
