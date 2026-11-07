package io.github.fate_grand_automata.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Result of looking for one icon. [Missing] means the Atlas host has no image (HTTP 404). */
enum class IconOutcome { Ready, Missing, Failed }

/** Thrown by a downloader when the asset host answers 404, so "no image" is told apart from a real failure. */
class IconNotFoundException : Exception()

/** Public assets live in filesDir, independently of account deletion and Coil's evictable caches. */
class IconFileStore(
    private val directory: File,
    private val download: (String) -> ByteArray = ::downloadIcon,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Share a lock for each id, allowing unrelated missing icons to download concurrently.
    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun resolve(id: Long, address: String?): File? = fetch(id, address).first

    suspend fun fetch(id: Long, address: String?): Pair<File?, IconOutcome> = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null to IconOutcome.Failed
        mutexes.getOrPut(id) { Mutex() }.withLock {
            val file = File(directory, "$id.png")
            if (file.isFile && file.length() > 0) return@withLock file to IconOutcome.Ready
            if (address == null || !isPublicIcon(address)) return@withLock null to IconOutcome.Failed
            val missing = File(directory, "$id.missing")
            val missingAt = runCatching { missing.readText().toLong() }.getOrNull()
            if (missingAt != null && now() - missingAt < RETRY_MS) return@withLock null to IconOutcome.Missing
            val attempt = File(directory, "$id.attempt")
            val last = runCatching { attempt.readText().toLong() }.getOrNull()
            if (last != null && now() - last < RETRY_MS) return@withLock null to IconOutcome.Failed
            try {
                check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
                attempt.writeText(now().toString())
                val bytes = download(address)
                check(bytes.isNotEmpty())
                val temp = File(directory, "$id.tmp")
                try {
                    temp.outputStream().use { it.write(bytes); it.fd.sync() }
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { temp.delete() }
                attempt.delete()
                missing.delete()
                file to IconOutcome.Ready
            } catch (_: IconNotFoundException) {
                runCatching { missing.writeText(now().toString()); attempt.delete() }
                null to IconOutcome.Missing
            } catch (_: Exception) {
                null to IconOutcome.Failed
            }
        }
    }

    companion object {
        internal const val RETRY_MS = 60 * 60 * 1000L
        internal fun isPublicIcon(address: String) = runCatching {
            URI(address).let { it.scheme == "https" && it.host == "static.atlasacademy.io" &&
                it.userInfo == null && (it.port == -1 || it.port == 443) }
        }.getOrDefault(false)
    }
}

private fun downloadIcon(address: String): ByteArray {
    check(IconFileStore.isPublicIcon(address))
    val connection = URL(address).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        val code = connection.responseCode
        if (code == 404) throw IconNotFoundException()
        check(code == 200)
        connection.inputStream.use { it.readBytes() }
    } finally { connection.disconnect() }
}
