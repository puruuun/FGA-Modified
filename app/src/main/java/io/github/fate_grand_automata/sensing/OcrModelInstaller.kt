package io.github.fate_grand_automata.sensing

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

/** The pin that Gradle checked the packaged model against (native/ocr-model/model.properties). */
data class OcrModelPin(val file: String, val sha256: String, val size: Long) {
    companion object {
        fun parse(input: InputStream): OcrModelPin {
            val properties = Properties().apply { load(input) }
            fun value(key: String) = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("model pin has no $key")
            val file = value("file")
            if (!Regex("""[A-Za-z0-9._-]+\.onnx""").matches(file)) throw IOException("model pin names '$file'")
            val sha256 = value("sha256").lowercase()
            if (!Regex("[0-9a-f]{64}").matches(sha256)) throw IOException("model pin sha256 '$sha256'")
            val size = value("size").toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("model pin size '${value("size")}'")
            return OcrModelPin(file, sha256, size)
        }
    }
}

/**
 * Rust opens the model by path and APK assets are not files, so the packaged model is copied once
 * into [dir] (the app's no-backup folder, so a 21 MB file never goes into a backup) under a name
 * that holds its SHA-256. The copy streams through a 64 KB buffer, so the Java heap never holds the
 * model, and it is checked by size and SHA-256 while it is written; only a checked copy gets the
 * final name. A later run reuses a file of that name only after reading it again and finding the
 * pinned size and SHA-256, so a truncated or otherwise damaged copy is replaced from the asset,
 * once per call; any other file in [dir] (another model's copy, a part file) is deleted first, so a
 * stale copy never stays behind.
 *
 * Why the reused copy is hashed at every engine load (once per run, at the engine's first use):
 * the damage this guards against is a same-size change of the file's bytes (storage corruption),
 * which neither the size nor a marker written after an earlier check (size, modification time)
 * can see, and Rust would load such a file without complaint when only weights changed. The cost
 * is one read of 21 MB: 12 ms warm (30 ms on the first, cold run) on the owner's PC with the JDK's
 * SHA-256 and a 64 KB buffer; the first load of a run, which already hashes the model once while
 * copying it, took 146 ms on the tablet, 388 ms on MuMu and 581 ms on BlueStacks (the ocrProbe
 * device suite's rust-engine case, docs/agents/rust-native.md), so the hash adds a part of that
 * once per run, on the script thread.
 */
object OcrModelInstaller {
    const val PART_SUFFIX = ".part"

    fun fileName(pin: OcrModelPin) = "${pin.sha256}-${pin.file}"

    /** The checked copy of the model; throws [IOException] when the asset does not match [pin]. */
    fun install(pin: OcrModelPin, dir: File, openAsset: () -> InputStream): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        val target = File(dir, fileName(pin))
        dir.listFiles()?.forEach { if (it != target) it.deleteRecursively() }
        if (target.isFile && target.length() == pin.size) {
            if (sha256Of(target) == pin.sha256) return target
            Timber.w("The installed OCR model copy %s does not match its SHA-256; copying it again", target.name)
        }
        target.delete()
        val part = File(dir, target.name + PART_SUFFIX)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            openAsset().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > pin.size) throw IOException("model asset is longer than the pinned ${pin.size} bytes")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total != pin.size) throw IOException("model asset has $total bytes, the pin says ${pin.size}")
            val actual = hex(digest.digest())
            if (actual != pin.sha256) throw IOException("model asset has SHA-256 $actual, the pin says ${pin.sha256}")
            if (!part.renameTo(target)) throw IOException("cannot rename ${part.name} to ${target.name}")
            return target
        } finally {
            part.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
