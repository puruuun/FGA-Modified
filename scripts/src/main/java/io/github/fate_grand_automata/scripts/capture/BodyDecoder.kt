package io.github.fate_grand_automata.scripts.capture

import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Decode a copy only; the HTTP relay always forwards the original wire bytes. */
object BodyDecoder {
    const val MAX_DECODED = 32 * 1024 * 1024

    fun decode(payload: ByteArray, contentEncoding: String?, limit: Int = MAX_DECODED): ByteArray {
        require(limit >= 0)
        var result = payload
        for (encoding in contentEncoding.orEmpty().split(',').asReversed()) {
            result = when (encoding.trim().lowercase()) {
                "", "identity" -> result.also { if (it.size > limit) throw IOException("Body too large") }
                "gzip", "x-gzip" -> GZIPInputStream(result.inputStream()).use { bounded(it, limit) }
                "zlib" -> inflate(result, false, limit)
                "deflate" -> {
                    // RFC deflate uses a zlib wrapper; KR Unity sends raw RFC 1951 instead.
                    try { inflate(result, false, limit) } catch (_: IOException) { inflate(result, true, limit) }
                }
                else -> throw IOException("Unsupported content encoding")
            }
        }
        return result
    }

    /**
     * Streaming counterpart of [decode]: nothing is buffered, and reading past [limit] decoded bytes throws
     * an IOException. Same encodings as [decode], including the raw-deflate fallback.
     */
    fun decodeStream(payload: ByteArray, contentEncoding: String?, limit: Int = MAX_DECODED): InputStream {
        require(limit >= 0)
        var stream: InputStream = payload.inputStream()
        for (encoding in contentEncoding.orEmpty().split(',').asReversed()) {
            val source = stream
            stream = when (encoding.trim().lowercase()) {
                "", "identity" -> source
                "gzip", "x-gzip" -> GZIPInputStream(source)
                "zlib" -> InflaterInputStream(source)
                "deflate" -> deflateStream(source)
                else -> throw IOException("Unsupported content encoding")
            }
        }
        return Limited(stream, limit)
    }

    private fun deflateStream(source: InputStream): InputStream {
        // A two-byte probe tells a zlib header from raw RFC 1951 (KR Unity), as decode() does by retrying.
        val buffered = java.io.BufferedInputStream(source, 8192)
        buffered.mark(2)
        val head = ByteArray(2)
        val count = buffered.read(head)
        buffered.reset()
        val zlib = count == 2 && (head[0].toInt() and 0x0F) == 8 &&
            (((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)) % 31 == 0
        return if (zlib) InflaterInputStream(buffered) else InflaterInputStream(buffered, Inflater(true))
    }

    private class Limited(input: InputStream, private val limit: Int) : java.io.FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++total > limit) throw IOException("Body too large")
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { total += n; if (total > limit) throw IOException("Body too large") }
            return n
        }
    }

    private fun inflate(bytes: ByteArray, raw: Boolean, limit: Int): ByteArray {
        val inflater = Inflater(raw)
        return try {
            InflaterInputStream(bytes.inputStream(), inflater).use { bounded(it, limit) }
        } finally { inflater.end() }
    }

    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray()
            if (count > limit - out.size()) throw IOException("Body too large")
            out.write(buffer, 0, count)
        }
    }
}
