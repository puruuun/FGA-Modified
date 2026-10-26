package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.PanelOutlineMatcher
import io.github.fate_grand_automata.scripts.modules.PanelAppendMatcher
import io.github.fate_grand_automata.scripts.modules.PanelLevelEvidence
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

    @Test fun `white stays bright and yellow darkens in blue with unsigned RGB pixels`() {
        val rgb = byteArrayOf(-1, -1, -1, -1, -1, 0, 0, 0, -1)
        val channels = checkNotNull(PanelAppendMatcher.channels(rgb, 3, 1))
        assertEquals(listOf(255, 0, 255), channels.first.map { it.toInt() and 255 })
        assertEquals(listOf(255, 226, 29), channels.second.map { it.toInt() and 255 })
        assertNull(PanelAppendMatcher.channels(ByteArray(8), 3, 1))
        assertNull(PanelAppendMatcher.channels(ByteArray(0), 0, 1))
    }

    private fun evidence(value: Int, score: Double, margin: Double) = PanelLevelEvidence(
        value.takeIf { score >= PanelOutlineMatcher.MIN_SCORE && margin >= PanelOutlineMatcher.MIN_MARGIN },
        false, value, score, margin, "test")

    @Test fun `agreeing channels still require one channel to clear both gates`() {
        assertNull(PanelAppendMatcher.combine(evidence(10, 0.69, 0.4), evidence(10, 0.9, 0.09)).level)
        val accepted = PanelAppendMatcher.combine(evidence(10, 0.9, 0.09), evidence(10, 0.75, 0.2))
        assertEquals(10, accepted.level)
        assertEquals(0.75, accepted.score)
        assertEquals("luminance-outline-ncc", accepted.reason)
        assertEquals(10, PanelAppendMatcher.combine(evidence(10, 0.85, 0.2), evidence(10, 0.6, 0.2)).level)
    }

    @Test fun `even an abstaining contradictory channel vetoes a passing number`() {
        for (other in listOf(evidence(9, 0.9, 0.2), evidence(9, 0.6, 0.09),
            PanelLevelEvidence(null, false, null, 0.0, 0.0, "no-outline"))) {
            assertNull(PanelAppendMatcher.combine(evidence(10, 0.95, 0.4), other).level)
            assertNull(PanelAppendMatcher.combine(other, evidence(10, 0.95, 0.4)).level)
        }
    }

    @Test fun `own icon crop cannot validate itself`() {
        val matcher = PanelOutlineMatcher(sheet { level, col -> patch(level * 10 + col) }, w * 4, h * 10)
        val own = patch(10)
        assertNull(matcher.read(own, w, h, 0).level)
        assertEquals(1, matcher.read(own, w, h, 1).level)
    }

    @Test fun `yellow prefix changes cannot alter the digit decision`() {
        val matcher = PanelOutlineMatcher(sheet { level, _ -> patch(level) }, w * 4, h * 10)
        val observed = patch(6)
        for (y in 0 until h) for (x in 0 until 14) observed[y * w + x] = 255.toByte()
        assertEquals(6, matcher.read(observed, w, h, 1).level)
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
