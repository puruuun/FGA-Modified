package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Backups of the Chaldea file under `<root>/<package>/` (amendment B): `first-before-fga.json` is the first original
 * ever taken and is never rotated or deleted; the newest [keep] rotating copies `backup-<time>.json` are kept; the
 * base of the write in progress sits in `inflight-base.json` until the write is verified.
 */
class ChaldeaBackupStore(private val root: File, private val keep: Int = 3) {
    /** Where the service's stream is written before the package is known. */
    fun incoming(): File = File(root, "incoming.tmp").also { root.mkdirs() }

    fun packageDir(pkg: String) = File(root, pkg)
    fun firstOriginal(pkg: String) = File(packageDir(pkg), FIRST)
    fun rotating(pkg: String): List<File> =
        packageDir(pkg).listFiles { f -> f.name.startsWith("backup-") && f.name.endsWith(".json") }
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
     * Moves the verified [incoming] file into the package folder as `inflight-base.json` and, unless [skipRotating],
     * adds a rotating copy and prunes to the newest [keep]. Creates `first-before-fga.json` when absent.
     * @return the file the in-flight record points at
     * @throws IOException when a file cannot be stored
     */
    fun adopt(pkg: String, incoming: File, skipRotating: Boolean, timeMs: Long): File {
        val dir = packageDir(pkg)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
        val inflight = File(dir, INFLIGHT)
        inflight.delete()
        if (!incoming.renameTo(inflight)) {
            incoming.copyTo(inflight, overwrite = true)
            incoming.delete()
        }
        if (!firstOriginal(pkg).exists()) inflight.copyTo(firstOriginal(pkg), overwrite = false)
        if (!skipRotating) {
            inflight.copyTo(File(dir, "backup-%016d.json".format(timeMs)), overwrite = true)
            rotating(pkg).drop(keep).forEach { it.delete() }
        }
        return inflight
    }

    fun release(pkg: String) {
        File(packageDir(pkg), INFLIGHT).delete()
    }

    companion object {
        const val FIRST = "first-before-fga.json"
        const val INFLIGHT = "inflight-base.json"
    }
}
