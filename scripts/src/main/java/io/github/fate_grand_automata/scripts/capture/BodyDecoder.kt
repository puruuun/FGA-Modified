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
