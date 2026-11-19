package io.github.fate_grand_automata.sensing

import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

class ScoreWireTest {
    private val nan = Float.NaN.toRawBits()

    private fun text(s: String) = intArrayOf(s.codePointCount(0, s.length)) + s.codePoints().toArray()

    private fun read(cls: Int, grand: Int, display: Int, classReason: Int, grandReason: Int, s: String = "") =
        intArrayOf(ScoreWire.MAGIC, 0, cls, grand, display, classReason, grandReason, nan, nan, 0) + text(s)

    private fun assertEngineError(reply: IntArray?) {
        val score = ScoreWire.decode(reply, 1.milliseconds)
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), score.classScore, reply?.toList().toString())
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), score.grandScore, reply?.toList().toString())
        assertEquals(EngineDisplay.Unknown, score.display)
    }

    @Test fun `golden compound reply from wire_rs decodes`() {
        val score = ScoreWire.decode(GOLDEN_COMPOUND, 3.milliseconds)
        assertEquals(EngineField.Value(62), score.classScore)
        assertEquals(EngineField.Value(16), score.grandScore)
        assertEquals(EngineDisplay.Compound, score.display)
        assertEquals("+62/+16", score.text)
        assertEquals(0.75f, score.confidence)
        assertEquals(0.5f, score.minCharConfidence)
        assertEquals(3.milliseconds, score.duration)
        assertNull(score.detail)
        assertEquals(1234, ScoreWire.rustMicros(GOLDEN_COMPOUND))
    }

    @Test fun `golden ordinary reply from wire_rs decodes`() {
        val score = ScoreWire.decode(GOLDEN_ORDINARY, 0.milliseconds)
        assertEquals(EngineField.Value(0), score.classScore)
        assertEquals(EngineField.Unknown(EngineUnknownReason.NotShown), score.grandScore)
        assertEquals(EngineDisplay.Ordinary, score.display)
        assertEquals("+0", score.text)
    }

    @Test fun `every reason code maps to its reason and never to a value`() {
        val expected = listOf(
            EngineUnknownReason.Blank, EngineUnknownReason.LowConfidence, EngineUnknownReason.Format,
            EngineUnknownReason.Range, EngineUnknownReason.Clipped, EngineUnknownReason.NotShown,
            EngineUnknownReason.CropSize,
        )
        assertEquals(expected, ScoreWire.REASONS)
        expected.forEachIndexed { i, reason ->
            val score = ScoreWire.decode(read(-1, -1, 2, i + 1, i + 1), 0.milliseconds)
            assertEquals(EngineField.Unknown(reason), score.classScore)
            assertEquals(EngineField.Unknown(reason), score.grandScore)
            assertNull(score.classScore.valueOrNull)
            assertNull(score.detail)
        }
    }

    @Test fun `zero is a value and only with no reason`() {
        val score = ScoreWire.decode(read(0, 0, 1, 0, 0, "+0/+0"), 0.milliseconds)
        assertEquals(EngineField.Value(0), score.classScore)
        assertEquals(EngineField.Value(0), score.grandScore)
    }

    @Test fun `the range ends are values`() {
        val score = ScoreWire.decode(read(62, 16, 1, 0, 0), 0.milliseconds)
        assertEquals(62, score.classScore.valueOrNull)
        assertEquals(16, score.grandScore.valueOrNull)
    }

    @Test fun `an error reply is engine error with its message`() {
        val reply = intArrayOf(ScoreWire.MAGIC, 1, -1, -1, 2, 0, 0, nan, nan, 0) + text("not loaded")
        val score = ScoreWire.decode(reply, 0.milliseconds)
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), score.classScore)
        assertEquals("not loaded", score.detail)
        assertNull(ScoreWire.rustMicros(reply))
    }

    @Test fun `malformed replies are engine errors, never values`() {
        assertEngineError(null)
        assertEngineError(IntArray(0))
        assertEngineError(GOLDEN_COMPOUND.copyOf(10))
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[0] = 0 })
        // Text length that does not match the array.
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[10] = 6 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[10] = -1 })
        assertEngineError(GOLDEN_COMPOUND + 43)
        // Unknown status and display.
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[1] = 2 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[1] = -1 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[4] = 3 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[4] = -1 })
        // A value with a reason, unknown without one, an unknown reason code, values out of range.
        assertEngineError(read(62, 16, 1, 1, 0))
        assertEngineError(read(62, 16, 1, 0, 7))
        assertEngineError(read(-1, 16, 1, 0, 0))
        assertEngineError(read(62, -1, 1, 0, 0))
        assertEngineError(read(-1, -1, 2, 8, 1))
        assertEngineError(read(-1, -1, 2, 1, -3))
        assertEngineError(read(63, 16, 1, 0, 0))
        assertEngineError(read(62, 17, 1, 0, 0))
        assertEngineError(read(-2, -1, 2, 0, 1))
        assertEngineError(read(Int.MIN_VALUE, 16, 1, 0, 0))
        // Text that is not code points.
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[11] = 0xD800 })
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[11] = 0x110000 })
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[11] = -5 })
        // Longer than fga-ffi ever sends.
        assertEngineError(read(62, 16, 1, 0, 0, "9".repeat(ScoreWire.MAX_TEXT + 1)))
    }

    companion object {
        /** wire.rs `golden_compound_reply`. */
        val GOLDEN_COMPOUND = intArrayOf(0x46534331, 0, 62, 16, 1, 0, 0, 0x3F400000, 0x3F000000, 1234, 7, 43, 54, 50, 47, 43, 49, 54)

        /** wire.rs `golden_ordinary_reply`. */
        val GOLDEN_ORDINARY = intArrayOf(0x46534331, 0, 0, -1, 0, 0, 6, 0x3F400000, 0x3F000000, 5, 2, 43, 48)
    }
}
