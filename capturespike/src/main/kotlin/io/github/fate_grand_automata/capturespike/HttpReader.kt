package io.github.fate_grand_automata.capturespike

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/** The raw head (start line + headers + blank line) of an HTTP/1.1 message. */
class HttpHead(val raw: ByteArray) {
    private val lines: List<String> = String(raw, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }

    val startLine: String = lines.firstOrNull().orEmpty()
    val headers: List<Pair<String, String>> = lines.drop(1).mapNotNull {
        val i = it.indexOf(':')
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Request: method. */
    val method: String get() = startLine.substringBefore(' ')

    /** Request: target as sent. */
    val target: String get() = startLine.split(' ').getOrElse(1) { "" }

    /** Response: status code, or -1. */
    val status: Int get() = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
}

/**
 * @param rawBytes bytes consumed from the wire for this body (including chunk framing).
 * @param payload the body without chunk framing, or null when it exceeded the capture limit.
 * @param untilClose the body was delimited by connection close, so the connection cannot be reused.
 */
class BodyResult(val rawBytes: Long, val payload: ByteArray?, val untilClose: Boolean)

/**
 * Reads HTTP/1.1 messages and tees every consumed byte to an output stream unchanged.
 */
class HttpReader(input: InputStream, private val maxCapture: Int = 32 * 1024 * 1024) {
    private val input = BufferedInputStream(input, 16 * 1024)

    /** Returns null on a clean EOF before the first byte of a message. */
    fun readHead(): HttpHead? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("EOF inside HTTP head")
            }
            // Tolerate stray CRLF between messages.
            if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
            buf.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return HttpHead(buf.toByteArray())
            if (buf.size() > MAX_HEAD) throw IOException("HTTP head too large")
        }
    }

    /**
     * Copies the body that follows [head] to [out] byte for byte.
     * @param isResponse true when [head] is a response.
     * @param requestMethod for responses, the method of the matching request.
     */
    fun copyBody(
        head: HttpHead,
        out: OutputStream,
        isResponse: Boolean,
        requestMethod: String? = null,
    ): BodyResult {
        if (isResponse) {
            val s = head.status
            if (requestMethod.equals("HEAD", true) || s in 100..199 || s == 204 || s == 304) {
                return BodyResult(0, ByteArray(0), false)
            }
        }
        val te = head.header("Transfer-Encoding")
        val cl = head.header("Content-Length")?.toLongOrNull()
        return when {
            te != null && te.contains("chunked", ignoreCase = true) -> copyChunked(out)
            cl != null -> copyFixed(cl, out)
            isResponse -> copyUntilClose(out)
            else -> BodyResult(0, ByteArray(0), false)
        }
    }

    private fun capture() = ByteArrayOutputStream()

    private fun add(cap: ByteArrayOutputStream, state: BooleanArray, b: ByteArray, n: Int) {
        if (state[0]) return
        if (cap.size() + n > maxCapture) {
            state[0] = true
            cap.reset()
        } else {
            cap.write(b, 0, n)
        }
    }

    private fun result(cap: ByteArrayOutputStream, overflow: BooleanArray, raw: Long, untilClose: Boolean) =
        BodyResult(raw, if (overflow[0]) null else cap.toByteArray(), untilClose)

    private fun copyFixed(length: Long, out: OutputStream): BodyResult {
        val cap = capture()
        val overflow = BooleanArray(1)
        copyExact(length, out) { b, n -> add(cap, overflow, b, n) }
        return result(cap, overflow, length, false)
    }

    private fun copyExact(length: Long, out: OutputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(8192)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("EOF inside body")
            out.write(buf, 0, n)
            sink(buf, n)
            left -= n
        }
    }

    private fun copyUntilClose(out: OutputStream): BodyResult {
        val cap = capture()
        val overflow = BooleanArray(1)
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            add(cap, overflow, buf, n)
            total += n
        }
        return result(cap, overflow, total, true)
    }

    private fun copyChunked(out: OutputStream): BodyResult {
        val cap = capture()
        val overflow = BooleanArray(1)
        var raw = 0L
        while (true) {
            val line = readLineRaw()
            out.write(line)
            raw += line.size
            val size = String(line, Charsets.ISO_8859_1).trim().substringBefore(';').trim().toLong(16)
            if (size == 0L) {
                // trailers until an empty line
                while (true) {
                    val t = readLineRaw()
                    out.write(t)
                    raw += t.size
                    if (String(t, Charsets.ISO_8859_1).isBlank()) break
                }
                break
            }
            copyExact(size, out) { b, n -> add(cap, overflow, b, n) }
            raw += size
            val crlf = readLineRaw()
            out.write(crlf)
            raw += crlf.size
        }
        return result(cap, overflow, raw, false)
    }

    /** One line including its terminator. */
    private fun readLineRaw(): ByteArray {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("EOF inside chunked body")
            buf.write(b)
            if (b == '\n'.code) return buf.toByteArray()
            if (buf.size() > MAX_HEAD) throw IOException("line too long")
        }
    }

    companion object {
        const val MAX_HEAD = 64 * 1024

        fun gunzipIfNeeded(payload: ByteArray, contentEncoding: String?): ByteArray =
            if (contentEncoding != null && contentEncoding.contains("gzip", ignoreCase = true) && payload.isNotEmpty()) {
                GZIPInputStream(payload.inputStream()).use { it.readBytes() }
            } else {
                payload
            }
    }
}
