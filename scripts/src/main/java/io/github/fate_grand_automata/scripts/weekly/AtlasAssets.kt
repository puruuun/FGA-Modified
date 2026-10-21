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
 * The Atlas Academy chapter art the navigation matches against (design 0009 D-3, D-10): the list banner of a chapter and
 * its map images. Both are downloaded once, converted to what the matching needs and cached on disk.
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
        /** D-36: bumped so maps cached by older versions (beta.8 kept a blurred 8x8 placeholder as the map) are fetched once more. */
        const val MAP_CACHE_FORMAT = 2
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

    /**
     * The grayscale map of [mapId] at its display size (one pixel per map pixel). The Atlas PNG is a larger power-of-two
     * texture stretched to the display size, so it is resized here once and the result is cached. Close the result.
     */
    fun map(server: String, war: WarInfo, mapId: Int): Pattern? = runCatching {
        val map = war.maps.firstOrNull { it.id == mapId } ?: error("war ${war.id} has no map $mapId")
        val stem = "$server-map-v$MAP_CACHE_FORMAT-${map.id}-${hash(map.image)}"
        val placeholderMark = File(dir(), "$stem.placeholder")
        val textureRecord = File(dir(), "$stem.texture")
        if (placeholderMark.exists()) return@runCatching null
        val ready = File(dir(), "$stem-${map.width}x${map.height}.gray.png")
        if (ready.exists()) {
            // D-36: a cached map is only trusted when the size of the original texture was recorded and is a real map.
            val recorded = textureRecord.takeIf { it.exists() }?.readText()?.trim()?.split('x')?.mapNotNull { it.toIntOrNull() }
            if (recorded == null || recorded.size != 2 || PlaceholderMap.isPlaceholder(recorded[0], recorded[1])) {
                log("map cache rejected war=${war.id} map=${map.id} texture=${recorded?.joinToString("x")}")
                ready.delete(); textureRecord.delete()
            }
        }
        if (!ready.exists()) {
            val raw = download(map.image, "$server-mapraw-${map.id}-${hash(map.image)}.png")
            val placeholder = loader.load(raw, color = false, tag = "map-${map.id}").use { texture ->
                textureRecord.writeText("${texture.width}x${texture.height}")
                if (PlaceholderMap.isPlaceholder(texture.width, texture.height)) true
                else { texture.resize(Size(map.width, map.height)).use { loader.save(it, ready) }; false }
            }
            raw.delete()
            if (placeholder) {
                // D-31: the Atlas texture is a placeholder; remember that so the chapter is not downloaded again.
                placeholderMark.writeText("placeholder")
                log("map placeholder war=${war.id} map=${map.id}")
                return@runCatching null
            }
            log("map ready ${ready.name}")
        }
        loader.load(ready, color = false, tag = "map-${map.id}")
    }.onFailure { log("map war=${war.id} map=$mapId failed: ${it.message}") }.getOrNull()

    /** D-31: true once [map] found that the Atlas map image of [mapId] is only a placeholder texture. */
    fun isPlaceholder(server: String, war: WarInfo, mapId: Int): Boolean {
        val map = war.maps.firstOrNull { it.id == mapId } ?: return false
        return File(dir(), "$server-map-v$MAP_CACHE_FORMAT-${map.id}-${hash(map.image)}.placeholder").exists()
    }
}
