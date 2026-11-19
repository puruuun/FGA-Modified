package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.TextLineOcrOptions
import kotlin.test.Test
import kotlin.test.assertEquals

class RunEnginesTest {
    private class FakeOcr(val log: MutableList<String>, val name: String, val throwOnClose: Boolean = false) : OcrService {
        override fun detectText(pattern: Pattern): String = error("unused")
        override fun detectTextLine(pattern: Pattern, options: TextLineOcrOptions): OcrText = error("unused")
        override fun close() {
            log += "ocr $name closed"
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

    private val log = mutableListOf<String>()
    private val engines = RunEngines()

    private fun start(name: String, failing: Boolean = false) =
        engines.attach(FakeOcr(log, name, failing), FakeEngine(log, name, failing))

    /**
     * ScriptManager calls release() on each way a run ends: the script exit (normal or by an
     * exception, both through onScriptExit), a failed screen detection, a skill screen without a
     * servant, a cancelled launcher, and the service's destruction (ScriptRunnerServiceController).
     */
    @Test fun `each end of a run closes both engines once`() {
        for (end in listOf("script exit", "detection failed", "no servant", "cancelled", "service destroyed")) {
            log.clear()
            start(end)
            engines.release()
            engines.release()
            assertEquals(listOf("ocr $end closed", "engine $end closed"), log, end)
        }
    }

    @Test fun `a run that exits and then the service ends closes once`() {
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
}
