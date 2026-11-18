package io.github.fate_grand_automata.capture

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ProcessRootShellTest {
    /** A `su` that waits for its dialog: it never exits by itself and its stdout ends only when it is destroyed. */
    private class WaitingProcess : Process() {
        val destroyed = CountDownLatch(1)
        private val stdout = object : InputStream() {
            override fun read(): Int {
                destroyed.await()
                return -1
            }
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArray(0).inputStream()
        override fun waitFor(): Int {
            destroyed.await()
            return 137
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed.await(timeout, unit)
        override fun exitValue(): Int = if (destroyed.count == 0L) 137 else throw IllegalThreadStateException()
        override fun destroy() = destroyed.countDown()
        override fun destroyForcibly(): Process = also { destroyed.countDown() }
    }

    @Test
    fun interruptingTheCallerDestroysTheWaitingProcessAtOnce() {
        val process = WaitingProcess()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val thread = Thread {
            try {
                ProcessRootShell { started.countDown(); process }.run(listOf("su", "-c", "id"), "", 30)
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        thread.interrupt()
        assertTrue(finished.await(5, TimeUnit.SECONDS), "run() kept waiting after the interrupt")
        assertTrue(process.destroyed.await(5, TimeUnit.SECONDS), "the process was left running")
        assertTrue(assertNotNull(failure.get()) is InterruptedException)
    }

    @Test
    fun theTimeoutDestroysTheProcessToo() {
        val process = WaitingProcess()
        val out = ProcessRootShell { process }.run(listOf("su", "-c", "id"), "", 1)
        assertTrue(out.timedOut)
        assertEquals(-1, out.exitCode)
        assertTrue(process.destroyed.await(5, TimeUnit.SECONDS))
    }
}
