package io.github.fate_grand_automata.touch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.capture.chaldea.ChaldeaRootClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Bound session to a privileged [ITouchInjector] (Shizuku or root). Never throws across the binder.
 */
interface TouchInjectorSession : AutoCloseable {
    suspend fun tap(x: Int, y: Int, holdMs: Long): Boolean
    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    suspend fun describe(): String
    override fun close()
}

/**
 * Clients that bind [TouchInjectorService] (over Shizuku) or [TouchRootService] (over libsu root) on demand when the
 * user starts a test on that path, and unbind when the test screen closes (issue #387, R1).
 */
class TouchInjectorClient private constructor(
    private val opener: suspend (Long) -> TouchInjectorSession?,
) {
    suspend fun open(timeoutMs: Long = 10_000): TouchInjectorSession? = opener(timeoutMs)

    class Session internal constructor(
        private val service: ITouchInjector,
        private val release: () -> Unit,
    ) : TouchInjectorSession {
        private val closed = AtomicBoolean(false)

        override suspend fun tap(x: Int, y: Int, holdMs: Long): Boolean = withContext(Dispatchers.IO) {
            try {
                service.tap(x, y, holdMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }

        override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    service.drag(x1, y1, x2, y2, durationMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
            }

        override suspend fun describe(): String = withContext(Dispatchers.IO) {
            try {
                service.describe()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                "binder_error(${t.javaClass.simpleName})"
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release()
            }
        }
    }

    companion object {
        const val TAG = "touch-injector"
        const val PROCESS_SUFFIX = "touch_injector"

        internal fun userServiceArgs(
            packageName: String,
            debuggable: Boolean,
            versionCode: Int,
        ): Shizuku.UserServiceArgs =
            Shizuku.UserServiceArgs(ComponentName(packageName, TouchInjectorService::class.java.name))
                .processNameSuffix(PROCESS_SUFFIX)
                .tag(TAG)
                .daemon(false)
                .debuggable(debuggable)
                .version(versionCode)

        fun forShizuku(context: Context): TouchInjectorClient {
            val appContext = context.applicationContext
            val args by lazy {
                userServiceArgs(appContext.packageName, BuildConfig.DEBUG, BuildConfig.VERSION_CODE)
            }
            fun unbind(connection: ServiceConnection) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
            return TouchInjectorClient { timeoutMs ->
                var connection: ServiceConnection? = null
                val session = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<TouchInjectorSession?> { cont ->
                        val c = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                                if (binder != null && binder.pingBinder() && cont.isActive) {
                                    cont.resume(Session(ITouchInjector.Stub.asInterface(binder)) { unbind(this) })
                                }
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {}
                        }
                        connection = c
                        cont.invokeOnCancellation { unbind(c) }
                        try {
                            Shizuku.bindUserService(args, c)
                        } catch (_: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (session == null) connection?.let { unbind(it) }
                session
            }
        }

        fun forRoot(
            context: Context,
            isRootShell: () -> Boolean = { ChaldeaRootClient.openRootShell() },
            bindService: (Intent, ServiceConnection) -> Unit = { intent, conn -> RootService.bind(intent, conn) },
            unbindService: (ServiceConnection) -> Unit = { conn -> RootService.unbind(conn) },
        ): TouchInjectorClient {
            val appContext = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            fun unbind(connection: ServiceConnection, done: AtomicBoolean) {
                if (!done.compareAndSet(false, true)) return
                val runnable = Runnable { runCatching { unbindService(connection) } }
                if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else main.post(runnable)
            }
            return TouchInjectorClient { timeoutMs ->
                val rootShell = withContext(Dispatchers.IO) { runCatching { isRootShell() }.getOrDefault(false) }
                if (!rootShell) return@TouchInjectorClient null

                val intent = Intent(appContext, TouchRootService::class.java)
                val connected = CompletableDeferred<TouchInjectorSession?>()
                val unbound = AtomicBoolean(false)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            connected.complete(
                                Session(ITouchInjector.Stub.asInterface(binder)) { unbind(this, unbound) },
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                val callerJob = currentCoroutineContext()[Job]
                var bound = false
                var session: TouchInjectorSession? = null
                try {
                    val started = withContext(Dispatchers.Main + NonCancellable) {
                        if (callerJob?.isActive == false) return@withContext false
                        runCatching {
                            bindService(intent, connection)
                            bound = true
                        }.isSuccess
                    }
                    currentCoroutineContext().ensureActive()
                    if (!started) return@TouchInjectorClient null
                    session = withTimeoutOrNull(timeoutMs) { connected.await() }
                    session
                } finally {
                    if (bound && session == null) {
                        unbind(connection, unbound)
                    }
                }
            }
        }
    }
}
