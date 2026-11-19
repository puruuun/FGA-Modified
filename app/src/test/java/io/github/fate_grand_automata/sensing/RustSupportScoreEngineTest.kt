package io.github.fate_grand_automata.sensing

import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.lib_automata.Match
import io.github.lib_automata.MeanColor
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RustSupportScoreEngineTest {
    /** Answers only what fga-ffi can answer in each situation. */
    private class FakeNative(
        val libraryError: String? = null,
        val loadAnswer: String = "OK: 12",
        val reply: IntArray? = ScoreWireTest.GOLDEN_COMPOUND,
    ) : ScoreNative {
        val calls = mutableListOf<String>()
        var loaded = false
        override fun loadLibrary(): String? = libraryError.also { calls += "library" }
        override fun load(modelPath: String): String {
            calls += "load $modelPath"
            if (loaded) return "ERR: the support-score engine is already loaded; unload it before loading again"
            if (loadAnswer.startsWith("OK: ")) loaded = true
            return loadAnswer
        }
        override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? {
            calls += "read ${pixels.size} ${width}x$height"
            return reply
        }
        override fun unload(): Int {
            calls += "unload"
            return (if (loaded) ScoreWire.CODE_YES else ScoreWire.CODE_NO).also { loaded = false }
        }
        override fun isLoaded() = if (loaded) 1 else 0
    }

    private class FakePattern(private val gray: ByteArray?, override val width: Int = 60, override val height: Int = 30) : Pattern {
        override var tag = ""
        override fun grayPixels() = gray?.copyOf()
        override fun resize(size: Size): Pattern = error("unused")
        override fun resize(target: Pattern, size: Size) = error("unused")
        override fun findMatches(template: Pattern, similarity: Double): Sequence<Match> = error("unused")
        override fun crop(region: Region): Pattern = error("unused")
        override fun save(stream: OutputStream) = error("unused")
        override fun copy(): Pattern = error("unused")
        override fun threshold(value: Double): Pattern = error("unused")
        override fun isWhite() = error("unused")
        override fun isBlack() = error("unused")
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = error("unused")
        override fun fillText(): Pattern = error("unused")
        override fun meanColor(): MeanColor? = null
        override fun close() = Unit
    }

    private val field = FakePattern(ByteArray(1800))
    private val model = File("model.onnx")

    private fun engine(native: FakeNative, mainThread: Boolean = false, prepare: () -> File = { model }) =
        RustSupportScoreEngine(native, prepare, isMainThread = { mainThread })

    @Test fun `nothing loads until the first use, and only once`() {
        val native = FakeNative()
        val engine = engine(native)
        assertEquals(emptyList(), native.calls)
        val score = engine.read(field)
        assertEquals(EngineField.Value(62), score.classScore)
        engine.read(field)
        assertTrue(engine.available)
        assertEquals(listOf("library", "load ${model.path}", "read 1800 60x30", "read 1800 60x30"), native.calls)
    }

    @Test fun `a missing library makes the engine unavailable without a load`() {
        val native = FakeNative(libraryError = "dlopen failed: library \"libfga_ffi.so\" not found")
        val engine = engine(native)
        assertFalse(engine.available)
        val score = engine.read(field)
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), score.classScore)
        assertTrue(score.detail!!.contains("libfga_ffi.so did not load"))
        assertEquals(listOf("library"), native.calls)
    }

    @Test fun `a missing model asset makes the engine unavailable`() {
        val native = FakeNative()
        val engine = engine(native) { throw FileNotFoundException("ocr/ocr-model.properties") }
        val score = engine.read(field)
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), score.grandScore)
        assertTrue(score.detail!!.startsWith("the OCR model asset is missing"))
        assertEquals(listOf("library"), native.calls)
    }

    @Test fun `a model that does not match its pin makes the engine unavailable`() {
        val engine = engine(FakeNative()) { throw java.io.IOException("model asset has 5 bytes, the pin says 21234383") }
        assertFalse(engine.available)
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), engine.read(field).classScore)
    }

    @Test fun `a failed load makes the engine unavailable for the run`() {
        val native = FakeNative(loadAnswer = "ERR: inference backend: no such file")
        val engine = engine(native)
        assertFalse(engine.available)
        assertFalse(engine.available)
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), engine.read(field).classScore)
        assertEquals(listOf("library", "load ${model.path}"), native.calls)
    }

    @Test fun `the main thread never loads`() {
        val native = FakeNative()
        val engine = engine(native, mainThread = true)
        assertFalse(engine.available)
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), engine.read(field).classScore)
        assertEquals(emptyList(), native.calls)
    }

    @Test fun `a colour field is an engine error`() {
        val engine = engine(FakeNative())
        val score = engine.read(FakePattern(null))
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), score.classScore)
    }

    @Test fun `a JNI failure is an engine error`() {
        val engine = engine(FakeNative(reply = null))
        assertEquals(EngineField.Unknown(EngineUnknownReason.EngineError), engine.read(field).classScore)
    }

    @Test fun `close unloads once and later reads are unavailable`() {
        val native = FakeNative()
        val engine = engine(native)
        engine.read(field)
        engine.close()
        engine.close()
        assertEquals(EngineField.Unknown(EngineUnknownReason.Unavailable), engine.read(field).classScore)
        assertFalse(engine.available)
        assertEquals(listOf("library", "load ${model.path}", "read 1800 60x30", "unload"), native.calls)
    }

    @Test fun `close before any use calls nothing`() {
        val native = FakeNative()
        engine(native).close()
        assertEquals(emptyList(), native.calls)
    }
}
