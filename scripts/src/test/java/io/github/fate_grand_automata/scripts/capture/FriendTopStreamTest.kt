package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

/** Synthetic bodies only: made-up ids and names. */
class FriendTopStreamTest {
    private val target = "https://yfgo-game.netmarble.com/friend/top"
    private val requestUser = """{"userId":"9001","userName":"Requester","userLv":1}"""

    private fun friend(i: Int) =
        """{"userId":"${1000 + i}","userName":"Friend $i","userLv":${50 + i % 70},"friendCode":"${100000000 + i}",
           "message":"PRIVATE","mainSupportDeckIds":[101,102,103],"eventSupportDeckIds":[201],
           "userSvtLeaderHash":[{"supportDeckId":101,"classId":1,"svtId":${500 + i},"lv":90,"skillLv1":10,"skillLv2":9,"skillLv3":8,
             "appendPassiveSkill":[{"skillId":3001000,"skillLv":5}],"equipTarget1":{"svtId":9000,"limitCount":4,"lv":100},"updatedAt":${2000 + i}}],
           "eventUserSvtLeaderHash":[]}"""

    private fun body(
        friends: Int = 200, ownRows: Int = 300, bigRows: Int = 2000, owner: String = "7001",
        ownIds: List<String> = listOf(owner),
    ): String {
        val friendsJson = (0 until friends).joinToString(",") { friend(it) } + ",$requestUser"
        val tbl = (0 until friends).joinToString(",") { """{"userId":"$owner","friendId":"${1000 + it}","status":0}""" } +
            """,{"userId":"$owner","friendId":"9001","status":1},{"userId":"9002","friendId":"$owner","status":2}"""
        val svt = (0 until ownRows).joinToString(",") {
            """{"userId":"${ownIds[it % ownIds.size]}","svtId":$it,"padding":"${"x".repeat(200)}"}"""
        }
        val big = (0 until bigRows).joinToString(",") { """{"id":$it,"blob":"${"y".repeat(300)}","nested":{"a":[1,2,{"b":$it}]}}""" }
        return """{"response":[{"nid":"friend_top","resCode":"00"}],"cache":{
            "replaced":{"tblFriend":[$tbl],"unrelatedA":[$big]},
            "updated":{"otherUserGame":[$friendsJson],"userSvt":[$svt],"unrelatedB":[$big],"userGame":[{"userId":"$owner","name":"Master"}]},
            "serverTime":1234567}}"""
    }

    private fun full(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun streamed(text: String) = FriendTopStream.parse(text.byteInputStream())

    @Test fun streamedSlotsPreserveGrandAndDiagnosticFields() {
        val text = body(friends = 3, bigRows = 0).replace("\"lv\":90", "\"grandSvt\":1,\"lv\":90")
        assertSame(text)
        val reduced = streamed(text)
        assertTrue(FriendRoster.fromFriendTop(reduced)!!.friends.values.flatMap { it.slots }.all { it.grand })
        assertEquals(full(text)["cache"]!!.jsonObject["updated"]!!.jsonObject["otherUserGame"],
            reduced["cache"]!!.jsonObject["updated"]!!.jsonObject["otherUserGame"])
    }

    private fun assertSame(text: String) {
        val a = full(text)
        val b = streamed(text)
        assertEquals(FriendRoster.fromFriendTop(a, 0), FriendRoster.fromFriendTop(b, 0))
        assertEquals(AccountRouting.friendOwner(a), AccountRouting.friendOwner(b))
        assertEquals(AccountRouting.bodyUserId(target, a), AccountRouting.bodyUserId(target, b))
        assertEquals(
            FriendRoster.update(null, "friend/top", a, 77, allowNew = true),
            FriendRoster.update(null, "friend/top", b, 77, allowNew = true),
        )
    }

    @Test fun streamedRosterAndRoutingMatchTheFullTree() {
        val text = body()
        assertSame(text)
        val roster = FriendRoster.fromFriendTop(streamed(text))!!
        assertEquals(200, roster.friends.size)
        assertFalse("9001" in roster.friends)
        assertEquals(1234567, roster.updatedAt)
        assertEquals("7001", AccountRouting.friendOwner(streamed(text)))
    }

    private fun encode(text: String, how: String): ByteArray {
        val out = ByteArrayOutputStream()
        val sink = when (how) {
            "gzip" -> GZIPOutputStream(out)
            "zlib" -> DeflaterOutputStream(out)
            else -> DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true))
        }
        sink.use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    @Test fun everyEncodingGivesTheSameReducedBody() {
        val text = body(friends = 30, bigRows = 200)
        val expected = streamed(text)
        for ((how, header) in listOf("raw" to "deflate", "zlib" to "deflate", "gzip" to "gzip", "zlib" to "zlib")) {
            val parsed = BodyDecoder.decodeStream(encode(text, how), header).use { FriendTopStream.parse(it) }
            assertEquals(expected, parsed, "$how/$header")
        }
        assertEquals(expected, BodyDecoder.decodeStream(text.toByteArray(), null).use { FriendTopStream.parse(it) })
        // Raw deflate through the streamed path gives the same roster as today's buffered decode.
        val raw = encode(text, "raw")
        assertEquals(
            FriendRoster.fromFriendTop(full(String(BodyDecoder.decode(raw, "deflate")))),
            FriendRoster.fromFriendTop(BodyDecoder.decodeStream(raw, "deflate").use { FriendTopStream.parse(it) }),
        )
    }

