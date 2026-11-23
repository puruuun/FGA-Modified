package io.github.fate_grand_automata.sensing

import dagger.Binds
import io.github.fate_grand_automata.di.script.ScriptsModule
import io.github.fate_grand_automata.scripts.entrypoints.SupportScoreScan
import io.github.fate_grand_automata.scripts.supportSelection.EngineFirstSupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import javax.inject.Inject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design 0026, D-3: the run's [ISupportScoreReader] is the engine-first reader over the run's
 * existing reader, and the support score scan compares that existing reader, not the binding.
 * Hilt builds the graph from these declarations, so the test reads them.
 */
class SupportScoreReaderBindingTest {
    @Test fun `the script component binds the engine-first reader as the score reader`() {
        val binds = ScriptsModule::class.java.declaredMethods.filter {
            it.isAnnotationPresent(Binds::class.java) && it.returnType == ISupportScoreReader::class.java
        }
        assertEquals(1, binds.size)
        assertEquals(listOf<Class<*>>(EngineFirstSupportScoreReader::class.java), binds.single().parameterTypes.toList())
    }

    @Test fun `the engine-first reader is built on the existing reader and the run's engine`() {
        val injected = EngineFirstSupportScoreReader::class.java.constructors.single { it.isAnnotationPresent(Inject::class.java) }
        val types = injected.parameterTypes.toList()
        assertTrue(SupportScoreReader::class.java in types)
        assertTrue(SupportScoreEngine::class.java in types)
        assertFalse(ISupportScoreReader::class.java in types)
    }

    @Test fun `the score scan injects the existing reader itself`() {
        val injected = SupportScoreScan::class.java.constructors.single { it.isAnnotationPresent(Inject::class.java) }
        val types = injected.parameterTypes.toList()
        assertTrue(SupportScoreReader::class.java in types)
        assertFalse(ISupportScoreReader::class.java in types)
        assertFalse(EngineFirstSupportScoreReader::class.java in types)
    }
}
