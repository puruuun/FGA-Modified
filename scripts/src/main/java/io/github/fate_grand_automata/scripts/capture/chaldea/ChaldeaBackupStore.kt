package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Backups of the two Chaldea files under `<root>/<package>/` (amendments B, F): each backup is a folder holding the
 * original `userdata.json` and `userdata.json.bak`. `first-before-fga/` is the first original ever taken and is never
 * rotated or deleted; the newest [keep] rotating copies `backup-<time>/` are kept; the base of the write in progress
 * sits in `inflight-base/` until the write is verified.
 */
class ChaldeaBackupStore(private val root: File, private val keep: Int = 3) {
    /** Where the service's stream is written before the package is known. */
    fun incoming(): File = File(root, "incoming.tmp").also { root.mkdirs() }

    /** Scratch file for the bundle sent back to the service when restoring. */
    fun restoreScratch(): File = File(root, "restore.tmp").also { root.mkdirs() }

    fun packageDir(pkg: String) = File(root, pkg)
    fun firstOriginal(pkg: String) = File(packageDir(pkg), FIRST)
    fun inflightDir(pkg: String) = File(packageDir(pkg), INFLIGHT)
    fun rotating(pkg: String): List<File> =
        packageDir(pkg).listFiles { f -> f.isDirectory && f.name.startsWith("backup-") }
            ?.sortedByDescending { it.name }.orEmpty()

    /** Re-reads [file] from disk and compares size and SHA-256. */
    fun verify(file: File, size: Long, sha256: String): Boolean = try {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        total == size && md.digest().joinToString("") { "%02x".format(it) } == sha256
    } catch (e: IOException) {
        false
    }

    /**
     * Splits the verified [incoming] bundle into `inflight-base/` and, unless [skipRotating], adds a rotating copy and
     * prunes to the newest [keep]. Creates `first-before-fga/` when absent.
     * @return the folder the in-flight record points at
     * @throws IOException when a file cannot be stored
     */
    fun adopt(pkg: String, incoming: File, skipRotating: Boolean, timeMs: Long): File {
        val dir = packageDir(pkg)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
        val inflight = inflightDir(pkg)
        inflight.deleteRecursively()
        val ok = try {
            incoming.inputStream().use { ChaldeaBackupBundle.split(it, inflight) }
        } finally {
            incoming.delete()
        }
        if (!ok) {
            inflight.deleteRecursively()
            throw IOException("bundle")
        }
        if (!firstOriginal(pkg).exists() && !inflight.copyRecursively(firstOriginal(pkg), overwrite = false)) throw IOException("first")
        if (!skipRotating) {
            if (!inflight.copyRecursively(File(dir, "backup-%016d".format(timeMs)), overwrite = true)) throw IOException("rotating")
            rotating(pkg).drop(keep).forEach { it.deleteRecursively() }
        }
        return inflight
    }

    /**
     * Writes the bundle of a backup folder into [target] and returns its size and SHA-256, so it can be sent to the
     * service's restore.
     */
    fun bundleOf(folder: File, target: File): ChaldeaBackupReceiver.Received {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        java.io.FileOutputStream(target).use { file ->
            val counting = object : java.io.OutputStream() {
                override fun write(b: Int) {
                    md.update(b.toByte())
                    total++
                    file.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    md.update(b, off, len)
                    total += len
                    file.write(b, off, len)
                }
            }
            ChaldeaBackupBundle.join(folder, counting)
        }
        return ChaldeaBackupReceiver.Received(total, md.digest().joinToString("") { "%02x".format(it) })
    }

    fun release(pkg: String) {
        inflightDir(pkg).deleteRecursively()
    }

    companion object {
        const val FIRST = "first-before-fga"
        const val INFLIGHT = "inflight-base"
    }
}
