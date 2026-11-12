package io.github.fate_grand_automata.capture.chaldea

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaBackupReceiver
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaPatch
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuFacts
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.OutputStream
import kotlin.coroutines.resume

/** Enables or disables the Shizuku provider component. While the sync switch is off the app does not touch Shizuku. */
object ShizukuProviderSwitch {
    private fun component(context: Context) = ComponentName(context, "rikka.shizuku.ShizukuProvider")

    fun isEnabled(context: Context): Boolean = context.packageManager.getComponentEnabledSetting(component(context)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP)
    }
}

/** Reads the current [ShizukuState] from the Shizuku API. Call only while the sync switch is on. */
object ShizukuStateReader {
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    fun current(context: Context): ShizukuState {
        val installed = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        val alive = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        if (!alive) return ShizukuFacts(installed, false, false, false, false).state()
        return try {
            ShizukuFacts(
                appInstalled = installed,
                binderAlive = true,
                serverOutdated = Shizuku.isPreV11(),
                permissionGranted = !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
                permissionDenied = !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale(),
            ).state()
        } catch (t: Throwable) {
            ShizukuFacts(installed, false, false, false, false).state()
        }
    }
}

/**
 * Binds the Chaldea UserService for the duration of one session. The scheduler (stage C) opens a session per state
 * poll and per write, and closes it right after, so the shell process lives only as long as it is needed.
 */
class ChaldeaSyncClient(private val context: Context) {
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ChaldeaSyncService::class.java.name))
            .tag(TAG)
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** Result of [Session.prepare]. [backupMatches] is true only when the stored bytes equal what was announced. */
    class PrepareOutcome(val result: ChaldeaSyncResult, val received: ChaldeaBackupReceiver.Received?, val backupMatches: Boolean)

    class Session internal constructor(private val service: IChaldeaSyncService, private val release: () -> Unit) {
        suspend fun state(): ChaldeaStateResult = withContext(Dispatchers.IO) { ChaldeaStateResult.fromJson(service.state()) }

        suspend fun forceStop(): ChaldeaStateResult = withContext(Dispatchers.IO) { ChaldeaStateResult.fromJson(service.forceStop()) }

        /**
         * Streams the base file into [sink]. The pipe is read on a dedicated thread that starts before the binder
         * call, so the service never blocks on a full pipe. [sink] is flushed but not closed.
         */
        suspend fun prepare(sink: OutputStream): PrepareOutcome = withContext(Dispatchers.IO) {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            val received = CompletableDeferred<Result<ChaldeaBackupReceiver.Received>>()
            val reader = Thread({
                received.complete(
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(readSide).use { ChaldeaBackupReceiver.receive(it, sink) }
                    },
                )
            }, "ChaldeaBackupReader")
            reader.start()
            val text = try {
                service.prepare(writeSide)
            } finally {
                // The service holds its own copy; closing ours lets the reader see end-of-stream.
                runCatching { writeSide.close() }
            }
            val result = ChaldeaSyncResult.fromJson(text)
            val got = received.await().getOrNull()
            PrepareOutcome(result, got, got != null && ChaldeaBackupReceiver.matches(got, result))
        }

        suspend fun commit(patch: ChaldeaPatch, expectedSha256: String, allowCreate: Boolean, expectedPackage: String): ChaldeaSyncResult =
            withContext(Dispatchers.IO) {
                ChaldeaSyncResult.fromJson(service.commit(patch.toJson(), expectedSha256, allowCreate, expectedPackage))
            }

        /** Unbinds and tells the service to exit. Safe to call twice. */
        fun close() = release()
    }

    /** Binds the service; null when it does not connect within [timeoutMs]. Always [Session.close] the result. */
    suspend fun open(timeoutMs: Long = 10_000): Session? {
        var connection: ServiceConnection? = null
        val session = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Session?> { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder() && cont.isActive) {
                            cont.resume(Session(IChaldeaSyncService.Stub.asInterface(binder)) { unbind(this) })
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                connection = c
                cont.invokeOnCancellation { unbind(c) }
                try {
                    Shizuku.bindUserService(args, c)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (session == null) connection?.let { unbind(it) }
        return session
    }

    private fun unbind(connection: ServiceConnection) {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    companion object {
        const val TAG = "chaldea-sync"
    }
}
