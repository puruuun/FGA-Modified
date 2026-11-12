package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Receives the backup stream the service sends during `prepare`: copies it to [sink] in small chunks, counting the
 * bytes and hashing them on the way, so nothing larger than one chunk is held in memory.
 */
object ChaldeaBackupReceiver {
    data class Received(val size: Long, val sha256: String)

    fun receive(input: InputStream, sink: OutputStream): Received {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            sink.write(buf, 0, n)
            total += n
        }
        sink.flush()
        return Received(total, md.digest().joinToString("") { "%02x".format(it) })
    }

    /** True when what arrived is exactly what `prepare` announced. */
    fun matches(received: Received, prepared: ChaldeaSyncResult): Boolean =
        prepared.ok && received.size == prepared.bundleSize && received.sha256 == prepared.bundleSha256
}
