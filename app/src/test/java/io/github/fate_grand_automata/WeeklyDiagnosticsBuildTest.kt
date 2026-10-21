package io.github.fate_grand_automata

import io.github.fate_grand_automata.util.WeeklyPanelDiagnostics
import io.github.lib_automata.Pattern
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WeeklyDiagnosticsBuildTest {
    private inline fun <reified T> untouched(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected call: ${method.name}") } as T

    @Test fun `stable build writes no panel probe evidence or abort diagnostics`() {
        val storage = untouched<IStorageProvider>()
        val frame = untouched<Pattern>()
        val diagnostics = WeeklyPanelDiagnostics(storage)
        assertFalse(diagnostics.enabled)
        diagnostics.startProbe()
        assertFalse(diagnostics.enabled)
        assertNull(diagnostics.save(frame, 1, 0))
        assertNull(diagnostics.saveEvidence(frame, 1))
        assertNull(diagnostics.saveAbort(frame))
    }
}
