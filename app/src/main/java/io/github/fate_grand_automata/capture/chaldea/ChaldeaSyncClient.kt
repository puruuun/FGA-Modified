package io.github.fate_grand_automata.capture.chaldea

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaBackupReceiver
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaGameDataReader
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaGameDataResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaPatch
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncSession
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuFacts
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * The Shizuku provider is always enabled in the manifest: Shizuku hands its binder over only when the app's process
 * starts (or the Shizuku server starts), so a provider that is enabled later would get nothing until a restart. An
 * earlier beta disabled the component at runtime, and that state survives updates, so [resetToDefault] puts it back
 * to the manifest value once at startup. It is the only package-manager call left; it does not touch the Shizuku API.
 */
object ShizukuProviderReset {
    private fun component(context: Context) = ComponentName(context, "rikka.shizuku.ShizukuProvider")

    /** Returns true when the component had a leftover runtime state that was reset. Never throws. */
    fun resetToDefault(context: Context): Boolean = try {
        val pm = context.packageManager
        val component = component(context)
        if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        false
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
    private val args by lazy { userServiceArgs(context.packageName, BuildConfig.DEBUG, BuildConfig.VERSION_CODE) }

    class Session internal constructor(private val service: IChaldeaSyncService, private val release: () -> Unit) : ChaldeaSyncSession {
        override suspend fun state(): ChaldeaStateResult = withContext(Dispatchers.IO) { ChaldeaStateResult.fromJson(service.state()) }

        override suspend fun forceStop(): ChaldeaStateResult = withContext(Dispatchers.IO) { ChaldeaStateResult.fromJson(service.forceStop()) }

        /**
         * Streams the base file into [sink]. The pipe is read on a dedicated thread that starts before the binder
         * call, so the service never blocks on a full pipe. [sink] is flushed but not closed.
         */
        override suspend fun prepare(sink: OutputStream): ChaldeaSyncSession.PrepareOutcome = withContext(Dispatchers.IO) {
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
            ChaldeaSyncSession.PrepareOutcome(result, got, got != null && ChaldeaBackupReceiver.matches(got, result))
        }

        override suspend fun commit(
            patch: ChaldeaPatch,
            expectedSha256: String,
            allowCreate: Boolean,
            expectedPackage: String,
            expectedBundleSha256: String,
        ): ChaldeaSyncResult =
            withContext(Dispatchers.IO) {
                ChaldeaSyncResult.fromJson(service.commit(patch.toJson(), expectedSha256, allowCreate, expectedPackage, expectedBundleSha256))
            }

        override suspend fun restore(bundle: File, sha256: String, packageName: String): ChaldeaSyncResult =
            withContext(Dispatchers.IO) {
                val pipe = ParcelFileDescriptor.createPipe()
                val feeder = Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> bundle.inputStream().use { it.copyTo(out) } }
                    }
                }, "ChaldeaRestoreFeeder")
                feeder.start()
                val text = try {
                    service.restore(pipe[0], sha256, packageName)
                } finally {
                    runCatching { pipe[0].close() }
                }
                ChaldeaSyncResult.fromJson(text)
            }

        override suspend fun gameData(knownVersion: String?): ChaldeaGameDataResult =
            withContext(Dispatchers.IO) {
                try {
                    ChaldeaGameDataReader.parseResponse(service.gameData(knownVersion))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name)
                }
            }

        /** Unbinds and tells the service to exit. Safe to call twice. */
        override fun close() = release()
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

        /** Shizuku refuses to add a user service without a process name suffix (IllegalArgumentException). */
        const val PROCESS_SUFFIX = "chaldea_sync"

        internal fun userServiceArgs(packageName: String, debuggable: Boolean, versionCode: Int): Shizuku.UserServiceArgs =
            Shizuku.UserServiceArgs(ComponentName(packageName, ChaldeaSyncService::class.java.name))
                .processNameSuffix(PROCESS_SUFFIX)
                .tag(TAG)
                .daemon(false)
                .debuggable(debuggable)
                .version(versionCode)
    }
}
