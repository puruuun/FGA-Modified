package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.ServiceConnection
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaRootClientTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private class FakeShell(private val shellStatus: Int) : Shell() {
        var closed = false
        override fun isAlive() = !closed
        override fun execTask(task: Task) {}
        override fun submitTask(task: Task) {}
        override fun newJob(): Job = throw UnsupportedOperationException()
        override fun getStatus() = if (closed) UNKNOWN else shellStatus
        override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
            closed = true
            return true
        }
        override fun close() {
            closed = true
        }
    }

    // ---- F2: cached non-root shell is closed before calling getShell() ----

    @Test
    fun openRootShellClosesNonRootCachedShellBeforeCallingGetShell() {
        val nonRoot = FakeShell(Shell.NON_ROOT_SHELL)
        val root = FakeShell(Shell.ROOT_SHELL)
        var getCalls = 0
        val ok = ChaldeaRootClient.openRootShell(
            cachedShell = { nonRoot },
            getShell = {
                getCalls++
                assertTrue(nonRoot.closed, "non-root cached shell must be closed before getShell()")
                root
            },
        )
        assertTrue(ok)
        assertEquals(1, getCalls)
        assertFalse(root.closed)
    }

    @Test
    fun openRootShellKeepsRootCachedShellWithoutClosing() {
        val root = FakeShell(Shell.ROOT_SHELL)
        val ok = ChaldeaRootClient.openRootShell(
            cachedShell = { root },
            getShell = { root },
        )
        assertTrue(ok)
        assertFalse(root.closed)
    }

    // ---- F5: bind + cancel / timeout / close unbind guarantees ----

    private fun pumpMainUntil(condition: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("condition not reached")
    }

    @Test
    fun cancellingRightAfterBindStillCallsUnbindOnce() = runBlocking {
        var binds = 0
        var unbinds = 0
        lateinit var job: Job
        val client = ChaldeaRootClient(
            context = context,
            isRootShell = { true },
            bindService = { _, _ ->
                binds++
                // Cancel while Dispatchers.Main returns, before open() checks `started`.
                job.cancel()
            },
            unbindService = { unbinds++ },
        )

        job = launch(Dispatchers.Default) { client.open(timeoutMs = 5_000) }
        pumpMainUntil { job.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(job.isCancelled)
        assertEquals(1, binds)
        assertEquals(1, unbinds)
    }

    @Test
    fun cancellingBeforeMainBindRunsDoesNotBindOrUnbind() = runBlocking {
        var binds = 0
        var unbinds = 0
        val shellDone = CompletableDeferred<Unit>()
        val client = ChaldeaRootClient(
            context = context,
            isRootShell = {
                shellDone.complete(Unit)
                true
            },
            bindService = { _, _ -> binds++ },
            unbindService = { unbinds++ },
        )
        val job = launch(Dispatchers.Default) { client.open(timeoutMs = 5_000) }
        shellDone.await()
        // Wait briefly so withContext(Dispatchers.Main + NonCancellable) posts to the main looper before it idles.
        Thread.sleep(50)
        job.cancel()
        pumpMainUntil { job.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, binds)
        assertEquals(0, unbinds)
    }

    @Test
    fun timeoutCallsUnbindOnce() = runBlocking {
        var binds = 0
        var unbinds = 0
        val client = ChaldeaRootClient(
            context = context,
            isRootShell = { true },
            bindService = { _, _ -> binds++ },
            unbindService = { unbinds++ },
        )

        var session: ChaldeaSyncClient.Session? = null
        val job = launch(Dispatchers.Default) { session = client.open(timeoutMs = 20) }
        pumpMainUntil { job.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(session)
        assertEquals(1, binds)
        assertEquals(1, unbinds)
    }

    @Test
    fun closingReturnedSessionUnbindsOnce() = runBlocking {
        var unbinds = 0
        var savedConn: ServiceConnection? = null
        val client = ChaldeaRootClient(
            context = context,
            isRootShell = { true },
            bindService = { _, conn ->
                savedConn = conn
                conn.onServiceConnected(null, ChaldeaSyncService())
            },
            unbindService = { unbinds++ },
        )

        var session: ChaldeaSyncClient.Session? = null
        val job = launch(Dispatchers.Default) { session = client.open(timeoutMs = 5_000) }
        pumpMainUntil { job.isCompleted }

        assertNotNull(session)
        assertNotNull(savedConn)
        assertEquals(0, unbinds)

        session!!.close()
        session!!.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, unbinds)
    }

    @Test
    fun bindFailureDoesNotCallUnbind() = runBlocking {
        var unbinds = 0
        val client = ChaldeaRootClient(
            context = context,
            isRootShell = { true },
            bindService = { _, _ -> throw SecurityException("denied") },
            unbindService = { unbinds++ },
        )

        var session: ChaldeaSyncClient.Session? = FakeSessionPlaceholder()
        val job = launch(Dispatchers.Default) { session = client.open(timeoutMs = 5_000) }
        pumpMainUntil { job.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(session)
        assertEquals(0, unbinds)
    }

    private fun FakeSessionPlaceholder(): ChaldeaSyncClient.Session =
        ChaldeaSyncClient.Session(ChaldeaSyncService()) {}
}
