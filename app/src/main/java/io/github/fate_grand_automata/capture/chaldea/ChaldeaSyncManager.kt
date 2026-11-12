package io.github.fate_grand_automata.capture.chaldea

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.ProcessRootShell
import io.github.fate_grand_automata.capture.captureSafely
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccess
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccessMode
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
import io.github.fate_grand_automata.scripts.capture.chaldea.ProbeTicket
import io.github.fate_grand_automata.scripts.capture.chaldea.RootProbe
import io.github.fate_grand_automata.scripts.capture.chaldea.RootProbePolicy
import io.github.fate_grand_automata.scripts.capture.chaldea.RootState
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuPermissionRequester
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * (or starting the process with it on) registers the Shizuku listeners and the resume callback and creates the
 * scheduler. The Shizuku provider itself is always enabled in the manifest (it only receives the binder, passively).
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
    private var rootClient: ChaldeaRootClient? = null

    /** Decides the root probes and keeps the in-process root state (issue #362). Created in [init]. */
    @Volatile private var rootPolicy: RootProbePolicy? = null
    @Volatile private var enabledNow = false

    /** Whether the sync is currently switched on in this process (tests and diagnostics). */
    internal val isOn: Boolean get() = enabledNow
    private val lock = Any()
    private var statusJob: Job? = null
    private var scriptJob: Job? = null
    private var resultsJob: Job? = null

    /**
     * The settings collector. flow-preferences listens through a weakly held SharedPreferences listener, so a
     * collector nobody references can be collected and runtime changes would stop arriving. Kept for the process.
     *
     * The fields are only written, never read, so R8 deletes them from a minified build and the collector is
     * collected after all (seen on the device with the beta APK). A keep rule in app/proguard-rules.pro stops R8 from removing them.
     */
    private var settingsScope: CoroutineScope? = null
    private var settingsJob: Job? = null

    private val _shizuku = MutableStateFlow(ShizukuState.NotRunning)
    val shizukuState: StateFlow<ShizukuState> = _shizuku.asStateFlow()
    private val _access = MutableStateFlow(ChaldeaAccess(RootState.Unchecked, ShizukuState.NotRunning))

    /** Root and Shizuku together: what the settings screen and the scheduler read. */
    val access: StateFlow<ChaldeaAccess> = _access.asStateFlow()

    /** Runs su -c id; replaced in tests. Blocking: only call it off the main thread. */
    internal var probeRunner: () -> RootState = {
        try {
            val out = ProcessRootShell().run(listOf("su", "-c", "id"), "", 5)
            RootProbe.parse(out.exitCode, out.lines, out.timedOut)
        } catch (t: Throwable) {
            // No su binary: the process cannot even start.
            RootState.Unavailable
        }
    }
    private val probeScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val _status = MutableStateFlow<ChaldeaSyncStatus>(ChaldeaSyncStatus.Idle)
    val status: StateFlow<ChaldeaSyncStatus> = _status.asStateFlow()
    private val _results = MutableStateFlow<Map<ChaldeaServer, ChaldeaSyncStatus>>(emptyMap())

    /** The last result of each server (KR / JP). */
    val serverResults: StateFlow<Map<ChaldeaServer, ChaldeaSyncStatus>> = _results.asStateFlow()
    private val permission = ShizukuPermissionRequester()
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
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        synchronized(lock) { permission.released() }
        refreshShizuku()
    }
    private val resumeCallback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // The first permission request may never report back (seen on an emulator): look again on every resume.
            synchronized(lock) { permission.released() }
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

    /** Tests only: forgets everything [init] set up so the next test can start from a clean process state. */
    internal fun resetForTest() {
        settingsJob?.cancel()
        settingsJob = null
        settingsScope = null
        app = null
        prefs = null
        client = null
        rootClient = null
        rootPolicy = null
        _access.value = ChaldeaAccess(RootState.Unchecked, ShizukuState.NotRunning)
        enabledNow = false
        scheduler = null
        hooks = null
    }

    /** Called from Application.onCreate. Cheap; starts nothing while the switch is off. */
    fun init(application: Application, prefsCore: PrefsCore) {
        if (app != null) return
        app = application
        prefs = prefsCore
        client = ChaldeaSyncClient(application)
        rootClient = ChaldeaRootClient(application)
        rootPolicy = RootProbePolicy(
            storedFlag = { prefsCore.chaldeaSyncRootGranted.get() },
            setStoredFlag = { prefsCore.chaldeaSyncRootGranted.set(it) },
        )
        // Undo the runtime "disabled" state an earlier beta left on the provider. No Shizuku API is involved.
        ShizukuProviderReset.resetToDefault(application)
        // The settings flows are plain preference listeners: no timer, thread or Shizuku call.
        val settings = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        settingsScope = settings
        settingsJob = settings.launch {
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
        override fun access(): ChaldeaAccess {
            // The next round after a failed root bind probes again, silently (never while a script runs).
            startProbe(rootPolicy?.onRound())
            return currentAccess()
        }

        override suspend fun openSession(): ChaldeaSyncSession? = when (currentAccess().mode) {
            ChaldeaAccessMode.Root -> rootClient?.open() ?: run {
                // Forget the root state: the next round probes again and, without root, falls back to Shizuku.
                rootPolicy?.onRootBindFailed()
                publishAccess()
                null
            }
            ChaldeaAccessMode.Shizuku -> client?.open()
            null -> null
        }
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
        // Only the flags are changed under the lock. The package-manager and Shizuku binder calls below run outside
        // it, so the main thread (the resume callback takes the same lock) never waits on a binder call.
        val change = synchronized(lock) {
            when {
                syncOn && !enabledNow -> {
                    enabledNow = true
                    1
                }
                !syncOn && enabledNow -> {
                    enabledNow = false
                    permission.disarm()
                    2
                }
                else -> 0
            }
        }
        if (change == 1) {
            // Silent, and only when root was granted before; waits for a running script to end.
            startProbe(rootPolicy?.onSettingsOn())
            runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceived) }
            runCatching { Shizuku.addBinderDeadListener(binderDead) }
            runCatching { Shizuku.addRequestPermissionResultListener(permissionResult) }
            application.registerActivityLifecycleCallbacks(resumeCallback)
            runCatching { ShizukuProvider.requestBinderForNonProviderProcess(application) }
            refreshShizuku()
            synchronized(lock) { if (scheduler == null) createScheduler(application) }
        } else if (change == 2) {
            rootPolicy?.onSwitchedOff()
            publishAccess()
            probeScope.launch { ChaldeaRootClient.closeShell() }
            application.unregisterActivityLifecycleCallbacks(resumeCallback)
            runCatching { Shizuku.removeBinderReceivedListener(binderReceived) }
            runCatching { Shizuku.removeBinderDeadListener(binderDead) }
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResult) }
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
        resultsJob = scope.launch { s.serverResults.collect { _results.value = it } }
    }

    /** Re-reads the Shizuku state and wakes the scheduler. Safe to call at any time while the switch is on. */
    fun refreshShizuku() {
        val application = app ?: return
        if (!enabledNow) return
        val state = captureShizuku(application)
        val changed = _shizuku.value != state
        _shizuku.value = state
        publishAccess()
        if (changed) scheduler?.onAccessChanged()
        requestPermissionIfWanted()
    }

    /** Shows the Shizuku permission dialog when the user asked for the sync and Shizuku needs it. */
    private fun requestPermissionIfWanted() {
        // Not while root is Granted, nor while a root probe may still make the dialog unnecessary.
        val policy = rootPolicy
        val ask = synchronized(lock) {
            if (policy != null) policy.shouldRequestShizukuPermission(permission, _shizuku.value) else permission.shouldRequest(_shizuku.value)
        }
        if (ask) runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    private fun captureShizuku(application: Application): ShizukuState = try {
        ShizukuStateReader.current(application)
    } catch (t: Throwable) {
        Timber.w("Chaldea sync: reading the Shizuku state failed: %s", t.javaClass.simpleName)
        ShizukuState.NotRunning
    }

    /**
     * The user turned the switch on. The preference flow may already have run [apply] or may not have yet, so the
     * wish is recorded first and acted on from both places (see [ShizukuPermissionRequester]).
     */
    fun onUserTurnedOn() {
        synchronized(lock) { permission.arm() }
        // The Magisk / KernelSU dialog may appear; the Shizuku dialog waits for this probe.
        startProbe(rootPolicy?.onUserTurnedOn())
        if (enabledNow) refreshShizuku()
    }

    suspend fun syncNow(confirmed: ChaldeaRunState?): ChaldeaSyncNowOutcome {
        // Root is not Granted yet in this process: ask now (interactive), and use the answer for this sync.
        rootPolicy?.onSyncNow()?.let { ticket ->
            finishProbe(ticket, withContext(Dispatchers.IO) { probeRunner() })
        }
        return scheduler?.syncNow(confirmed) ?: ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.Idle)
    }

    private fun currentAccess() = ChaldeaAccess(rootPolicy?.state ?: RootState.Unchecked, _shizuku.value)

    private fun publishAccess() {
        _access.value = currentAccess()
    }

    /** Runs the probe the policy allowed, on a background thread. */
    private fun startProbe(ticket: ProbeTicket?) {
        if (ticket == null) return
        probeScope.launch { captureSafely("chaldeaRootProbe") { finishProbe(ticket, probeRunner()) } }
    }

    private fun finishProbe(ticket: ProbeTicket, result: RootState) {
        val policy = rootPolicy ?: return
        val changed = policy.onProbeResult(ticket, result)
        publishAccess()
        if (changed) scheduler?.onAccessChanged()
        // The Shizuku dialog was held back while the probe ran.
        if (result != RootState.Granted) refreshShizuku()
    }

    // ---- hooks (non-blocking; callers wrap them in captureSafely) ----

    fun onAccountsSaved(ids: Collection<String>) {
        hooks?.onAccountsSaved(ids)
    }

    fun onAccountDeleted(id: String?) {
        hooks?.onAccountDeleted(id)
    }

    fun onScriptStart() {
        rootPolicy?.onScriptStart()
        hooks?.onScriptStart()
    }

    fun onScriptEnd() {
        // A root probe that waited for the script starts now, off this thread.
        startProbe(rootPolicy?.onScriptEnd())
        hooks?.onScriptEnd()
    }
}
