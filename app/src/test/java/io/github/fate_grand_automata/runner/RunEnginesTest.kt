package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.TextLineOcrOptions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the [RunEngines] helper on its own. [ScriptManager]'s call sites (which paths call
 * `releaseOcrService` and when) are not covered here.
 */
class RunEnginesTest {
    /** [closeGate] stands for TesseractOcrService.close waiting for a running OCR call. */
    private class FakeOcr(
        val log: MutableList<String>,
        val name: String,
        val throwOnClose: Boolean = false,
        val closeGate: CountDownLatch? = null,
    ) : OcrService {
        val closing = CountDownLatch(1)

        override fun detectText(pattern: Pattern): String = error("unused")
        override fun detectTextLine(pattern: Pattern, options: TextLineOcrOptions): OcrText = error("unused")
        override fun close() {
            log += "ocr $name closed"
            closing.countDown()
            closeGate?.await(10, TimeUnit.SECONDS)
            if (throwOnClose) error("close failed")
        }
    }

    private class FakeEngine(val log: MutableList<String>, val name: String, val throwOnClose: Boolean = false) :
        SupportScoreEngine by NoSupportScoreEngine {
        override fun close() {
            log += "engine $name closed"
            if (throwOnClose) error("close failed")
        }
    }

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val engines = RunEngines()

    private fun start(name: String, failing: Boolean = false) =
        engines.attach(FakeOcr(log, name, failing), FakeEngine(log, name, failing))

    @Test fun `releasing twice closes each engine once`() {
        start("a")
        engines.release()
        engines.release()
        assertEquals(listOf("ocr a closed", "engine a closed"), log)
    }

    @Test fun `a new run releases what the last one left`() {
        start("a")
        start("b")
        assertEquals(listOf("ocr a closed", "engine a closed"), log)
        engines.release()
        assertEquals(listOf("ocr a closed", "engine a closed", "ocr b closed", "engine b closed"), log)
    }

    @Test fun `a failing close of the OCR still closes the engine`() {
        start("a", failing = true)
        engines.release()
        engines.release()
        assertEquals(listOf("ocr a closed", "engine a closed"), log)
    }

    @Test fun `a close that blocks does not hold up another release or attach`() {
        val gate = CountDownLatch(1)
        val slowOcr = FakeOcr(log, "a", closeGate = gate)
        engines.attach(slowOcr, FakeEngine(log, "a"))
        val first = thread { engines.release() }
        assertTrue(slowOcr.closing.await(10, TimeUnit.SECONDS))

        val second = thread { engines.release() }
        second.join(5_000)
        assertFalse(second.isAlive, "a second release waited for the first one's close")
        val attach = thread { engines.attach(FakeOcr(log, "b"), FakeEngine(log, "b")) }
        attach.join(5_000)
        assertFalse(attach.isAlive, "an attach waited for the first release's close")
        assertTrue(first.isAlive)

        gate.countDown()
        first.join(10_000)
        assertFalse(first.isAlive)
        engines.release()
        assertEquals(1, log.count { it == "ocr a closed" })
        assertEquals(1, log.count { it == "engine a closed" })
        assertEquals(1, log.count { it == "ocr b closed" })
        assertEquals(1, log.count { it == "engine b closed" })
    }
}
