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

    @Test fun keptFollowerIsTheOriginalEntryWithEverySlot() {
        val a = friend("a", main = hit(100, deck = 2, cls = 4))
        val r = run(body(a, friend("b")))!!
        val kept = infos(r)
        assertEquals(1, kept.size)
        assertEquals(Json.parseToJsonElement(a), kept[0])
        assertEquals(27, kept[0].jsonObject["userSvtLeaderHash"]!!.jsonArray.size)
        assertEquals(27, kept[0].jsonObject["eventUserSvtLeaderHash"]!!.jsonArray.size)
        assertEquals(SupportListCounts(2, 1), r.counts)
    }

    @Test fun nonFriendsAreFilteredLikeFriends() {
        val r = run(body(friend("f1"), friend("n1", type = 2), friend("f2", main = hit()), friend("n2", type = 2, main = hit())))!!
        assertEquals(listOf("f2", "n2"), ids(r))
        assertEquals(SupportListCounts(4, 2), r.counts)
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

    @Test fun hashKeyedByDeckIsReadAndKeptWhole() {
        val keyed = """{"type":1,"userId":"a","userSvtLeaderHash":{"z":${slot(999, 1, 1)},"a":${slot(100, 1, 2)},"m":${slot(0, 1, 3)}}}"""
        val r = run(body(keyed, friend("b")))!!
        assertEquals(Json.parseToJsonElement(keyed), infos(r)[0])
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
}
