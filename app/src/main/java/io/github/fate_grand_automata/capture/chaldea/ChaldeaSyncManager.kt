package io.github.fate_grand_automata.capture.chaldea

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.captureSafely
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccountSource
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaBackupStore
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaServer
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncEnv
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncNowOutcome
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncScheduler
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncSession
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStatus
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStore
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide entry for the Chaldea direct sync (issue #355). The capture and script hooks have no Context, so
 * [init] (called once from the Application, which also holds the injected [PrefsCore]) is how they reach the
 * scheduler: each hook is one volatile read and a queue add, and does nothing while the switch is off.
 *
 * While the switch is off the app does not touch Shizuku, no scheduler exists and no thread runs. Turning it on
 * (or starting the process with it on) enables the Shizuku provider, registers the Shizuku listeners and the
 * resume callback, and creates the scheduler.
 */
/** What the capture and script code tells the sync. Every call must return at once. */
internal interface ChaldeaSyncHooks {
    fun onAccountsSaved(ids: Collection<String>)
    fun onAccountDeleted(id: String?)
    fun onScriptStart()
    fun onScriptEnd()
}

object ChaldeaSyncManager {
    private const val PERMISSION_REQUEST_CODE = 3551

    @Volatile private var scheduler: ChaldeaSyncScheduler? = null

    /** The hook target: the scheduler while the sync is on, nothing otherwise. Replaced in tests. */
    @Volatile internal var hooks: ChaldeaSyncHooks? = null
    private var app: Application? = null
    private var prefs: PrefsCore? = null
    private var client: ChaldeaSyncClient? = null
    private var enabledNow = false
    private val lock = Any()
    private var statusJob: Job? = null
    private var scriptJob: Job? = null

    private val _shizuku = MutableStateFlow(ShizukuState.NotRunning)
    val shizukuState: StateFlow<ShizukuState> = _shizuku.asStateFlow()
    private val _status = MutableStateFlow<ChaldeaSyncStatus>(ChaldeaSyncStatus.Idle)
    val status: StateFlow<ChaldeaSyncStatus> = _status.asStateFlow()
    private val _scriptRunning = MutableStateFlow(false)
    val scriptRunning: StateFlow<Boolean> = _scriptRunning.asStateFlow()

    private val scopeDispatcher by lazy {
        // One background-priority thread: patch building, backups and timers never compete with the game scripts.
        Executors.newSingleThreadExecutor { r ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "chaldea-sync").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
    private val scope by lazy { CoroutineScope(SupervisorJob() + scopeDispatcher) }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }
    private val resumeCallback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // The first permission request may never report back (seen on an emulator): look again on every resume.
            refreshShizuku()
            scheduler?.onForeground()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Called from Application.onCreate. Cheap; starts nothing while the switch is off. */
    fun init(application: Application, prefsCore: PrefsCore) {
        if (app != null) return
        app = application
        prefs = prefsCore
        client = ChaldeaSyncClient(application)
        // The settings flows are plain preference listeners: no timer, thread or Shizuku call.
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            combine(
                prefsCore.chaldeaSyncEnabled.asFlow(),
                prefsCore.captureTrackerEnabled.asFlow(),
                prefsCore.captureConsentVersion.asFlow(),
            ) { sync, tracker, consent -> sync to (tracker && CaptureConsent.agreed(consent)) }
                .distinctUntilChanged()
                .collect { (sync, _) -> captureSafely("chaldeaSettings") { apply(sync) } }
        }
    }

    private fun env(): ChaldeaSyncEnv = object : ChaldeaSyncEnv {
        override fun enabled(): Boolean {
            val p = prefs ?: return false
            return p.chaldeaSyncEnabled.get() && p.captureTrackerEnabled.get() && CaptureConsent.agreed(p.captureConsentVersion.get())
        }

        override fun closeMinutes(): Int = prefs?.chaldeaSyncCloseMinutes?.get()?.takeIf { it in listOf(0, 10, 30, 60) } ?: 30
        override fun shizukuState(): ShizukuState = _shizuku.value
        override suspend fun openSession(): ChaldeaSyncSession? = client?.open()
    }

    private fun accounts(application: Application): ChaldeaAccountSource = object : ChaldeaAccountSource {
        override fun load(userId: String): ChaldeaAccountSource.Loaded? {
            CaptureTracker.init(application)
            val server = when (CaptureTracker.serverOf(userId)) {
                "KR" -> ChaldeaServer.Kr
                "JP" -> ChaldeaServer.Jp
                else -> return null
            }
            val state = CaptureTracker.snapshot(userId)?.second ?: return null
            return ChaldeaAccountSource.Loaded(server, state)
        }

        override fun currentIds(): Map<ChaldeaServer, String> {
            CaptureTracker.init(application)
            return buildMap {
                CaptureTracker.currentIdOf("KR")?.let { put(ChaldeaServer.Kr, it) }
                CaptureTracker.currentIdOf("JP")?.let { put(ChaldeaServer.Jp, it) }
            }
        }
    }

    private fun apply(syncOn: Boolean) {
        val application = app ?: return
        synchronized(lock) {
            if (syncOn && !enabledNow) {
                enabledNow = true
                ShizukuProviderSwitch.setEnabled(application, true)
                runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
                runCatching { Shizuku.addBinderDeadListener(binderDead) }
                runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
                application.registerActivityLifecycleCallbacks(resumeCallback)
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(application) }
                refreshShizuku()
                if (scheduler == null) createScheduler(application)
            } else if (!syncOn && enabledNow) {
                enabledNow = false
                application.unregisterActivityLifecycleCallbacks(resumeCallback)
                runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
                runCatching { Shizuku.removeBinderDeadListener(binderDead) }
                runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
                ShizukuProviderSwitch.setEnabled(application, false)
            }
        }
        // Also covers log collection or consent changing while the sync switch stays on.
        scheduler?.onSettingsChanged()
    }

    private fun createScheduler(application: Application) {
        val s = ChaldeaSyncScheduler(
            scope = scope,
            env = env(),
            accounts = accounts(application),
            store = ChaldeaSyncStore(File(application.filesDir, "chaldea-sync")),
            backups = ChaldeaBackupStore(File(application.filesDir, "chaldea-backup")),
            clock = SystemClock::elapsedRealtime,
        )
        scheduler = s
        hooks = object : ChaldeaSyncHooks {
            override fun onAccountsSaved(ids: Collection<String>) = s.onAccountSaved(ids)
            override fun onAccountDeleted(id: String?) = s.onAccountDeleted(id)
            override fun onScriptStart() = s.onScriptStart()
            override fun onScriptEnd() = s.onScriptEnd()
        }
        statusJob = scope.launch { s.status.collect { _status.value = it } }
        scriptJob = scope.launch { s.scriptRunningState.collect { _scriptRunning.value = it } }
    }

    /** Re-reads the Shizuku state and wakes the scheduler. Safe to call at any time while the switch is on. */
    fun refreshShizuku() {
        val application = app ?: return
        if (!enabledNow) return
        val state = captureShizuku(application)
        val changed = _shizuku.value != state
        _shizuku.value = state
        if (changed) scheduler?.onShizukuChanged()
    }

    private fun captureShizuku(application: Application): ShizukuState = try {
        ShizukuStateReader.current(application)
    } catch (t: Throwable) {
        Timber.w("Chaldea sync: reading the Shizuku state failed: %s", t.javaClass.simpleName)
        ShizukuState.NotRunning
    }

    /** Asks Shizuku for permission when it is running and has not granted it. */
    fun requestPermissionIfNeeded() {
        if (!enabledNow) return
        refreshShizuku()
        if (_shizuku.value == ShizukuState.NeedsPermission || _shizuku.value == ShizukuState.Denied) {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        }
    }

    suspend fun syncNow(confirmed: ChaldeaRunState?): ChaldeaSyncNowOutcome =
        scheduler?.syncNow(confirmed) ?: ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.Idle)

    // ---- hooks (non-blocking; callers wrap them in captureSafely) ----

    fun onAccountsSaved(ids: Collection<String>) {
        hooks?.onAccountsSaved(ids)
    }

    fun onAccountDeleted(id: String?) {
        hooks?.onAccountDeleted(id)
    }

    fun onScriptStart() {
        hooks?.onScriptStart()
    }

    fun onScriptEnd() {
        hooks?.onScriptEnd()
    }
}
