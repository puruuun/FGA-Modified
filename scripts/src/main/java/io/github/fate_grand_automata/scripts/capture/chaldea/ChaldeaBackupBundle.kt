package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * What `prepare` streams to FGA: both Chaldea files in one stream, so a crash can later restore each file to its
 * own original (amendment F). Layout: the 4 bytes `FGB1`, then for `userdata.json` and `userdata.json.bak` in this
 * order one flag byte (0 absent, 1 present) and, when present, an 8 byte length followed by the bytes.
 */
object ChaldeaBackupBundle {
    const val JSON = "userdata.json"
    const val BAK = "userdata.json.bak"
    private val magic = byteArrayOf('F'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    /** The service side: both files, each null when it did not exist. */
    fun encode(json: ByteArray?, bak: ByteArray?): ByteArray {
        val bos = java.io.ByteArrayOutputStream((json?.size ?: 0) + (bak?.size ?: 0) + 32)
        val out = DataOutputStream(bos)
        out.write(magic)
        for (part in listOf(json, bak)) {
            if (part == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeLong(part.size.toLong())
                out.write(part)
            }
        }
        out.flush()
        return bos.toByteArray()
    }

    /** The service side. Null when [bytes] is not a bundle. */
    fun decode(bytes: ByteArray): Pair<ByteArray?, ByteArray?>? = try {
        val input = DataInputStream(bytes.inputStream())
        val head = ByteArray(4)
        input.readFully(head)
        if (!head.contentEquals(magic)) null else {
            fun part(): ByteArray? = when (input.readByte().toInt()) {
                0 -> null
                1 -> {
                    val n = input.readLong()
                    if (n < 0 || n > input.available()) throw java.io.IOException("length")
                    ByteArray(n.toInt()).also { input.readFully(it) }
                }
                else -> throw java.io.IOException("flag")
            }
            val json = part()
            val bak = part()
            if (input.available() != 0) null else json to bak
        }
    } catch (e: java.io.IOException) {
        null
    }

    /** The FGA side: splits a received bundle into [dir]/userdata.json and userdata.json.bak, streaming. */
    fun split(input: InputStream, dir: File): Boolean {
        val data = DataInputStream(input)
        val head = ByteArray(4)
        return try {
            data.readFully(head)
            if (!head.contentEquals(magic)) return false
            dir.mkdirs()
            for (name in listOf(JSON, BAK)) {
                when (data.readByte().toInt()) {
                    0 -> {}
                    1 -> {
                        var left = data.readLong()
                        if (left < 0) return false
                        File(dir, name).outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (left > 0) {
                                val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) return false
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                    else -> return false
                }
            }
            data.read() < 0
        } catch (e: java.io.IOException) {
            false
        }
    }

    /** The FGA side: writes the files found in [dir] as a bundle, streaming. */
    fun join(dir: File, out: OutputStream) {
        val data = DataOutputStream(out)
        data.write(magic)
        for (name in listOf(JSON, BAK)) {
            val f = File(dir, name)
            if (!f.isFile) {
                data.writeByte(0)
            } else {
                data.writeByte(1)
                data.writeLong(f.length())
                f.inputStream().use { it.copyTo(data, 64 * 1024) }
            }
        }
        data.flush()
    }
}
