package io.github.fate_grand_automata.qa.suites

import android.util.Log
import io.github.fate_grand_automata.qa.NativeProbe
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * One test per stage 1-0 probe. The device QA runner has no JUnit runner, so the `nativeProbe`
 * suite runs these methods through JUnitCore (see [runNativeProbeCase]).
 */
class NativeProbeTest {
    @Before
    fun libraryPackaged() {
        assumeTrue("libfga_ffi.so is not packaged; build with -Pfga.native=true", NativeProbe.loaded)
    }

    private fun probe(name: String, call: () -> String): String =
        call().also { Log.i(TAG, "$name: $it") }

    @Test
    fun ping() {
        val result = probe("ping", NativeProbe::ping)
        assertTrue(result, result.startsWith("OK:"))
    }

    @Test
    fun panicProbe() {
        val result = probe("panicProbe", NativeProbe::panicProbe)
        assertTrue(result, result.startsWith("ERR: panic:"))
    }

    @Test
    fun ringProbe() {
        val result = probe("ringProbe", NativeProbe::ringProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    @Test
    fun rustlsProbe() {
        val result = probe("rustlsProbe", NativeProbe::rustlsProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    @Test
    fun tractProbe() {
        val result = probe("tractProbe", NativeProbe::tractProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    /** Evidence for diagnosing a crash elsewhere, not a pass/fail check. */
    @Test
    fun cpuProbe() {
        probe("cpuProbe", NativeProbe::cpuProbe)
    }

    companion object {
        const val TAG = "FgaNativeProbe"
    }
}
