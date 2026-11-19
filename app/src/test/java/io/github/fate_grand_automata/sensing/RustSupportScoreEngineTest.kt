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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds

class RustSupportScoreEngineTest {
    /**
     * Answers what fga-ffi answers: a read when a model is loaded, the not-loaded error reply
     * otherwise, a refused second load, and 1 or 0 from unload. [failOn] makes one call throw
     * what a library that does not match RustSensing throws.
     */
    private class FakeNative(
        val libraryError: String? = null,
        val loadAnswer: String = "OK: 12",
        val failOn: String? = null,
        val loadGate: CountDownLatch? = null,
        val readGate: CountDownLatch? = null,
    ) : ScoreNative {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)
        @Volatile var loaded = false

        private fun call(name: String) {
            calls += name
            if (failOn == name) throw UnsatisfiedLinkError("No implementation found for $name")
        }

        override fun loadLibrary(): String? = libraryError.also { call("library") }

        override fun load(modelPath: String): String {
            call("load")
            loadGate?.let { entered.countDown(); it.await(10, TimeUnit.SECONDS) }
            if (loaded) return "ERR: the support-score engine is already loaded; unload it before loading again"
            if (loadAnswer.startsWith("OK: ")) loaded = true
            return loadAnswer
        }

        override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? {
            call("read")
            readGate?.let { entered.countDown(); it.await(10, TimeUnit.SECONDS) }
            return if (loaded) ScoreWireTest.GOLDEN_COMPOUND else NOT_LOADED_REPLY
        }

        override fun unload(): Int {
            call("unload")
            return (if (loaded) ScoreWire.CODE_YES else ScoreWire.CODE_NO).also { loaded = false }
        }

