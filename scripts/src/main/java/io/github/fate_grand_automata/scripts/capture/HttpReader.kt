package io.github.fate_grand_automata.scripts.capture

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
 * Reason why response body capture was dropped.
 */
enum class DropReason { TooLarge, OutOfMemory }

/**
 * @param rawBytes bytes consumed from the wire for this body (including chunk framing).
 * @param payload the body without chunk framing, or null when it exceeded the capture limit or ran out of memory.
 * @param untilClose the body was delimited by connection close, so the connection cannot be reused.
 * @param dropped the reason why capture was dropped, or null when capture succeeded or was not requested.
 */
class BodyResult(
    val rawBytes: Long,
    val payload: ByteArray?,
    val untilClose: Boolean,
    val dropped: DropReason? = null,
)

/**
 * Reads HTTP/1.1 messages and tees every consumed byte to an output stream unchanged.
 */
class HttpReader(
    input: InputStream,
    private val maxCapture: Int = 32 * 1024 * 1024,
    private val bufferFactory: () -> ByteArrayOutputStream = { ByteArrayOutputStream() },
) {
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
        captureBody: Boolean = true,
    ): BodyResult {
        if (isResponse) {
            val s = head.status
            if (requestMethod.equals("HEAD", true) || s in 100..199 || s == 204 || s == 304) {
                return BodyResult(0, ByteArray(0), false)
            }
        }
        val sink = BodySink(captureBody)
        val te = head.header("Transfer-Encoding")
        val cl = head.header("Content-Length")?.toLongOrNull()
        return when {
            te != null && te.contains("chunked", ignoreCase = true) -> copyChunked(out, sink)
            cl != null -> copyFixed(cl, out, sink)
            isResponse -> copyUntilClose(out, sink)
            else -> BodyResult(0, ByteArray(0), false)
        }
    }

    private inner class BodySink(private val capturing: Boolean) {
        private var cap: ByteArrayOutputStream? = if (capturing) {
            try {
                bufferFactory()
            } catch (_: OutOfMemoryError) {
                null
            }
        } else null

        var dropped: DropReason? = if (capturing && cap == null) DropReason.OutOfMemory else null
            private set

        fun add(b: ByteArray, n: Int) {
            if (!capturing || dropped != null) return
            val c = cap ?: return
            if (c.size() + n > maxCapture) {
                dropped = DropReason.TooLarge
                c.reset()
                cap = null
                return
            }
            try {
                c.write(b, 0, n)
            } catch (_: OutOfMemoryError) {
                dropped = DropReason.OutOfMemory
                runCatching { c.reset() }
                cap = null
            }
        }

        fun finish(rawBytes: Long, untilClose: Boolean): BodyResult {
            if (!capturing) {
                return BodyResult(rawBytes, null, untilClose, null)
            }
            val d = dropped
            if (d != null) {
                return BodyResult(rawBytes, null, untilClose, d)
            }
            val c = cap
            val payload = try {
                c?.toByteArray()
            } catch (_: OutOfMemoryError) {
                runCatching { c?.reset() }
                cap = null
                return BodyResult(rawBytes, null, untilClose, DropReason.OutOfMemory)
            }
            return BodyResult(rawBytes, payload, untilClose, null)
        }
    }

    private fun copyFixed(length: Long, out: OutputStream, sink: BodySink): BodyResult {
        copyExact(length, out) { b, n -> sink.add(b, n) }
        return sink.finish(length, false)
    }

    private fun copyExact(length: Long, out: OutputStream, sink: (ByteArray, Int) -> Unit) {
        require(length >= 0) { "Negative body length" }
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

    private fun copyUntilClose(out: OutputStream, sink: BodySink): BodyResult {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            sink.add(buf, n)
            total += n
        }
        return sink.finish(total, true)
    }

    private fun copyChunked(out: OutputStream, sink: BodySink): BodyResult {
        var raw = 0L
        while (true) {
            val line = readLineRaw()
            out.write(line)
            raw += line.size
            val size = String(line, Charsets.ISO_8859_1).trim().substringBefore(';').trim().toLong(16)
            require(size >= 0) { "Negative chunk size" }
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
            copyExact(size, out) { b, n -> sink.add(b, n) }
            raw += size
            val crlf = readLineRaw()
            out.write(crlf)
            raw += crlf.size
        }
        return sink.finish(raw, false)
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

    }
}
