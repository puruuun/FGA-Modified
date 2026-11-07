package io.github.fate_grand_automata.capture

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RootCaRunnerTest {
    @Test fun usesStdinAndTenSecondTimeout() {
        val runner = RootCaRunner { command, stdin, timeout ->
            assertEquals(listOf("su", "-c", "sh"), command)
            assertEquals("fixed script", stdin)
            assertEquals(10L, timeout)
            RootShellOutput(0, listOf("FGA_ROOT", "FGA_APPS=18", "FGA_INJECTED"))
        }
        assertEquals(RootCaResult.Injected, runner.inject("fixed script"))
    }
    @Test fun timeoutIsFailure() {
        assertIs<RootCaResult.Failed>(RootCaRunner { _, _, _ -> RootShellOutput(-1, emptyList(), true) }.inject(""))
    }
    @Test fun missingSuIsRootMissing() {
        assertEquals(RootCaResult.RootMissing, RootCaRunner { _, _, _ -> throw IOException("error=2, No such file") }.inject(""))
    }
    @Test fun rootDenialIsDenied() {
        assertEquals(RootCaResult.Denied, RootCaRunner { _, _, _ -> RootShellOutput(1, listOf("Permission denied")) }.inject(""))
    }
    @Test fun alreadyPresentAndFailureMarkersAreParsed() {
        assertEquals(RootCaResult.AlreadyPresent, RootCaInjector.parse(RootShellOutput(0, listOf("FGA_ROOT", "FGA_ALREADY_PRESENT"))))
        assertEquals(RootCaResult.Failed("Certificate verification failed"),
            RootCaInjector.parse(RootShellOutput(16, listOf("FGA_ROOT", "FGA_VERIFY_FAILED"))))
        assertIs<RootCaResult.Failed>(RootCaInjector.parse(RootShellOutput(0, listOf("FGA_ROOT"))))
    }
    @Test fun allOtherExceptionsAreFailures() {
        assertIs<RootCaResult.Failed>(RootCaRunner { _, _, _ -> error("fake failure") }.inject(""))
    }
}
