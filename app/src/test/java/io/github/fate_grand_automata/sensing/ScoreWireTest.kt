package io.github.fate_grand_automata.sensing

import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class ScoreWireTest {
    private val nan = Float.NaN.toRawBits()

    private fun text(s: String) = intArrayOf(s.codePointCount(0, s.length)) + s.codePoints().toArray()

    private fun read(cls: Int, grand: Int, display: Int, classReason: Int, grandReason: Int, s: String = "") =
        intArrayOf(ScoreWire.MAGIC, 0, cls, grand, display, classReason, grandReason, nan, nan, 0, 0) + text(s)

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
        assertEquals(1234.microseconds, score.engineTime)
        assertEquals(false, score.signCorrected)
    }

    @Test fun `golden sign corrected reply from wire_rs decodes`() {
        val score = ScoreWire.decode(GOLDEN_SIGN_CORRECTED, 0.milliseconds)
        assertEquals(EngineField.Value(62), score.classScore)
        assertEquals(EngineField.Value(16), score.grandScore)
        assertEquals(EngineDisplay.Compound, score.display)
        assertEquals("+62/-16", score.text)
        assertEquals(true, score.signCorrected)
        assertEquals(9.microseconds, score.engineTime)
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
        val reply = intArrayOf(ScoreWire.MAGIC, 1, -1, -1, 2, 0, 0, nan, nan, 0, 0) + text("not loaded")
        val score = ScoreWire.decode(reply, 0.milliseconds)
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), score.classScore)
        assertEquals("not loaded", score.detail)
        assertNull(score.engineTime)
    }

    /** native/fga-ffi/src/score_engine/wire-codes.txt, which wire.rs's test reads too. */
    @Test fun `codes match the table shared with wire_rs`() {
        val table = File(System.getProperty("fga.scoreWireCodes")!!).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().split(' ', limit = 3) }
        fun camel(name: String) = name.split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
        val reasons = table.filter { it[0] == "reason" }
        assertEquals(ScoreWire.REASONS.size, reasons.size)
        // Every reason except the two the binding adds comes from the reader, so it needs a code.
        val readerReasons = EngineUnknownReason.entries.toSet() - setOf(EngineUnknownReason.EngineError, EngineUnknownReason.Unavailable)
        assertEquals(readerReasons, ScoreWire.REASONS.toSet())
        for ((_, name, code) in reasons) {
            assertEquals(EngineUnknownReason.valueOf(camel(name)), ScoreWire.REASONS[code.toInt() - 1], name)
            val score = ScoreWire.decode(read(-1, -1, 2, code.toInt(), code.toInt()), 0.milliseconds)
            assertEquals(EngineField.Unknown(EngineUnknownReason.valueOf(camel(name))), score.classScore, name)
        }
        val displays = table.filter { it[0] == "display" }
        assertEquals(EngineDisplay.entries.size, displays.size)
        for ((_, name, code) in displays) {
            val value = if (name == "unknown") -1 else 1
            val reason = if (name == "unknown") 1 else 0
            val score = ScoreWire.decode(read(value, value, code.toInt(), reason, reason), 0.milliseconds)
            assertEquals(EngineDisplay.valueOf(camel(name)), score.display, name)
        }
        val answers = table.filter { it[0] == "answer" }.associate { it[1] to it[2].toInt() }
        assertEquals(
            mapOf(
                "no" to ScoreWire.CODE_NO,
                "yes" to ScoreWire.CODE_YES,
                "busy" to ScoreWire.CODE_BUSY,
                "error" to ScoreWire.CODE_ERROR,
                "pending_exception" to ScoreWire.CODE_PENDING_EXCEPTION,
            ),
            answers,
        )
        val texts = table.filter { it[0] == "text" }.associate { it[1] to it[2] }
        assertEquals(mapOf("already_loaded" to ScoreWire.ALREADY_LOADED), texts)
        fun number(text: String) = if (text.startsWith("0x")) text.removePrefix("0x").toLong(16).toInt() else text.toInt()
        val layout = table.filter { it[0] == "layout" }.associate { it[1] to number(it[2]) }
        assertEquals(
            mapOf("magic" to ScoreWire.MAGIC, "header_len" to ScoreWire.HEADER_LEN, "flags_index" to ScoreWire.FLAGS_INDEX),
            layout,
        )
        val flags = table.filter { it[0] == "flag" }.associate { it[1] to number(it[2]) }
        assertEquals(mapOf("sign_corrected" to ScoreWire.FLAG_SIGN_CORRECTED), flags)
        assertEquals(ScoreWire.MAGIC, GOLDEN_SIGN_CORRECTED[0])
        assertEquals(ScoreWire.FLAG_SIGN_CORRECTED, GOLDEN_SIGN_CORRECTED[ScoreWire.FLAGS_INDEX])
    }

    @Test fun `infinite confidences count as absent`() {
        val reply = GOLDEN_COMPOUND.copyOf().also {
            it[7] = Float.POSITIVE_INFINITY.toRawBits()
            it[8] = Float.NEGATIVE_INFINITY.toRawBits()
        }
        val score = ScoreWire.decode(reply, 0.milliseconds)
        assertEquals(EngineField.Value(62), score.classScore)
        assertNull(score.confidence)
        assertNull(score.minCharConfidence)
    }

    @Test fun `malformed replies are engine errors, never values`() {
        assertEngineError(null)
        assertEngineError(IntArray(0))
        assertEngineError(GOLDEN_COMPOUND.copyOf(11))
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[0] = 0 })
        // The layout before the flags (FSC1, 11 ints of header).
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[0] = 0x46534331 })
        assertEngineError(intArrayOf(0x46534331, 0, 62, 16, 1, 0, 0, 0x3F400000, 0x3F000000, 1234, 7, 43, 54, 50, 47, 43, 49, 54))
        // Text length that does not match the array.
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[11] = 6 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[11] = -1 })
        // A flag bit that is not defined.
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[10] = 2 })
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[10] = -1 })
        assertEngineError(GOLDEN_COMPOUND + 43)
        // One element shorter than the declared text.
        assertEngineError(GOLDEN_COMPOUND.copyOf(GOLDEN_COMPOUND.size - 1))
        // A negative engine time.
        assertEngineError(GOLDEN_COMPOUND.copyOf().also { it[9] = -1 })
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
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[12] = 0xD800 })
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[12] = 0x110000 })
        assertEngineError(read(62, 16, 1, 0, 0, "+").also { it[12] = -5 })
        // Longer than fga-ffi ever sends.
        assertEngineError(read(62, 16, 1, 0, 0, "9".repeat(ScoreWire.MAX_TEXT + 1)))
    }

    companion object {
        /** wire.rs `golden_compound_reply`. */
        val GOLDEN_COMPOUND = intArrayOf(0x46534332, 0, 62, 16, 1, 0, 0, 0x3F400000, 0x3F000000, 1234, 0, 7, 43, 54, 50, 47, 43, 49, 54)

        /** wire.rs `golden_ordinary_reply`. */
        val GOLDEN_ORDINARY = intArrayOf(0x46534332, 0, 0, -1, 0, 0, 6, 0x3F400000, 0x3F000000, 5, 0, 2, 43, 48)

        /** wire.rs `golden_sign_corrected_reply`. */
        val GOLDEN_SIGN_CORRECTED = intArrayOf(0x46534332, 0, 62, 16, 1, 0, 0, 0x3F400000, 0x3F000000, 9, 1, 7, 43, 54, 50, 47, 45, 49, 54)
    }
}
