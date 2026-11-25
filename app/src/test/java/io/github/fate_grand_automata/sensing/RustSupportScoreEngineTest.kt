package io.github.fate_grand_automata.sensing

import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RustSupportScoreEngineTest {
    /**
     * Answers what fga-ffi answers: a read when a model is loaded, the not-loaded error reply
     * otherwise, a refused second load, and 1 or 0 from unload. [slot] is the process-wide engine
     * slot, shared by fakes that stand for two runs; [events] records both runs' loads and unloads
     * in order. [failOn] makes one call throw what a library that does not match RustSensing throws.
     */
    private class FakeNative(
        val libraryError: String? = null,
        val loadAnswer: String = "OK: 12",
        val failOn: String? = null,
        val fatalOn: String? = null,
        val loadGate: CountDownLatch? = null,
        val readGate: CountDownLatch? = null,
        val slot: AtomicBoolean = AtomicBoolean(false),
        val name: String = "",
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        @Volatile var busyPolls: Int = 0,
        @Volatile var refuseLoads: Int = 0,
    ) : ScoreNative {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val entered = CountDownLatch(1)

        /** Counts down when this run found the slot filled. */
        val sawBusySlot = CountDownLatch(1)

        val loaded: Boolean get() = slot.get()

        private fun call(name: String) {
            calls += name
            if (failOn == name) throw UnsatisfiedLinkError("No implementation found for $name")
            if (fatalOn == name) throw OutOfMemoryError("test: $name")
        }

        override fun loadLibrary(): String? = libraryError.also { call("library") }

        override fun load(modelPath: String): String {
            call("load")
            loadGate?.let { entered.countDown(); it.await(10, TimeUnit.SECONDS) }
            if (refuseLoads > 0) {
                refuseLoads--
                return ScoreWire.ALREADY_LOADED_REPLY
            }
            if (loadAnswer.startsWith("OK: ")) {
                if (!slot.compareAndSet(false, true)) return ScoreWire.ALREADY_LOADED_REPLY
                events += "load $name"
            }
            return loadAnswer
        }

        override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? {
            call("read")
            readGate?.let { entered.countDown(); it.await(10, TimeUnit.SECONDS) }
            return if (loaded) ScoreWireTest.GOLDEN_COMPOUND else NOT_LOADED_REPLY
        }

        override fun unload(): Int {
            call("unload")
            events += "unload $name"
            return if (slot.getAndSet(false)) ScoreWire.CODE_YES else ScoreWire.CODE_NO
        }

        override fun isLoaded(): Int {
            calls += "isLoaded"
            if (busyPolls > 0) {
                busyPolls--
                sawBusySlot.countDown()
                return ScoreWire.CODE_BUSY
            }
            return if (loaded) ScoreWire.CODE_YES.also { sawBusySlot.countDown() } else ScoreWire.CODE_NO
        }
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

    private fun engine(
        native: ScoreNative,
        mainThread: Boolean = false,
        slotWait: Duration = 5.seconds,
        stopRequested: () -> Boolean = { false },
        prepare: () -> File = { model },
    ) = RustSupportScoreEngine(native, prepare, isMainThread = { mainThread }, slotWait = slotWait, stopRequested = stopRequested)

    /** The calls without the `isLoaded` polls, whose number depends on timing. */
    private val FakeNative.work get() = calls.filter { it != "isLoaded" }

    private val unavailable = EngineField.Unknown(EngineUnknownReason.Unavailable)
    private val engineError = EngineField.Unknown(EngineUnknownReason.EngineError)

    @Test fun `nothing loads until the first use, and only once`() {
        val native = FakeNative()
        val engine = engine(native)
        assertEquals(emptyList(), native.work)
        assertNull(engine.loadTime)
        val score = engine.read(field)
        assertEquals(EngineField.Value(62), score.classScore)
        assertEquals(1234.microseconds, score.engineTime)
        engine.read(field)
        assertTrue(engine.available)
        assertNull(engine.unavailableReason)
        assertNotNull(engine.loadTime)
        assertEquals(listOf("library", "load", "read", "read"), native.work)
    }

    @Test fun `a missing library makes the engine unavailable without a load`() {
        val native = FakeNative(libraryError = "dlopen failed: library \"libfga_ffi.so\" not found")
        val engine = engine(native)
        assertFalse(engine.available)
        assertTrue(engine.unavailableReason!!.contains("libfga_ffi.so did not load"))
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(listOf("library"), native.work)
    }

    @Test fun `a missing model asset makes the engine unavailable`() {
        val native = FakeNative()
        val engine = engine(native, prepare = { throw FileNotFoundException("ocr/ocr-model.properties") })
        val score = engine.read(field)
        assertEquals(unavailable, score.grandScore)
        assertEquals("${RustSupportScoreEngine.MODEL_NOT_PREPARED}FileNotFoundException: ocr/ocr-model.properties", score.detail)
        assertEquals(listOf("library"), native.work)
    }

    @Test fun `an installed copy or folder that cannot be opened says so, not that the asset is missing`() {
        val path = "/data/user/0/app/no_backup/ocr-model/x.onnx.part: open failed: EACCES (Permission denied)"
        val engine = engine(FakeNative(), prepare = { throw FileNotFoundException(path) })
        assertEquals("${RustSupportScoreEngine.MODEL_NOT_PREPARED}FileNotFoundException: $path", engine.unavailableReason)
    }

    @Test fun `a model that does not match its pin makes the engine unavailable`() {
        val engine = engine(FakeNative(), prepare = { throw java.io.IOException("model asset has 5 bytes, the pin says 21234383") })
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
    }

    @Test fun `a damaged installed copy and a damaged asset make the engine unavailable without a load`() {
        val dir = java.nio.file.Files.createTempDirectory("ocr-model").toFile()
        try {
            val bytes = ByteArray(4096) { it.toByte() }
            val pin = OcrModelPin("m.onnx", java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
            val damaged = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
            File(dir, OcrModelInstaller.fileName(pin)).writeBytes(damaged)
            val native = FakeNative()
            val engine = engine(native, prepare = { OcrModelInstaller.install(pin, dir) { damaged.inputStream() } })
            assertFalse(engine.available)
            assertTrue(engine.unavailableReason!!.startsWith("${RustSupportScoreEngine.MODEL_NOT_PREPARED}IOException: model asset has SHA-256"), engine.unavailableReason)
            assertEquals(unavailable, engine.read(field).classScore)
            assertEquals(listOf("library"), native.work)
            assertEquals(emptyList(), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `a failed load makes the engine unavailable for the run`() {
        val native = FakeNative(loadAnswer = "ERR: inference backend: no such file")
        val engine = engine(native)
        assertFalse(engine.available)
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(listOf("library", "load"), native.work)
    }

    @Test fun `the main thread never loads`() {
        val native = FakeNative()
        val engine = engine(native, mainThread = true)
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(emptyList(), native.work)
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
        assertEquals(listOf("library", "load", "read", "unload"), unloadFails.work)
    }

    @Test fun `a close after a failing unload still ends closed`() {
        val native = FakeNative(failOn = "unload")
        val engine = engine(native)
        engine.read(field)
        engine.close()
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(RustSupportScoreEngine.CLOSED, engine.unavailableReason)
        assertEquals(listOf("library", "load", "read", "unload"), native.work)
    }

    @Test fun `a field whose pixels cannot be read is an engine error for that field only`() {
        val native = FakeNative()
        val engine = engine(native)
        assertEquals(engineError, engine.read(FakePattern { null }).classScore)
        val score = engine.read(FakePattern { throw IllegalStateException("released Mat") })
        assertEquals(engineError, score.classScore)
        assertTrue(score.detail!!.contains("released Mat"))
        assertNull(engine.unavailableReason)
        assertEquals(EngineField.Value(62), engine.read(field).classScore)
        assertEquals(listOf("library", "load", "read"), native.work)
    }

    /** Captures what the engine logs at warning level. */
    private class Warnings : timber.log.Timber.Tree() {
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == android.util.Log.WARN) messages += message
        }
    }

    private fun <T> withWarnings(block: (Warnings) -> T): T {
        val warnings = Warnings()
        timber.log.Timber.plant(warnings)
        try {
            return block(warnings)
        } finally {
            timber.log.Timber.uproot(warnings)
        }
    }

    /**
     * After a panic in a read, Rust answers that read with the panic as an error reply and drops
     * the model (score_engine_poisoned.rs), so the slot is empty and unload answers 0.
     */
    private class PanickingRead(val inner: FakeNative) : ScoreNative by inner {
        override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? {
            inner.readScore(pixels, width, height)
            inner.slot.set(false)
            return errorReply(PANIC)
        }
    }

    @Test fun `an engine error in a read ends the engine for the run, logged once with its text`() = withWarnings { warnings ->
        val slot = AtomicBoolean(false)
        val inner = FakeNative(slot = slot)
        val engine = engine(PanickingRead(inner))
        val score = engine.read(field)
        assertEquals(engineError, score.classScore)
        assertEquals(PANIC, score.detail)
        assertEquals(RustSupportScoreEngine.READ_FAILED + PANIC, engine.unavailableReason)
        assertFalse(engine.available)
        repeat(3) {
            val later = engine.read(field)
            assertEquals(unavailable, later.classScore)
            assertEquals(RustSupportScoreEngine.READ_FAILED + PANIC, later.detail)
        }
        assertEquals(listOf("library", "load", "read", "unload"), inner.work)
        assertEquals(1, warnings.messages.count { it.contains(PANIC) }, warnings.messages.toString())
        engine.close()
        assertEquals(listOf("library", "load", "read", "unload"), inner.work)
        assertEquals(RustSupportScoreEngine.CLOSED, engine.unavailableReason)

        // The next run's engine loads into the same slot and reads.
        val next = FakeNative(slot = slot)
        val nextEngine = engine(next)
        assertEquals(EngineField.Value(62), nextEngine.read(field).classScore)
        nextEngine.close()
        assertEquals(listOf("library", "load", "read", "unload"), next.work)
    }

    @Test fun `an error reply that leaves the model loaded unloads it at once`() = withWarnings { warnings ->
        val inner = FakeNative()
        val failing = object : ScoreNative by inner {
            override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? {
                inner.readScore(pixels, width, height)
                return errorReply("inference backend: tract run failed")
            }
        }
        val engine = engine(failing)
        assertEquals(engineError, engine.read(field).classScore)
        assertFalse(inner.loaded)
        assertEquals(RustSupportScoreEngine.READ_FAILED + "inference backend: tract run failed", engine.unavailableReason)
        engine.close()
        assertEquals(listOf("library", "load", "read", "unload"), inner.work)
        assertEquals(1, warnings.messages.size, warnings.messages.toString())
    }

    @Test fun `errors that leave the VM unusable propagate`() {
        val engine = engine(FakeNative())
        assertFailsWith<OutOfMemoryError> { engine.read(FakePattern { throw OutOfMemoryError("test") }) }
    }

    @Test fun `a JNI failure or a malformed reply is an engine error that ends the engine`() {
        for (reply in listOf<IntArray?>(null, intArrayOf(0))) {
            val inner = FakeNative()
            val engine = RustSupportScoreEngine(object : ScoreNative by inner {
                override fun readScore(pixels: ByteArray, width: Int, height: Int): IntArray? = reply
            }, { model }, isMainThread = { false })
            val score = engine.read(field)
            assertEquals(engineError, score.classScore)
            assertTrue(score.detail!!.startsWith("malformed engine reply"), score.detail)
            assertEquals(RustSupportScoreEngine.READ_FAILED + score.detail, engine.unavailableReason)
            assertEquals(unavailable, engine.read(field).classScore)
            assertEquals(listOf("library", "load", "unload"), inner.work)
        }
    }

    @Test fun `close unloads once and later reads are unavailable`() {
        val native = FakeNative()
        val engine = engine(native)
        engine.read(field)
        engine.close()
        engine.close()
        assertEquals(unavailable, engine.read(field).classScore)
        assertFalse(engine.available)
        assertEquals(listOf("library", "load", "read", "unload"), native.work)
    }

    @Test fun `close before any use calls nothing and nothing loads afterwards`() {
        val native = FakeNative()
        val engine = engine(native)
        engine.close()
        assertFalse(engine.available)
        assertEquals(unavailable, engine.read(field).classScore)
        assertEquals(emptyList(), native.work)
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
        assertEquals(listOf("library", "load", "unload"), native.work)
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
        assertEquals(listOf("library", "load", "read", "unload"), native.work)
    }

    @Test fun `the next run loads only after the previous run's engine unloaded`() {
        val slot = AtomicBoolean(false)
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val gate = CountDownLatch(1)
        val nativeA = FakeNative(readGate = gate, slot = slot, name = "A", events = events)
        val nativeB = FakeNative(slot = slot, name = "B", events = events)
        val a = engine(nativeA)
        assertTrue(a.available)
        val workerA = thread { a.read(field) }
        assertTrue(nativeA.entered.await(10, TimeUnit.SECONDS))
        a.close()
        val b = engine(nativeB)
        var scoreB: EngineScore? = null
        val workerB = thread { scoreB = b.read(field) }
        assertTrue(nativeB.sawBusySlot.await(10, TimeUnit.SECONDS))
        gate.countDown()
        workerA.join(10_000)
        workerB.join(10_000)
        assertEquals(EngineField.Value(62), scoreB!!.classScore)
        assertEquals(EngineField.Value(62), b.read(field).classScore)
        b.close()
        assertEquals(listOf("load A", "unload A", "load B", "unload B"), events.toList())
        assertEquals(1, nativeA.calls.count { it == "unload" })
        assertEquals(1, nativeB.calls.count { it == "unload" })
        assertEquals(1, nativeB.calls.count { it == "load" })
        assertFalse(slot.get())
    }

    @Test fun `a slot that stays filled past the wait leaves the run without an engine and loads nothing`() {
        val slot = AtomicBoolean(true)
        val native = FakeNative(slot = slot, name = "B")
        val engine = engine(native, slotWait = 100.milliseconds)
        assertEquals(RustSupportScoreEngine.SLOT_BUSY, engine.unavailableReason)
        assertEquals(unavailable, engine.read(field).classScore)
        engine.close()
        assertEquals(listOf("library"), native.work)
        assertTrue(slot.get())
    }

    @Test fun `a close during the wait ends it without a load`() {
        val slot = AtomicBoolean(true)
        val native = FakeNative(slot = slot, name = "B")
        val engine = engine(native)
        var reason: String? = null
        val worker = thread { reason = engine.unavailableReason }
        assertTrue(native.sawBusySlot.await(10, TimeUnit.SECONDS))
        engine.close()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(RustSupportScoreEngine.CLOSED, reason)
        assertEquals(listOf("library"), native.work)
        assertTrue(slot.get())
    }

    @Test fun `a fatal error while a reader finishes the close leaves the lock free`() {
        val gate = CountDownLatch(1)
        val native = FakeNative(readGate = gate, fatalOn = "unload")
        val engine = engine(native)
        assertTrue(engine.available)
        var thrown: Throwable? = null
        val worker = thread { try { engine.read(field) } catch (t: Throwable) { thrown = t } }
        assertTrue(native.entered.await(10, TimeUnit.SECONDS))
        engine.close()
        gate.countDown()
        worker.join(10_000)
        assertTrue(thrown is OutOfMemoryError)
        val lock = RustSupportScoreEngine::class.java.getDeclaredField("work").apply { isAccessible = true }.get(engine)
        assertFalse((lock as java.util.concurrent.locks.ReentrantLock).isLocked)
        assertEquals(unavailable, engine.read(field).classScore)
    }

    @Test fun `an interrupted wait keeps the interrupt and loads nothing`() {
        val native = FakeNative(slot = AtomicBoolean(true), name = "B")
        val engine = engine(native)
        var reason: String? = null
        var stillInterrupted = false
        val worker = thread {
            reason = engine.unavailableReason
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        assertTrue(native.sawBusySlot.await(10, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertTrue(reason!!.contains("interrupted"))
        assertTrue(stillInterrupted)
        assertEquals(listOf("library"), native.work)
    }

    @Test fun `a busy slot is waited for and never loaded over`() {
        val native = FakeNative(busyPolls = 3)
        val engine = engine(native)
        assertNull(engine.unavailableReason)
        assertEquals(listOf("isLoaded", "isLoaded", "isLoaded", "isLoaded", "load"), native.calls.drop(1))
    }

    @Test fun `a refused load is retried once the slot is free`() {
        val native = FakeNative(refuseLoads = 1)
        val engine = engine(native)
        assertNull(engine.unavailableReason)
        assertEquals(2, native.calls.count { it == "load" })
        assertEquals(EngineField.Value(62), engine.read(field).classScore)
    }

    @Test fun `a slot kept by a third run ends at the bound`() {
        val native = FakeNative(refuseLoads = Int.MAX_VALUE)
        val engine = engine(native, slotWait = 100.milliseconds)
        assertEquals(RustSupportScoreEngine.SLOT_BUSY, engine.unavailableReason)
        assertFalse(native.loaded)
    }

    @Test fun `another load error fails at once`() {
        val native = FakeNative(loadAnswer = "ERR: inference backend: bad model")
        val engine = engine(native)
        assertEquals("model load failed: ERR: inference backend: bad model", engine.unavailableReason)
        assertEquals(1, native.calls.count { it == "load" })
    }

    @Test fun `a stop during the wait ends it long before the bound and loads nothing`() {
        val stopped = AtomicBoolean(false)
        val native = FakeNative(slot = AtomicBoolean(true), name = "B")
        val engine = engine(native, slotWait = 30.seconds, stopRequested = { stopped.get() })
        var reason: String? = null
        val worker = thread { reason = engine.unavailableReason }
        assertTrue(native.sawBusySlot.await(10, TimeUnit.SECONDS))
        stopped.set(true)
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertEquals(RustSupportScoreEngine.STOPPED, reason)
        assertEquals(listOf("library"), native.work)
    }

    @Test fun `a run stopped before its first use copies and loads nothing`() {
        val native = FakeNative()
        var prepared = 0
        val engine = engine(native, stopRequested = { true }, prepare = { prepared++; model })
        assertEquals(RustSupportScoreEngine.STOPPED, engine.unavailableReason)
        assertEquals(0, prepared)
        assertEquals(emptyList(), native.calls)
    }

    @Test fun `a close during the model copy loads nothing`() {
        val native = FakeNative()
        lateinit var engine: RustSupportScoreEngine
        engine = engine(native, prepare = { engine.close(); model })
        assertEquals(RustSupportScoreEngine.CLOSED, engine.unavailableReason)
        assertEquals(listOf("library"), native.work)
    }

    companion object {
        const val PANIC = "panic: the model panicked mid-read"

        /** wire.rs `encode_error(text)`. */
        fun errorReply(text: String): IntArray =
            intArrayOf(ScoreWire.MAGIC, 1, -1, -1, 2, 0, 0, Float.NaN.toRawBits(), Float.NaN.toRawBits(), 0, 0, text.length) +
                text.codePoints().toArray()

        /** wire.rs `encode_error(NOT_LOADED)`. */
        val NOT_LOADED_REPLY: IntArray = errorReply("the support-score engine is not loaded")
    }
}
