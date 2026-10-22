package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreBlocks
import kotlinx.serialization.json.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SupportScoreBlocksTest {
    @Test
    fun `native training rows reject near misses and count one digit blocks`() {
        val root = File("../docs/reference-images/support-score-d2")
        val labels = Json.parseToJsonElement(File(root, "labels.json").readText()).jsonObject
        for (frame in labels.getValue("frames").jsonArray) {
            val f = frame.jsonObject
            val path = File(f.getValue("file").jsonPrimitive.content)
            for ((index, row) in f.getValue("rows").jsonArray.withIndex()) {
                val name = "d2-${path.parent}-${path.nameWithoutExtension}-row-${index + 1}"
                val image = ImageIO.read(File(root, "scores/$name.png"))
                val pixels = ByteArray(image.width * image.height) { p ->
                    val rgb = image.getRGB(p % image.width, p / image.width)
                    (((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114 + 500) / 1000).toByte()
                }
                val result = SupportScoreBlocks.read(pixels, image.width, image.height)
                val expected = row.jsonObject
                for ((key, decision, maximum) in listOf(Triple("class", result.class62, 62), Triple("grand", result.grand16, 16))) {
                    if (expected.getValue(key).jsonPrimitive.intOrNull == maximum)
                        assertEquals(SupportScoreBlocks.Decision.Positive, decision, "$name $key")
                    else assertNotEquals(SupportScoreBlocks.Decision.Positive, decision, "$name $key")
                }
            }
        }
    }

    @Test
    fun `raw strokes of a discarded second digit make a single glyph count unknown`() {
        val image = ImageIO.read(File("../docs/reference-images/Jp/support-scores/032.png"))
        val pixels = ByteArray(image.width * image.height) { p ->
            val rgb = image.getRGB(p % image.width, p / image.width)
            (((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114 + 500) / 1000).toByte()
        }
        assertEquals(SupportScoreBlocks.Decision.Unknown, SupportScoreBlocks.read(pixels, image.width, image.height).class62)
    }

    @Test
    fun `empty or uncountable ink remains unknown`() {
        val result = SupportScoreBlocks.read(ByteArray(1800), 60, 30)
        assertEquals(SupportScoreBlocks.Decision.Unknown, result.class62)
        assertEquals(SupportScoreBlocks.Decision.Unknown, result.grand16)
    }
}
