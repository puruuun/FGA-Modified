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
        if (!NativeProbe.loaded) Log.w(TAG, NativeProbe.skipReason())
        assumeTrue(NativeProbe.skipReason(), NativeProbe.loaded)
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
        val result = cryptoProbe("ringProbe", NativeProbe::ringProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    @Test
    fun rustlsProbe() {
        val result = cryptoProbe("rustlsProbe", NativeProbe::rustlsProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    /** The crypto probes exist only in a library built with `-Pfga.native.crypto=true` (design 0026 PR D). */
    private fun cryptoProbe(name: String, call: () -> String): String = try {
        probe(name, call)
    } catch (_: UnsatisfiedLinkError) {
        assumeTrue(CRYPTO_SKIPPED, false)
        error("unreachable")
    }

    @Test
    fun tractProbe() {
        val result = probe("tractProbe", NativeProbe::tractProbe)
        assertTrue(result, result.startsWith("OK:"))
    }

    /** A failed argument read must come back as text with no Java exception left pending (#533). */
    @Test
    fun ocrLoadNullPath() {
        val result = probe("ocrLoadNullPath") { NativeProbe.ocrLoad(null) }
        assertTrue(result, result.startsWith("ERR: modelPath: "))
    }

    /** Evidence for diagnosing a crash elsewhere, not a pass/fail check. */
    @Test
    fun cpuProbe() {
        probe("cpuProbe", NativeProbe::cpuProbe)
    }

    companion object {
        const val TAG = "FgaNativeProbe"
        const val CRYPTO_SKIPPED =
            "libfga_ffi.so was built without the crypto-probes feature; build with -Pfga.native=true -Pfga.native.crypto=true"
    }
}
