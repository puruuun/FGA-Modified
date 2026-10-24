package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.PanelOutlineMatcher
import io.github.fate_grand_automata.scripts.modules.SkillLevelPanelReader
import io.github.lib_automata.OcrText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillLevelPanelReaderTest {
    private val w = PanelOutlineMatcher.WIDTH
    private val h = PanelOutlineMatcher.HEIGHT
    private fun patch(seed: Int) = Random(seed).nextBytes(w * h)
    private fun sheet(cell: (Int, Int) -> ByteArray): ByteArray = ByteArray(w * 4 * h * 10).also { image ->
        for (level in 1..10) for (col in 0..3) {
            val glyph = cell(level, col)
            for (y in 0 until h) for (x in 0 until w)
                image[((level - 1) * h + y) * w * 4 + col * w + x] = glyph[y * w + x]
        }
    }

    @Test fun `own icon crop cannot validate itself`() {
        val matcher = PanelOutlineMatcher(sheet { level, col -> patch(level * 10 + col) }, w * 4, h * 10)
        val own = patch(10)
        assertNull(matcher.read(own, w, h, 0).level)
        assertEquals(1, matcher.read(own, w, h, 1).level)
    }

    @Test fun `equal plausible numbers abstain even at a perfect score`() {
        val matcher = PanelOutlineMatcher(sheet { level, _ -> patch(if (level == 2) 1 else level) }, w * 4, h * 10)
        val result = matcher.read(patch(1), w, h, 1)
        assertNull(result.level)
        assertTrue(result.score > 0.99)
        assertTrue(result.margin < 1e-6)
    }

    @Test fun `blank and malformed observations do not become a level`() {
        val matcher = PanelOutlineMatcher(sheet { level, _ -> patch(level) }, w * 4, h * 10)
        assertNull(matcher.read(ByteArray(w * h) { 120 }, w, h, 1).level)
        assertNull(matcher.read(ByteArray(3), w, h, 1).level)
        assertNull(matcher.read(ByteArray(20 * h) { 120 }, 20, h, 1).level)
    }

    @Test fun `OCR keeps the denominator and rejects unsupported ranges`() {
        assertEquals(10, SkillLevelPanelReader.parse("Lv. 10/10\n"))
        for (s in listOf("0/10", "11/10", "5/19", "510", "5", "Lv. 5/10 extra"))
            assertNull(SkillLevelPanelReader.parse(s), s)
    }

    @Test fun `zero confidence and conflicting confident readings never authorize a level`() {
        assertNull(SkillLevelPanelReader.decide(List(8) { OcrText("5/10", 0) }).level)
        val votes = List(4) { OcrText("5/10", 96) }
        assertEquals(5, SkillLevelPanelReader.decide(votes).level)
        assertNull(SkillLevelPanelReader.decide(votes + OcrText("9/10", 90)).level)
        assertNull(SkillLevelPanelReader.decide(votes + OcrText("5/19", 90)).level)
    }
}