        override fun isLoaded() = if (loaded) 1 else 0
    }

    private class FakePattern(private val gray: () -> ByteArray?) : Pattern {
        override val width = 60
        override val height = 30
        override var tag = ""
        override fun grayPixels() = gray()
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

    private val field = FakePattern { ByteArray(1800) }
    private val model = File("model.onnx")

    private fun engine(native: FakeNative, mainThread: Boolean = false, prepare: () -> File = { model }) =
        RustSupportScoreEngine(native, prepare, isMainThread = { mainThread })

    private val unavailable = EngineField.Unknown(EngineUnknownReason.Unavailable)
    private val engineError = EngineField.Unknown(EngineUnknownReason.EngineError)

    @Test fun `nothing loads until the first use, and only once`() {
        val native = FakeNative()
        val engine = engine(native)
        assertEquals(emptyList(), native.calls)
        assertNull(engine.loadTime)
        val score = engine.read(field)
        assertEquals(EngineField.Value(62), score.classScore)
        assertEquals(1234.microseconds, score.engineTime)
        engine.read(field)
        assertTrue(engine.available)
        assertNull(engine.unavailableReason)
        assertNotNull(engine.loadTime)
        assertEquals(listOf("library", "load", "read", "read"), native.calls)
    }

    @Test fun `a missing library makes the engine unavailable without a load`() {
        val native = FakeNative(libraryError = "dlopen failed: library \"libfga_ffi.so\" not found")
        val engine = engine(native)
        assertFalse(engine.available)
        assertTrue(engine.unavailableReason!!.contains("libfga_ffi.so did not load"))
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(listOf("library"), native.calls)
    }

    @Test fun `a missing model asset makes the engine unavailable`() {
        val native = FakeNative()
        val engine = engine(native) { throw FileNotFoundException("ocr/ocr-model.properties") }
        val score = engine.read(field)
        assertEquals(unavailable, score.grandScore)
        assertTrue(score.detail!!.startsWith("the OCR model asset is missing"))
        assertEquals(listOf("library"), native.calls)
    }

    @Test fun `a model that does not match its pin makes the engine unavailable`() {
        val engine = engine(FakeNative()) { throw java.io.IOException("model asset has 5 bytes, the pin says 21234383") }
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
    }

    @Test fun `a failed load makes the engine unavailable for the run`() {
        val native = FakeNative(loadAnswer = "ERR: inference backend: no such file")
        val engine = engine(native)
        assertFalse(engine.available)
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(listOf("library", "load"), native.calls)
    }

    @Test fun `the main thread never loads`() {
        val native = FakeNative()
        val engine = engine(native, mainThread = true)
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(emptyList(), native.calls)
    }

    @Test fun `a library that does not match the class makes nothing throw`() {
        for (call in listOf("library", "load")) {
            val engine = engine(FakeNative(failOn = call))
            assertFalse(engine.available, call)
            assertTrue(engine.unavailableReason!!.startsWith("UnsatisfiedLinkError"), call)
            assertEquals(unavailable, engine.read(field).classScore, call)
            engine.close()
        }
        val readFails = engine(FakeNative(failOn = "read"))
        val score = readFails.read(field)
        assertEquals(engineError, score.classScore)
        assertTrue(score.detail!!.startsWith("UnsatisfiedLinkError"))
        val unloadFails = FakeNative(failOn = "unload")
        engine(unloadFails).apply { read(field); close() }
        assertEquals(listOf("library", "load", "read", "unload"), unloadFails.calls)
    }

    @Test fun `a close after a failing unload still ends closed`() {
        val native = FakeNative(failOn = "unload")
        val engine = engine(native)
        engine.read(field)
        engine.close()
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(RustSupportScoreEngine.CLOSED, engine.unavailableReason)
        assertEquals(listOf("library", "load", "read", "unload"), native.calls)
    }

    @Test fun `a field whose pixels cannot be read is an engine error`() {
        val engine = engine(FakeNative())
        assertEquals(engineError, engine.read(FakePattern { null }).classScore)
        val score = engine.read(FakePattern { throw IllegalStateException("released Mat") })
        assertEquals(engineError, score.classScore)
        assertTrue(score.detail!!.contains("released Mat"))
    }

    @Test fun `errors that leave the VM unusable propagate`() {
        val engine = engine(FakeNative())
        assertFailsWith<OutOfMemoryError> { engine.read(FakePattern { throw OutOfMemoryError("test") }) }
    }

    @Test fun `a JNI failure is an engine error`() {
        val engine = RustSupportScoreEngine(object : ScoreNative by FakeNative() {
            override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? = null
        }, { model }, isMainThread = { false })
        assertEquals(engineError, engine.read(field).classScore)
    }

    @Test fun `close unloads once and later reads are unavailable`() {
        val native = FakeNative()
        val engine = engine(native)
        engine.read(field)
        engine.close()
        engine.close()
        assertEquals(unavailable, engine.read(field).classScore)
        assertFalse(engine.available)
        assertEquals(listOf("library", "load", "read", "unload"), native.calls)
    }

    @Test fun `close before any use calls nothing and nothing loads afterwards`() {
        val native = FakeNative()
        val engine = engine(native)
        engine.close()
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(emptyList(), native.calls)
    }

    @Test fun `close does not wait for a load, and the engine ends unloaded`() {
        val gate = CountDownLatch(1)
        val native = FakeNative(loadGate = gate)
        val engine = engine(native)
        val worker = thread { engine.read(field) }
        assertTrue(native.entered.await(10, TimeUnit.SECONDS))
        val closer = thread { engine.close() }
        closer.join(5_000)
        assertFalse(closer.isAlive, "close waited for the load")
        assertEquals(unavailable, engine.read(field).classScore)
        gate.countDown()
        worker.join(10_000)
        assertFalse(native.loaded)
        assertEquals(listOf("library", "load", "unload"), native.calls)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(1, native.calls.count { it == "unload" })
    }

    @Test fun `close does not wait for a read, and the engine ends unloaded`() {
        val gate = CountDownLatch(1)
        val native = FakeNative(readGate = gate)
        val engine = engine(native)
        assertTrue(engine.available)
        val worker = thread { engine.read(field) }
        assertTrue(native.entered.await(10, TimeUnit.SECONDS))
        val closer = thread { engine.close() }
        closer.join(5_000)
        assertFalse(closer.isAlive, "close waited for the read")
        gate.countDown()
        worker.join(10_000)
        assertFalse(native.loaded)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(listOf("library", "load", "read", "unload"), native.calls)
    }

    companion object {
        /** wire.rs `encode_error(NOT_LOADED)`. */
        val NOT_LOADED_REPLY: IntArray = "the support-score engine is not loaded".let { text ->
            intArrayOf(ScoreWire.MAGIC, 1, -1, -1, 2, 0, 0, Float.NaN.toRawBits(), Float.NaN.toRawBits(), 0, text.length) +
                text.codePoints().toArray()
        }
    }
}
