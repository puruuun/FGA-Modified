package io.github.fate_grand_automata.capture.chaldea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChaldeaSyncServiceApi29Test {
    private class StubProcess(
        output: String = "",
        private var exited: Boolean = false,
        private var exitCode: Int = 0,
    ) : Process() {
        private val outStream = ByteArrayInputStream(output.toByteArray())
        private val errStream = ByteArray(0).inputStream()
        private val inStream = ByteArrayOutputStream()
        var destroyedForcibly = false
            private set

        override fun getOutputStream(): OutputStream = inStream
        override fun getInputStream(): InputStream = outStream
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited
        override fun exitValue(): Int = if (exited) exitCode else throw IllegalThreadStateException()
        override fun destroy() { exited = true }
        override fun destroyForcibly(): Process {
            destroyedForcibly = true
            exited = true
            return this
        }
    }

    @Test
    fun api29UsesWaitForWithTimeoutAndDestroyForcibly() {
        val ok = StubProcess(output = "ok\n", exited = true, exitCode = 0)
        val result = runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { ok }
        assertEquals(0, result.exit)
        assertEquals("ok\n", result.out)
        assertFalse(ok.destroyedForcibly)

        val hung = StubProcess(output = "", exited = false)
        val timedOut = runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { hung }
        assertNull(timedOut.exit)
        assertTrue(hung.destroyedForcibly)
    }
}
