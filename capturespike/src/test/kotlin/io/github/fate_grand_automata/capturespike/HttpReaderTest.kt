package io.github.fate_grand_automata.capturespike

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(5, body.rawBytes)
        assertEquals("hello", String(body.payload!!))
        assertFalse(body.untilClose)
        assertArrayEquals(msg, out.toByteArray())
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
        assertArrayEquals(msg, out.toByteArray())
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
        assertArrayEquals(msg, out.toByteArray())
        assertEquals(json, String(HttpReader.gunzipIfNeeded(body.payload!!, h.header("content-encoding"))))
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
        assertEquals(0, r.copyBody(h2, out, isResponse = false).rawBytes)

        assertNull(r.readHead())
        assertArrayEquals(input, out.toByteArray())

        // no length, no chunking: response runs until close
        val resp = bytes("HTTP/1.1 200 OK\r\n\r\nstreamed")
        val r2 = HttpReader(ByteArrayInputStream(resp))
        val o2 = ByteArrayOutputStream()
        val rh = r2.readHead()!!
        o2.write(rh.raw)
        val b = r2.copyBody(rh, o2, isResponse = true)
        assertTrue(b.untilClose)
        assertArrayEquals(resp, o2.toByteArray())
    }

    @Test
    fun noBodyForHeadAnd304() {
        val msg = bytes("HTTP/1.1 304 Not Modified\r\nContent-Length: 99\r\n\r\n")
        val r = HttpReader(ByteArrayInputStream(msg))
        val h = r.readHead()!!
        assertEquals(0, r.copyBody(h, ByteArrayOutputStream(), isResponse = true).rawBytes)
    }

    @Test
    fun namesAndAccountSkip() {
        val n = CaptureNames.fileName(LocalDateTime.of(2026, 11, 4, 9, 8, 7), 12, "/battle/setup?token=1")
        assertEquals("20261104-090807-12-battle_setup.json", n)
        assertTrue(CaptureNames.isAccountPath("/NMAccount/login?a=1"))
        assertTrue(CaptureNames.isAccountPath("/NetmarbleService/x"))
        assertTrue(CaptureNames.isAccountPath("/NetmarbleUserDataService/x"))
        assertFalse(CaptureNames.isAccountPath("/battle/result"))
    }
}