    @Test fun skippedTablesAreNotKept() {
        val reduced = streamed(body(friends = 5, ownRows = 5000, bigRows = 3000))
        fun count(e: JsonElement): Int = when (e) {
            is JsonObject -> 1 + e.values.sumOf(::count)
            is JsonArray -> 1 + e.sumOf(::count)
            else -> 1
        }
        val cache = reduced.getValue("cache").jsonObject
        assertEquals(setOf("replaced", "updated", "serverTime"), cache.keys)
        assertEquals(setOf("tblFriend"), cache.getValue("replaced").jsonObject.keys)
        // 5000 userSvt rows collapse to one id row; the big unrelated tables are gone.
        assertEquals(1, cache.getValue("updated").jsonObject.getValue("userSvt").jsonArray.size)
        assertFalse("unrelatedB" in cache.getValue("updated").jsonObject)
        assertTrue(count(reduced) < 500, "kept ${count(reduced)} elements")
    }

    @Test fun aHugeBodyIsSkippedWithoutBeingHeld() {
        // About 190 MB generated lazily; it is never stored, and only the small slices are kept.
        val rows = 600_000
        val row = """{"id":1,"blob":"${"z".repeat(300)}"},""".toByteArray()
        val head = """{"cache":{"replaced":{"tblFriend":[]},"updated":{"otherUserGame":[],"unrelated":[""".toByteArray()
        val tail = """{"id":0}]},"serverTime":5}}""".toByteArray()
        val input = object : InputStream() {
            private var current = head
            private var pos = 0
            private var emitted = 0
            private var state = 0
            override fun read(): Int {
                while (pos >= current.size) {
                    when {
                        state == 0 -> { state = 1; current = row; emitted++ }
                        state == 1 && emitted < rows -> { current = row; emitted++ }
                        state == 1 -> { current = tail; state = 2 }
                        else -> return -1
                    }
                    pos = 0
                }
                return current[pos++].toInt() and 0xFF
            }
        }
        val reduced = FriendTopStream.parse(input)
        assertEquals(5L, reduced.getValue("cache").jsonObject.getValue("serverTime").jsonPrimitive.long)
    }

    @Test fun guardsStillRejectOnStreamedSlices() {
        // A single tblFriend row has no shared owner.
        val single = streamed(
            """{"cache":{"replaced":{"tblFriend":[{"userId":"7001","friendId":"1"}]},"updated":{"otherUserGame":[{"userId":"1"}]}}}""",
        )
        assertNull(AccountRouting.friendOwner(single))
        // Rows without a common id.
        val mixed = streamed("""{"cache":{"replaced":{"tblFriend":[{"userId":"1","friendId":"2"},{"userId":"3","friendId":"4"}]}}}""")
        assertNull(AccountRouting.friendOwner(mixed))
        // Own tables that disagree on the owner.
        val conflictText = body(friends = 3, ownRows = 10, ownIds = listOf("7001", "7002"))
        assertEquals(AccountRouting.CONFLICT, AccountRouting.bodyUserId(target, streamed(conflictText)))
        assertSame(conflictText)
        // Owner differs from the own rows: both stay visible to the caller guard.
        val other = streamed(
            body(friends = 3, ownIds = listOf("7002")).replace("\"userGame\":[{\"userId\":\"7001\"", "\"userGame\":[{\"userId\":\"7002\""),
        )
        assertEquals("7001", AccountRouting.friendOwner(other))
        assertEquals("7002", AccountRouting.bodyUserId(target, other))
        // Missing otherUserGame still means unreadable.
        assertNull(FriendRoster.fromFriendTop(streamed("""{"cache":{"replaced":{"tblFriend":[]}}}""")))
    }

    @Test fun malformedAndOversizedBodiesAreRejected() {
        assertFails { streamed("""{"cache":{"updated":{"otherUserGame":[""") }
        val bomb = encode("""{"cache":{"x":"${"a".repeat(100_000)}"}}""", "gzip")
        assertFailsWith<IOException> { BodyDecoder.decodeStream(bomb, "gzip", limit = 1000).use { it.readBytes() } }
        assertFailsWith<IOException> { BodyDecoder.decodeStream(ByteArray(2000), null, limit = 1000).use { it.readBytes() } }
        assertFailsWith<IOException> { BodyDecoder.decodeStream(ByteArray(1), "br") }
    }
}
