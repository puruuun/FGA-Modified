package io.github.fate_grand_automata.scripts.capture

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class BodyDecoderTest {
    private val text = """{"cache":{"replaced":{"userItem":[{"itemId":1,"num":2}]}}}""".toByteArray()

    private fun deflate(raw: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
        DeflaterOutputStream(out, deflater).use { it.write(text) }
        deflater.end()
        return out.toByteArray()
    }

    @Test fun identity() {
        assertContentEquals(text, BodyDecoder.decode(text, null))
        assertContentEquals(text, BodyDecoder.decode(text, "identity"))
    }

    @Test fun gzip() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text) }
        assertContentEquals(text, BodyDecoder.decode(out.toByteArray(), "gzip"))
    }

    @Test fun zlibWrappedDeflate() {
        assertContentEquals(text, BodyDecoder.decode(deflate(false), "deflate"))
        assertContentEquals(text, BodyDecoder.decode(deflate(false), "zlib"))
    }

    @Test fun rawDeflateAsSentByKr() {
        assertContentEquals(text, BodyDecoder.decode(deflate(true), "deflate"))
    }

    @Test fun limitAndUnknownEncodingAreRejected() {
        assertFailsWith<IOException> { BodyDecoder.decode(deflate(true), "deflate", limit = 10) }
        assertFailsWith<IOException> { BodyDecoder.decode(text, "br") }
    }
}
