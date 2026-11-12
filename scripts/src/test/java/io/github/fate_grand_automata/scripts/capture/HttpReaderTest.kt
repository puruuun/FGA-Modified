package io.github.fate_grand_automata.scripts.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class HttpReaderTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun contentLengthResponse() {
        val msg = bytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        val r = HttpReader(ByteArrayInputStream(msg))
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true, requestMethod = "GET")
        assertEquals(200, head.status)
        assertEquals(5L, body.rawBytes)
        assertEquals("hello", String(body.payload!!))
        assertFalse(body.untilClose)
        assertContentEquals(msg, out.toByteArray())
    }

    @Test
    fun chunkedWithTrailersIsForwardedVerbatimAndDechunked() {
        val msg = bytes(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Trailer: a\r\nY-Trailer: b\r\n\r\n",
        )
        val r = HttpReader(ByteArrayInputStream(msg))
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true, requestMethod = "GET")
        assertEquals("Wikipedia", String(body.payload!!))
        assertContentEquals(msg, out.toByteArray())
        assertNull(r.readHead())
    }

    @Test
    fun gzipBodyIsDecodedOnlyForCapture() {
        val json = """{"a":1}"""
        val gz = gzip(json.toByteArray())
        val head = bytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gz.size}\r\n\r\n")
        val msg = head + gz
        val r = HttpReader(ByteArrayInputStream(msg))
        val out = ByteArrayOutputStream()
        val h = r.readHead()!!
        out.write(h.raw)
        val body = r.copyBody(h, out, isResponse = true)
        assertContentEquals(msg, out.toByteArray())
        assertEquals(json, String(BodyDecoder.decode(body.payload!!, h.header("content-encoding"))))
    }

    @Test
    fun keepAliveTwoExchangesAndUntilClose() {
        val req1 = bytes("POST /a/b?x=1 HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nabc")
        val req2 = bytes("GET /c HTTP/1.1\r\nHost: h\r\n\r\n")
        val input = req1 + req2
        val r = HttpReader(ByteArrayInputStream(input))
        val out = ByteArrayOutputStream()

        val h1 = r.readHead()!!
        out.write(h1.raw)
        assertEquals("POST", h1.method)
        assertEquals("/a/b", CaptureNames.stripQuery(h1.target))
        assertEquals("abc", String(r.copyBody(h1, out, isResponse = false).payload!!))

        val h2 = r.readHead()!!
        out.write(h2.raw)
        assertEquals("GET", h2.method)
        assertEquals(0L, r.copyBody(h2, out, isResponse = false).rawBytes)

        assertNull(r.readHead())
        assertContentEquals(input, out.toByteArray())

        // no length, no chunking: response runs until close
        val resp = bytes("HTTP/1.1 200 OK\r\n\r\nstreamed")
        val r2 = HttpReader(ByteArrayInputStream(resp))
        val o2 = ByteArrayOutputStream()
        val rh = r2.readHead()!!
        o2.write(rh.raw)
        val b = r2.copyBody(rh, o2, isResponse = true)
        assertTrue(b.untilClose)
        assertContentEquals(resp, o2.toByteArray())
    }

    @Test
    fun noBodyForHeadAnd304() {
        val msg = bytes("HTTP/1.1 304 Not Modified\r\nContent-Length: 99\r\n\r\n")
        val r = HttpReader(ByteArrayInputStream(msg))
        val h = r.readHead()!!
        assertEquals(0L, r.copyBody(h, ByteArrayOutputStream(), isResponse = true).rawBytes)
    }

    @Test
    fun namesAndAccountSkip() {
        assertTrue(CaptureNames.isAccountPath("/NMAccount/login?a=1"))
        assertTrue(CaptureNames.isAccountPath("/NetmarbleService/x"))
        assertTrue(CaptureNames.isAccountPath("/NetmarbleUserDataService/x"))
        assertFalse(CaptureNames.isAccountPath("/battle/result"))
    }

    @Test
    fun outOfMemoryWhileWritingDropsCaptureAndForwardsWireBytesForFixedLength() {
        val oomOnWrite = {
            object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw OutOfMemoryError("synthetic OOM")
                }
            }
        }
        val msg = bytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
        val r = HttpReader(ByteArrayInputStream(msg), bufferFactory = oomOnWrite)
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true, requestMethod = "GET")

        assertContentEquals(msg, out.toByteArray())
        assertNull(body.payload)
        assertEquals(DropReason.OutOfMemory, body.dropped)
    }

    @Test
    fun outOfMemoryWhileWritingDropsCaptureAndForwardsWireBytesForChunked() {
        val oomOnWrite = {
            object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw OutOfMemoryError("synthetic OOM")
                }
            }
        }
        val chunkedMsg = bytes(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        )
        val r = HttpReader(ByteArrayInputStream(chunkedMsg), bufferFactory = oomOnWrite)
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true, requestMethod = "GET")

        assertContentEquals(chunkedMsg, out.toByteArray())
        assertNull(body.payload)
        assertEquals(DropReason.OutOfMemory, body.dropped)
    }

    @Test
    fun outOfMemoryWhileWritingDropsCaptureAndForwardsWireBytesForUntilClose() {
        val oomOnWrite = {
            object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw OutOfMemoryError("synthetic OOM")
                }
            }
        }
        val untilCloseMsg = bytes("HTTP/1.1 200 OK\r\n\r\nstreamed data until disconnect")
        val r = HttpReader(ByteArrayInputStream(untilCloseMsg), bufferFactory = oomOnWrite)
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true)

        assertContentEquals(untilCloseMsg, out.toByteArray())
        assertTrue(body.untilClose)
        assertNull(body.payload)
        assertEquals(DropReason.OutOfMemory, body.dropped)
    }

    @Test
    fun outOfMemoryInToByteArrayReportsDroppedAndPreservesForwarding() {
        val oomOnToByteArray = {
            object : ByteArrayOutputStream() {
                override fun toByteArray(): ByteArray = throw OutOfMemoryError("synthetic toByteArray OOM")
            }
        }
        val msg = bytes("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest")
        val r = HttpReader(ByteArrayInputStream(msg), bufferFactory = oomOnToByteArray)
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true)

        assertContentEquals(msg, out.toByteArray())
        assertNull(body.payload)
        assertEquals(DropReason.OutOfMemory, body.dropped)
    }

    @Test
    fun readerSurvivesOutOfMemoryAndNextMessageOnSameReaderSucceeds() {
        var failFirst = true
        val factory = {
            if (failFirst) {
                failFirst = false
                object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        throw OutOfMemoryError("synthetic OOM")
                    }
                }
            } else {
                ByteArrayOutputStream()
            }
        }
        val msg1 = bytes("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nfirst")
        val msg2 = bytes("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nsecond")
        val r = HttpReader(ByteArrayInputStream(msg1 + msg2), bufferFactory = factory)

        val out1 = ByteArrayOutputStream()
        val head1 = r.readHead()!!
        out1.write(head1.raw)
        val body1 = r.copyBody(head1, out1, isResponse = true)
        assertContentEquals(msg1, out1.toByteArray())
        assertNull(body1.payload)
        assertEquals(DropReason.OutOfMemory, body1.dropped)

        val out2 = ByteArrayOutputStream()
        val head2 = r.readHead()!!
        out2.write(head2.raw)
        val body2 = r.copyBody(head2, out2, isResponse = true)
        assertContentEquals(msg2, out2.toByteArray())
        assertEquals("second", String(body2.payload!!))
        assertNull(body2.dropped)
    }

    @Test
    fun overflowAboveMaxCaptureReportsTooLarge() {
        val msg = bytes("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789")
        val r = HttpReader(ByteArrayInputStream(msg), maxCapture = 5)
        val out = ByteArrayOutputStream()
        val head = r.readHead()!!
        out.write(head.raw)
        val body = r.copyBody(head, out, isResponse = true)

        assertContentEquals(msg, out.toByteArray())
        assertNull(body.payload)
        assertEquals(DropReason.TooLarge, body.dropped)
    }
}