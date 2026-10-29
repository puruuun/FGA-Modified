package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.AtlasAssets
import io.github.fate_grand_automata.scripts.weekly.IPatternFileLoader
import io.github.fate_grand_automata.scripts.weekly.WarInfo
import io.github.fate_grand_automata.scripts.weekly.WarMap
import io.github.lib_automata.Pattern
import io.github.lib_automata.Size
import java.io.File
import java.io.OutputStream
import kotlin.test.*
import io.mockk.every
import io.mockk.mockk
import io.github.fate_grand_automata.scripts.weekly.*

/** Design 0009 D-36: a placeholder map is recognised even when an older version cached a map file for it. */
class WeeklyMapCacheTest {
    @Test fun `gimmicks decode from war data and survive the compact model`() {
        val war = AtlasWeekly.wars("""[{"id":306,"maps":[{"id":30601,"mapImage":"base","mapImageW":2048,"mapImageH":1440,
            "mapGimmicks":[{"image":"layer","x":1024,"y":720,"depthOffset":-100,"scale":1000}]}],
            "spots":[{"id":1,"quests":[{"id":1,"name":"quest","type":"free","consumeType":"ap","consume":21,
            "afterClear":"repeatLast","phases":[1]}]}]}]""", 100).wars.getValue(306)
        assertEquals(MapGimmick("layer", 1024.0, 720.0, -100, 1000), war.maps.single().mapGimmicks.single())
        val json = kotlinx.serialization.json.Json
        val saved = json.encodeToString(WarInfo.serializer(), war)
        assertEquals(war, json.decodeFromString(WarInfo.serializer(), saved))
    }

    @Test fun `map preparation composites successful layers in depth order skips failures and caches the result`() {
        val dir = kotlin.io.path.createTempDirectory("map-layers").toFile()
        try {
            val pattern = mockk<Pattern>(relaxed = true)
            every { pattern.width } returns 128; every { pattern.height } returns 128
            every { pattern.resize(any<Size>()) } returns pattern
            every { pattern.grayPixels() } returns ByteArray(128 * 128) { 100 }
            var result: ByteArray? = null
            val loader = object : IPatternFileLoader {
                override fun load(file: File, color: Boolean, tag: String) = pattern
                override fun save(pattern: Pattern, file: File) { file.writeText("prepared") }
                override fun loadOverlay(file: File): MapOverlay {
                    check(file.readText() != "invalid") { "bad PNG" }
                    val color = if (file.readText() == "white") 0xFFFFFFFF.toInt() else 0x80000000.toInt()
                    return MapOverlay(128, 128, IntArray(128 * 128) { color })
                }
                override fun fromGray(pixels: ByteArray, size: Size): Pattern { result = pixels; return pattern }
            }
            val fetched = mutableListOf<String>(); val logs = mutableListOf<String>()
            val assets = AtlasAssets(dir, loader, fetch = { url, target ->
                fetched += url
                check(url != "missing") { "HTTP 404" }
                target.writeText(url)
            }, log = logs::add)
            val layers = listOf(MapGimmick("black", 64.0, 64.0, 20), MapGimmick("missing", 64.0, 64.0, 0),
                MapGimmick("invalid", 64.0, 64.0, 1), MapGimmick("white", 64.0, 64.0, -10))
            val war = WarInfo(306, "Olympus", "", listOf(WarMap(30601, "base", 128, 128, layers)))
            assertNotNull(assets.map("JP", war, 30601))
            assertTrue(assertNotNull(result).all { (it.toInt() and 255) == 127 })
            assertEquals(listOf("base", "white", "missing", "invalid", "black"), fetched)
            assertEquals(2, logs.count { it.startsWith("map gimmick skipped") })
            assertNotNull(assets.map("JP", war, 30601))
            assertEquals(5, fetched.size)
        } finally { dir.deleteRecursively() }
    }

