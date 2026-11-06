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

/** Public assets live in filesDir, independently of account deletion and Coil's evictable caches. */
class IconFileStore(
    private val directory: File,
    private val download: (String) -> ByteArray = ::downloadIcon,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Share a lock for each id, allowing unrelated missing icons to download concurrently.
    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun resolve(id: Long, address: String?): File? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        mutexes.getOrPut(id) { Mutex() }.withLock {
            val file = File(directory, "$id.png")
            if (file.isFile && file.length() > 0) return@withLock file
            if (address == null || !isPublicIcon(address)) return@withLock null
            val attempt = File(directory, "$id.attempt")
            val last = runCatching { attempt.readText().toLong() }.getOrNull()
            if (last != null && now() - last < RETRY_MS) return@withLock null
            runCatching {
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
                file
            }.getOrNull()
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
        check(connection.responseCode == 200)
        connection.inputStream.use { it.readBytes() }
    } finally { connection.disconnect() }
}
