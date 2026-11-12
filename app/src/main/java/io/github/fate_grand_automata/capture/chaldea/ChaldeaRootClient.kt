package io.github.fate_grand_automata.capture.chaldea

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds [ChaldeaRootService] for the duration of one session, like [ChaldeaSyncClient] does over Shizuku, and hands
 * out the same [ChaldeaSyncClient.Session]. Only the scheduler calls [open], and it never does while a script runs.
 *
 * libsu needs `RootService.bind` and `unbind` on the main thread. The root process exits when the session is closed
 * (not a daemon), and closing never calls the Shizuku-only `destroy()`.
 */
class ChaldeaRootClient(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Binds the service; null when there is no root shell or it does not connect within [timeoutMs]. Always
     * [ChaldeaSyncClient.Session.close] the result.
     */
    suspend fun open(timeoutMs: Long = 10_000): ChaldeaSyncClient.Session? {
        // The one main shell of the process: created here, on first use, so Magisk's toast shows once per process.
        val rootShell = withContext(Dispatchers.IO) { runCatching { Shell.getShell().isRoot }.getOrDefault(false) }
        if (!rootShell) return null

        val intent = Intent(context, ChaldeaRootService::class.java)
        val connected = CompletableDeferred<ChaldeaSyncClient.Session?>()
        val unbound = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    connected.complete(ChaldeaSyncClient.Session(IChaldeaSyncService.Stub.asInterface(binder)) { unbind(this, unbound) })
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val started = withContext(Dispatchers.Main) { runCatching { RootService.bind(intent, connection) }.isSuccess }
        if (!started) return null
        return try {
            withTimeoutOrNull(timeoutMs) { connected.await() }.also { if (it == null) unbind(connection, unbound) }
        } catch (e: CancellationException) {
            unbind(connection, unbound)
            throw e
        }
    }

    /** Safe to call from any thread and more than once. */
    private fun unbind(connection: ServiceConnection, done: AtomicBoolean) {
        if (!done.compareAndSet(false, true)) return
        val run = Runnable { runCatching { RootService.unbind(connection) } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    companion object {
        /** Closes the process's libsu main shell. Called when the sync switch is turned off. */
        fun closeShell() {
            runCatching { Shell.getCachedShell()?.close() }
        }
    }
}