    private class Fake(override val width: Int, override val height: Int, override var tag: String) : Pattern {
        override fun resize(size: Size): Pattern = Fake(size.width, size.height, tag)
        override fun resize(target: Pattern, size: Size) = error("unused")
        override fun findMatches(template: Pattern, similarity: Double) = error("unused")
        override fun crop(region: io.github.lib_automata.Region): Pattern = error("unused")
        override fun save(stream: OutputStream) = error("unused")
        override fun copy(): Pattern = error("unused")
        override fun threshold(value: Double): Pattern = error("unused")
        override fun isWhite() = error("unused")
        override fun isBlack() = error("unused")
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = error("unused")
        override fun fillText(): Pattern = error("unused")
        override fun meanColor() = error("unused")
        override fun grayPixels() = error("unused")
        override fun close() {}
    }

    /** Files hold their size as "WxH" text, like a tiny image format. */
    private val loader = object : IPatternFileLoader {
        override fun load(file: File, color: Boolean, tag: String): Pattern {
            val (w, h) = file.readText().trim().split('x').map { it.toInt() }
            return Fake(w, h, tag)
        }
        override fun save(pattern: Pattern, file: File) { file.writeText("${pattern.width}x${pattern.height}") }
    }

    private fun war(image: String) = WarInfo(303, "Shin", "", listOf(WarMap(30301, image, 2048, 1440)))

    private fun assets(dir: File, textureSize: String, fetched: MutableList<String>) =
        AtlasAssets(dir, loader, fetch = { url, target -> fetched += url; target.writeText(textureSize) })

    @Test fun `a placeholder texture is detected and remembered`() {
        val dir = kotlin.io.path.createTempDirectory("maps").toFile()
        val fetched = mutableListOf<String>()
        val a = assets(dir, "8x8", fetched)
        assertNull(a.map("JP", war("https://static.atlasacademy.io/m.png"), 30301))
        assertTrue(a.isPlaceholder("JP", war("https://static.atlasacademy.io/m.png"), 30301))
        assertNull(a.map("JP", war("https://static.atlasacademy.io/m.png"), 30301))
        assertEquals(1, fetched.size)
    }

    @Test fun `a map cached by an older version is fetched again and a cached placeholder is rejected`() {
        val dir = kotlin.io.path.createTempDirectory("maps").toFile()
        val image = "https://static.atlasacademy.io/m.png"
        val assetDir = File(dir, "assets").apply { mkdirs() }
        // beta.8/9 layout: no version in the name, no record of the texture size, a blurred 8x8 stretched to 2048x1440.
        val stem = "JP-map-30301-${AtlasAssets.hash(image)}"
        File(assetDir, "$stem-2048x1440.gray.png").writeText("2048x1440")
        val fetched = mutableListOf<String>()
        assertNull(assets(dir, "8x8", fetched).map("JP", war(image), 30301))
        assertEquals(1, fetched.size)

        // Current layout with a ready file whose recorded texture is a placeholder (or not recorded) is not trusted.
        val dir2 = kotlin.io.path.createTempDirectory("maps").toFile()
        val assetDir2 = File(dir2, "assets").apply { mkdirs() }
        val stem2 = "JP-map-v${AtlasAssets.MAP_CACHE_FORMAT}-30301-${AtlasAssets.hash(image)}"
        File(assetDir2, "$stem2-2048x1440.gray.png").writeText("2048x1440")
        val fetched2 = mutableListOf<String>()
        assertNull(assets(dir2, "8x8", fetched2).map("JP", war(image), 30301))
        assertEquals(1, fetched2.size)

        // A real map is kept and a second call does not download again.
        val dir3 = kotlin.io.path.createTempDirectory("maps").toFile()
        val fetched3 = mutableListOf<String>()
        val real = assets(dir3, "1024x1024", fetched3)
        assertEquals(2048, assertNotNull(real.map("JP", war(image), 30301)).width)
        assertNotNull(real.map("JP", war(image), 30301))
        assertEquals(1, fetched3.size)
    }
}
