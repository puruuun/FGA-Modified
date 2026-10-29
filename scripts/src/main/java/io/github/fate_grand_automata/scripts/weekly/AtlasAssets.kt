package io.github.fate_grand_automata.scripts.weekly

import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Reads and writes image files as [Pattern]s; implemented on the Android side with OpenCV. */
interface IPatternFileLoader {
    fun load(file: File, color: Boolean, tag: String = file.name): Pattern
    fun save(pattern: Pattern, file: File)
}

/**
 * The Atlas Academy chapter art the navigation matches against (design 0009 D-3): the list banner of a chapter, downloaded
 * once, converted to what the matching needs and cached on disk. Issue #111: map images are no longer used.
 *
 * The in-game list button is the Atlas banner drawn at [BANNER_SCALE] times its pixel size at 720p. Only the button
 * body is kept (the header label above it and the transparent margin are cut off), in colour: grayscale correlation cannot
 * tell the red, green and orange chapter buttons apart, colour can.
 */
class AtlasAssets(
    private val cache: File,
    private val loader: IPatternFileLoader,
    private val fetch: (url: String, target: File) -> Unit = ::download,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val BANNER_SCALE = 1.25
        /** Button body inside the 450x125 banner image. */
        val BANNER_BODY = Region(14, 36, 420, 63)
        private const val HOST = "static.atlasacademy.io"
        private const val MAX_BYTES = 24L * 1024 * 1024

        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        private fun download(url: String, target: File) {
            val u = URL(url)
            require(u.protocol == "https" && u.host == HOST) { "unexpected asset host" }
            val connection = u.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FGA-Modified weekly-route/2")
            try {
                check(connection.responseCode == 200) { "Atlas HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_BYTES) { "asset too large" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
    }

    private fun dir() = File(cache, "assets").also { it.mkdirs() }

    private fun download(url: String, name: String): File {
        val target = File(dir(), name)
        if (target.exists() && target.length() > 0) return target
        val part = File(dir(), "$name.part")
        fetch(url, part)
        check(part.renameTo(target)) { "cannot store Atlas image" }
        log("asset downloaded $name bytes=${target.length()}")
        return target
    }

    /** The colour template of a chapter's list button at 720p, or null when it cannot be had. Close the result. */
    fun banner(server: String, war: WarInfo): Pattern? = runCatching {
        require(war.banner.isNotBlank()) { "war ${war.id} has no banner" }
        val file = download(war.banner, "$server-banner-${war.id}-${hash(war.banner)}.png")
        loader.load(file, color = true, tag = "banner-${war.id}").use { full ->
            full.crop(BANNER_BODY).use { body ->
                body.resize(Size((body.width * BANNER_SCALE).toInt(), (body.height * BANNER_SCALE).toInt()))
            }
        }
    }.onFailure { log("banner war=${war.id} failed: ${it.message}") }.getOrNull()
}
