package io.github.fate_grand_automata.sensing

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
 * final name. A later run reuses a file of that name and the pinned size, so a truncated copy is
 * replaced; any other file in [dir] (another model's copy, a part file) is deleted first, so a
 * stale copy never stays behind.
 */
object OcrModelInstaller {
    const val PART_SUFFIX = ".part"

    fun fileName(pin: OcrModelPin) = "${pin.sha256}-${pin.file}"

    /** The checked copy of the model; throws [IOException] when the asset does not match [pin]. */
    fun install(pin: OcrModelPin, dir: File, openAsset: () -> InputStream): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        val target = File(dir, fileName(pin))
        dir.listFiles()?.forEach { if (it != target) it.deleteRecursively() }
        if (target.isFile && target.length() == pin.size) return target
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
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != pin.sha256) throw IOException("model asset has SHA-256 $actual, the pin says ${pin.sha256}")
            if (!part.renameTo(target)) throw IOException("cannot rename ${part.name} to ${target.name}")
            return target
        } finally {
            part.delete()
        }
    }
}
