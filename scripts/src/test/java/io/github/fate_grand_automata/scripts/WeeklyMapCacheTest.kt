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

/** Design 0009 D-36: a placeholder map is recognised even when an older version cached a map file for it. */
class WeeklyMapCacheTest {
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
