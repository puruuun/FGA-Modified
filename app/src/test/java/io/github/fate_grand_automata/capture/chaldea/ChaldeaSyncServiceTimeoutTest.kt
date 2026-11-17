package io.github.fate_grand_automata.capture.chaldea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ChaldeaSyncServiceTimeoutTest {
    private class BlockingInputStream : InputStream() {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private var buf = ByteArray(0)
        private var pos = 0
        private var eof = false
        val closed = AtomicBoolean(false)

        fun append(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            lock.withLock {
                buf += bytes
                changed.signalAll()
            }
        }

        fun finish() {
            lock.withLock {
                eof = true
                changed.signalAll()
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.withLock {
                while (pos >= buf.size && !eof && !closed.get()) {
                    try {
                        changed.await()
                    } catch (e: InterruptedException) {
                        throw IOException("Interrupted", e)
                    }
                }
                if (closed.get()) throw IOException("Stream closed")
                if (pos >= buf.size && eof) return -1
                val count = minOf(len, buf.size - pos)
                System.arraycopy(buf, pos, b, off, count)
                pos += count
                return count
            }
        }

        override fun close() {
            lock.withLock {
                closed.set(true)
                changed.signalAll()
            }
        }
    }

    private class FakeProcess(
        initialOutput: String = "",
        closeStdoutImmediately: Boolean = false,
        initialExitCode: Int? = null,
    ) : Process() {
        private val stdout = BlockingInputStream().apply {
            if (initialOutput.isNotEmpty()) append(initialOutput)
            if (closeStdoutImmediately) finish()
        }
        private val stdin = ByteArrayOutputStream()
        private val stderr = ByteArray(0).inputStream()
        private val exitLock = ReentrantLock()
        private val exitedCondition = exitLock.newCondition()

        @Volatile
        private var exited = initialExitCode != null

        @Volatile
        private var exitCode = initialExitCode ?: 0

        @Volatile
        var destroyed = false
            private set

        val streamClosed: Boolean get() = stdout.closed.get()

        fun complete(code: Int) {
            stdout.finish()
            exitLock.withLock {
                exitCode = code
                exited = true
                exitedCondition.signalAll()
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            exitLock.withLock {
                while (!exited) exitedCondition.await()
                return exitCode
            }
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            var remainingNs = unit.toNanos(timeout)
            exitLock.withLock {
                while (!exited) {
                    if (remainingNs <= 0L) return false
                    remainingNs = exitedCondition.awaitNanos(remainingNs)
                }
                return true
            }
        }

        override fun exitValue(): Int {
            if (!exited) throw IllegalThreadStateException("Process has not exited")
            return exitCode
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            exitLock.withLock {
                exitCode = 137
                exited = true
                exitedCondition.signalAll()
            }
            return this
        }

        override fun isAlive(): Boolean = !exited
    }

    private fun assertNoReaderThreadAlive() {
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == "chaldea-shell-io" && it.isAlive })
    }

    @Test
    fun runReturnsNullExitAndDestroysProcessWhenStdoutNeverCloses() {
        val proc = FakeProcess()
        val result = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { proc }
        }
        assertNull(result.exit)
        assertEquals("", result.out)
        assertTrue(proc.destroyed)
        assertTrue(proc.streamClosed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun streamReturnsNullAndDestroysProcessWhenStdoutNeverCloses() {
        val proc = FakeProcess()
        val result = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            streamShell(arrayOf("dumpsys"), { lines -> lines.toList() }, timeoutMs = 200L) { proc }
        }
        assertNull(result)
        assertTrue(proc.destroyed)
        assertTrue(proc.streamClosed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun runAndStreamReturnNormalResultOnExitZero() {
        val runProc = FakeProcess(initialOutput = "1234\n5678\n", closeStdoutImmediately = true, initialExitCode = 0)
        val shell = runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { runProc }
        assertEquals(0, shell.exit)
        assertEquals("1234\n5678\n", shell.out)
        assertFalse(runProc.destroyed)

        val streamProc = FakeProcess(initialOutput = "alpha\nbeta\n", closeStdoutImmediately = true, initialExitCode = 0)
        val lines = streamShell(arrayOf("dumpsys"), { it.toList() }, timeoutMs = 200L) { streamProc }
        assertEquals(listOf("alpha", "beta"), lines)
        assertFalse(streamProc.destroyed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun streamFeedsLinesWithoutWaitingForEof() {
        val proc = FakeProcess(initialOutput = "target-line\n", closeStdoutImmediately = false, initialExitCode = null)
        val result = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            streamShell(
                arrayOf("dumpsys"),
                { seq ->
                    val found = seq.first { it == "target-line" }
                    proc.complete(0)
                    found
                },
                timeoutMs = 1_000L,
            ) { proc }
        }
        assertEquals("target-line", result)
        assertFalse(proc.destroyed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun runKeepsExitOneAndOutputWhileStreamReturnsNullOnExitOne() {
        val runProc = FakeProcess(initialOutput = "not found\n", closeStdoutImmediately = true, initialExitCode = 1)
        val shell = runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { runProc }
        assertEquals(1, shell.exit)
        assertEquals("not found\n", shell.out)
        assertFalse(runProc.destroyed)

        val streamProc = FakeProcess(initialOutput = "line1\n", closeStdoutImmediately = true, initialExitCode = 1)
        val streamed = streamShell(arrayOf("dumpsys"), { it.toList() }, timeoutMs = 200L) { streamProc }
        assertNull(streamed)
        assertFalse(streamProc.destroyed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun runTimesOutAndKeepsPartialOutputWhenProcessOutputsThenHangs() {
        val proc = FakeProcess(initialOutput = "partial-output\n", closeStdoutImmediately = false, initialExitCode = null)
        val result = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { proc }
        }
        assertNull(result.exit)
        assertEquals("partial-output\n", result.out)
        assertTrue(proc.destroyed)
        assertTrue(proc.streamClosed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun streamTimesOutWhenProcessOutputsThenHangs() {
        val proc = FakeProcess(initialOutput = "partial-line\n", closeStdoutImmediately = false, initialExitCode = null)
        val result = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            streamShell(arrayOf("dumpsys"), { it.toList() }, timeoutMs = 200L) { proc }
        }
        assertNull(result)
        assertTrue(proc.destroyed)
        assertTrue(proc.streamClosed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun runAndStreamTimeOutWhenStdoutClosesButProcessNeverExits() {
        val runProc = FakeProcess(initialOutput = "done\n", closeStdoutImmediately = true, initialExitCode = null)
        val runResult = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            runShell(arrayOf("pidof", "pkg"), timeoutMs = 200L) { runProc }
        }
        assertNull(runResult.exit)
        assertEquals("done\n", runResult.out)
        assertTrue(runProc.destroyed)

        val streamProc = FakeProcess(initialOutput = "done\n", closeStdoutImmediately = true, initialExitCode = null)
        val streamResult = assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            streamShell(arrayOf("dumpsys"), { it.toList() }, timeoutMs = 200L) { streamProc }
        }
        assertNull(streamResult)
        assertTrue(streamProc.destroyed)
        assertNoReaderThreadAlive()
    }

    @Test
    fun processStartFailureReturnsFailureResult() {
        val runResult = runShell(arrayOf("missing"), timeoutMs = 200L) { throw IOException("no su") }
        assertNull(runResult.exit)
        assertEquals("", runResult.out)

        val streamResult = streamShell(arrayOf("missing"), { it.toList() }, timeoutMs = 200L) { throw IOException("no su") }
        assertNull(streamResult)
        assertNoReaderThreadAlive()
    }

    @Test
    fun awaitProcessExitHandlesImmediateAndTimedOutProcesses() {
        val exited = FakeProcess(closeStdoutImmediately = true, initialExitCode = 0)
        assertTrue(awaitProcessExit(exited, 0L))

        val hung = FakeProcess()
        assertFalse(awaitProcessExit(hung, TimeUnit.MILLISECONDS.toNanos(50)))
        destroyProcessCompat(hung)
        assertTrue(hung.destroyed)
    }
}
