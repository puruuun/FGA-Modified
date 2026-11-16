package io.github.fate_grand_automata.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.main.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Local proxy service supporting both AdGuard mode (resident listener on 127.0.0.1:<port>)
 * and Root mode (per-app transparent TLS redirect listening only while reading is active).
 */
@RequiresApi(Build.VERSION_CODES.Q)
@AndroidEntryPoint
class CaptureProxyService : Service() {
    @Inject lateinit var prefs: PrefsCore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Written on the root serial executor and read on the main thread (and the other way round): volatile.
    @Volatile
    private var proxy: ProxyServer? = null

    @Volatile
    private var boundPort: Int? = null

    // In root mode the guard is taken and released only on the root serial executor (also from onDestroy), so an
    // async release from an old listener cannot release the guard of the new one (G5). AdGuard mode stays on the
    // main thread.
    private val ownsSaveGuard = AtomicBoolean(false)
    private var readingActive = false
    private var shuttingDown = false
    private var rootShuttingDown = false
    private var startupDisableSent = false
    private var ensureCleanupRanThisStart = false
    private var activeServiceMode: CaptureConnectionMode? = null
    private var pendingRootStopReason: CaptureStopReason = CaptureStopReason.User

    @Volatile
    private var lastRootListenPortConflict: Boolean = false

    private val rootAutoRetreat = RootAutoRetreat()
    private val retreatTriggered = AtomicBoolean(false)
    private lateinit var rootFlag: RootRulesActiveFlag
    private lateinit var rootRunner: RootCaptureRunner

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private var networkDebounceJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleDebouncedStatusRefresh()
        }

        override fun onLost(network: Network) {
            scheduleDebouncedStatusRefresh()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
        instanceGate.claim(this)
        startupDisableSent = false
        ensureCleanupRanThisStart = false
        CaptureController.init(applicationContext)
        CaptureTracker.init(applicationContext)
        // G1: in root mode the status left by the previous run (error, port error, counters) stays visible.
        if (prefs.captureConnectionMode.get() != CaptureConnectionMode.Root) {
            CaptureProxyStatus.reset()
        }
        rootFlag = RootRulesActiveFlag.from(applicationContext)
        rootRunner = createRootRunner()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        observeStartConditions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val mode = prefs.captureConnectionMode.get()
        if (activeServiceMode == null) {
            activeServiceMode = mode
        }

        startForegroundNotification()

        if (action == ACTION_SHUTDOWN) {
            shutdownAndStopSelf(CaptureStopReason.User)
            return START_NOT_STICKY
        }

        if (mode == CaptureConnectionMode.Root) {
            return handleRootStartCommand(action, intent)
        }

        if (rootShuttingDown || CaptureProxyStatus.state.value.rootCleanupRetrying) {
            updateNotification()
            return START_NOT_STICKY
        }

        if (!CaptureController.shouldEnsureProxy(prefs)) {
            shutdownAndStopSelf(CaptureStopReason.User)
            return START_NOT_STICKY
        }

        activeServiceMode = CaptureConnectionMode.AdGuard
        if (!ensureCleanupRanThisStart) {
            ensureCleanupRanThisStart = true
            rootRunner.cleanupIfFlagSet()
        }

        val targetPort = prefs.captureProxyPort.get()
        when (action) {
            null -> {
                // Service restarted by START_STICKY: rebind the listener with reading left off.
                turnReadingOff(CaptureStopReason.User)
                dispatchAdGuardSwitch(AdGuardSwitchEvent.ServiceStartEnsureOrSticky, targetPort)
                ensureListening(targetPort)
            }

            ACTION_ENSURE -> {
                dispatchAdGuardSwitch(AdGuardSwitchEvent.ServiceStartEnsureOrSticky, targetPort)
                ensureListening(targetPort)
            }

            ACTION_START_READING -> {
                val listening = ensureListening(targetPort)
                if (listening) {
                    if (!(readingActive && CaptureTracker.running.value)) {
                        dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOn, boundPort ?: targetPort)
                    }
                    turnReadingOn()
                } else {
                    CaptureTracker.starting = false
                    showPortErrorToast(targetPort)
                }
            }

            ACTION_STOP_READING -> {
                val reason = parseStopReason(intent)
                dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOff, targetPort)
                turnReadingOff(reason)
                ensureListening(targetPort)
            }

            ACTION_TOGGLE_READING -> {
                if (readingActive || CaptureTracker.running.value) {
                    dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOff, targetPort)
                    turnReadingOff(CaptureStopReason.User)
                    ensureListening(targetPort)
                } else {
                    val listening = ensureListening(targetPort)
                    if (listening) {
                        dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOn, boundPort ?: targetPort)
                        turnReadingOn()
                    } else {
                        CaptureTracker.starting = false
                        showPortErrorToast(targetPort)
                    }
                }
            }

            ACTION_APPLY_PORT -> {
                applyPortChange(targetPort)
            }

            else -> {
                ensureListening(targetPort)
            }
        }

        updateNotification()
        return START_STICKY
    }

    private fun handleRootStartCommand(action: String?, intent: Intent?): Int {
        val canUseRootMode = CaptureController.isSupported &&
            prefs.captureTrackerEnabled.get() &&
            CaptureConsent.agreed(prefs.captureConsentVersion.get()) &&
            prefs.captureConnectionMode.get() == CaptureConnectionMode.Root

        if (!canUseRootMode) {
            shutdownAndStopSelf(CaptureStopReason.User)
            return START_NOT_STICKY
        }

        activeServiceMode = CaptureConnectionMode.Root
        val targetPort = prefs.captureProxyPort.get()

        when (action) {
            null, ACTION_ENSURE -> {
                // R2 & B8: ENSURE or null intent in root mode does NOT listen; only runs R5 cleanup once.
                if (!ensureCleanupRanThisStart) {
                    ensureCleanupRanThisStart = true
                    if (!readingActive && !rootRunner.isReadingWanted()) {
                        rootRunner.cleanupIfFlagSet {
                            mainHandler.post {
                                if (!readingActive && !rootRunner.isReadingWanted() &&
                                    !CaptureProxyStatus.state.value.rootCleanupRetrying
                                ) {
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        }
                        return START_NOT_STICKY
                    }
                }
                if (!readingActive && !rootRunner.isReadingWanted() &&
                    !CaptureProxyStatus.state.value.rootCleanupRetrying
                ) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START_READING -> {
                startRootReading(targetPort)
            }

            ACTION_STOP_READING -> {
                pendingRootStopReason = parseStopReason(intent)
                stopRootReading()
            }

            ACTION_TOGGLE_READING -> {
                if (readingActive || rootRunner.isReadingWanted() || CaptureTracker.running.value) {
                    pendingRootStopReason = CaptureStopReason.User
                    stopRootReading()
                } else {
                    startRootReading(targetPort)
                }
            }

            ACTION_APPLY_PORT -> {
                if (readingActive || rootRunner.isReadingWanted()) {
                    rootRunner.requestPortChange(targetPort)
                } else if (!CaptureProxyStatus.state.value.rootCleanupRetrying) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            else -> {
                if (!readingActive && !rootRunner.isReadingWanted() &&
                    !CaptureProxyStatus.state.value.rootCleanupRetrying
                ) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        updateNotification()
        return START_NOT_STICKY
    }

    private fun startRootReading(targetPort: Int) {
        if (readingActive && CaptureTracker.running.value) return
        rootShuttingDown = false
        retreatTriggered.set(false)
        rootAutoRetreat.reset()
        CaptureProxyStatus.resetNonGameUpstreamFailures()
        CaptureProxyStatus.setRootError(null)

        JpCaptureStart.start(
            jpScope,
            captureFeaturesAvailable(prefs.captureTrackerEnabled.get(), Build.VERSION.SDK_INT),
            prefs.captureJpRoot.get(),
            RootCaInjector(applicationContext),
        ) { message ->
            mainHandler.post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }

        rootRunner.requestStartReading(targetPort)
    }

    private fun stopRootReading() {
        unregisterNetworkCallback()
        rootRunner.requestStopReading()
        updateNotification()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // In AdGuard mode, AdGuard keeps pointing at FGA even after swiping FGA away from Recents; stay alive.
        // In Root mode, the root watchdog cleans up if the process dies; if only the task is removed, keep reading.
    }

    override fun onDestroy() {
        val ranAsRoot = (activeServiceMode ?: prefs.captureConnectionMode.get()) == CaptureConnectionMode.Root ||
            rootShuttingDown
        // H5: a newer service instance owns the global status and the tracker; an old one leaves them alone.
        val wasLive = instanceGate.isLive(this)
        shuttingDown = true
        unregisterNetworkCallback()
        serviceScope.cancel()
        jpScope.cancel()
        // B6: onDestroy starts cleanup on the serial executor and waits at most 2 s.
        rootRunner.onDestroyCleanup(maxWaitMs = 2_000L)
        if (ranAsRoot) {
            // G5/H8: the guard of a root listener is only released on the serial executor.
            ROOT_SERIAL_EXECUTOR.execute { releaseSaveGuard() }
        }
        if (wasLive) turnReadingOff(CaptureStopReason.User, releaseGuard = !ranAsRoot)
        stopCurrentProxy()
        if (wasLive) {
            // G1: root mode keeps the last error, port error and counters after the service stops.
            CaptureProxyStatus.onServiceDestroyed(ranAsRoot)
        }
        instanceGate.release(this)
        try {
            super.onDestroy()
        } finally {
            alive = false
        }
    }

    private fun observeStartConditions() {
        serviceScope.launch {
            combine(
                prefs.captureTrackerEnabled.asFlow(),
                prefs.captureConsentVersion.asFlow(),
                prefs.captureConnectionMode.asFlow(),
            ) { enabled, consentVersion, mode ->
                Triple(enabled, CaptureConsent.agreed(consentVersion), mode)
            }.collect { (enabled, agreed, mode) ->
                if (shuttingDown) return@collect
                val previousMode = activeServiceMode
                if (!CaptureController.isSupported || !enabled || !agreed || mode == CaptureConnectionMode.Vpn) {
                    shutdownAndStopSelf(CaptureStopReason.User)
                    return@collect
                }
                if (previousMode != null && previousMode != mode) {
                    shutdownAndStopSelf(CaptureStopReason.User)
                }
            }
        }
    }

    private fun createRootRunner(): RootCaptureRunner = RootCaptureRunner(
        rootShell = ProcessRootShell(),
        flag = rootFlag,
        fgaUidProvider = { Process.myUid() },
        targetUidsProvider = { resolveRootTargetUids(applicationContext, prefs) },
        proxyController = object : RootCaptureRunner.ProxyController {
            override fun startListening(preferredPort: Int): RootCaptureRunner.ListenerInfo? =
                startRootProxyListener(preferredPort)

            override fun isLastListenErrorPortConflict(): Boolean =
                lastRootListenPortConflict

            override fun isListenerAlive(): Boolean = proxy?.isAccepting == true

            override fun clearContexts() {
                runCatching { proxy?.setContexts(emptyMap()) }
            }

            override fun stopAccepting() {
                runCatching { proxy?.stopAccepting() }
            }

            override fun awaitOpenConnections(timeoutMs: Long): Boolean =
                proxy?.awaitOpenConnections(timeoutMs) ?: true

            override fun closeProxy() {
                mainHandler.post { unregisterNetworkCallback() }
                // Called on the root serial executor, the same thread that took the guard.
                releaseSaveGuard()
                stopCurrentProxy()
            }
        },
        callbacks = object : RootCaptureRunner.Callbacks {
            override fun onApplied(
                port: Int,
                ipv4RuleCount: Int,
                ipv6RuleCount: Int,
                ipv6Unsupported: Boolean,
            ) {
                postLive {
                    rootShuttingDown = false
                    readingActive = true
                    CaptureProxyStatus.setRootApplied(
                        port = port,
                        ipv4Rules = ipv4RuleCount,
                        ipv6Rules = ipv6RuleCount,
                        ipv6Unsupported = ipv6Unsupported,
                        watchdogActive = true,
                        resetGameConnections = true,
                    )
                    CaptureTracker.setRunning(true)
                    registerNetworkCallback()
                    updateNotification()
                }
            }

            override fun onApplyFailed(
                errorMessage: String,
                toastMessage: String,
                cleanupRetrying: Boolean,
            ) {
                postLive {
                    readingActive = false
                    unregisterNetworkCallback()
                    CaptureTracker.starting = false
                    if (CaptureTracker.running.value) {
                        CaptureTracker.setRunning(false, CaptureStopReason.Error)
                    }
                    val localizedError = when (errorMessage) {
                        RootCaptureRunner.ERROR_NAT_UNSUPPORTED -> getString(R.string.capture_root_nat_unsupported)
                        RootCaptureRunner.ERROR_START_FAILED -> getString(R.string.capture_start_failed)
                        else -> errorMessage
                    }
                    val localizedToast = when (toastMessage) {
                        RootCaptureRunner.TOAST_APPLY_FAILED -> getString(R.string.capture_root_apply_failed_toast)
                        RootCaptureRunner.TOAST_START_FAILED -> getString(R.string.capture_start_failed)
                        else -> toastMessage
                    }
                    if (cleanupRetrying) {
                        CaptureProxyStatus.setRootCleanupRetrying(
                            true,
                            getString(R.string.capture_root_cleanup_retry_warning),
                        )
                    } else {
                        CaptureProxyStatus.setRootCleanedUp(preserveError = false)
                        CaptureProxyStatus.setRootError(localizedError)
                    }
                    if (localizedToast.isNotEmpty()) {
                        Toast.makeText(applicationContext, localizedToast, Toast.LENGTH_LONG).show()
                    }
                    updateNotification()
                    if (!cleanupRetrying &&
                        !CaptureProxyStatus.state.value.rootCleanupRetrying &&
                        (activeServiceMode == CaptureConnectionMode.Root || rootShuttingDown)
                    ) {
                        rootShuttingDown = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            override fun onRulesRemoved() {
                postLive {
                    // G2: reading is off as soon as the rules are gone; the listener only drains.
                    readingActive = false
                    unregisterNetworkCallback()
                    if (CaptureTracker.running.value || CaptureTracker.starting) {
                        CaptureTracker.setRunning(false, pendingRootStopReason)
                    }
                    CaptureProxyStatus.setRootRulesRemoved()
                    updateNotification()
                }
            }

            override fun onCleanupSucceeded(generation: Long) {
                postLive {
                    // H2: a completion from a stop that a newer start replaced must not stop the service.
                    if (!RootCaptureRunner.shouldStopServiceAfterCleanup(
                            generation,
                            rootRunner.lastStartGeneration(),
                            rootRunner.isReadingWanted(),
                        )
                    ) {
                        updateNotification()
                        return@postLive
                    }
                    val wasRootShuttingDown = rootShuttingDown
                    rootShuttingDown = false
                    readingActive = false
                    unregisterNetworkCallback()
                    if (CaptureTracker.running.value || CaptureTracker.starting) {
                        CaptureTracker.setRunning(false, pendingRootStopReason)
                    }
                    if (activeServiceMode != CaptureConnectionMode.Root) {
                        CaptureProxyStatus.reset()
                    } else {
                        // G1: keep the last error and counters for the status card.
                        CaptureProxyStatus.setRootCleanedUp(preserveError = true)
                    }
                    updateNotification()
                    if (activeServiceMode == CaptureConnectionMode.Root || wasRootShuttingDown || shuttingDown) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            override fun onCleanupRetryScheduled(message: String) {
                postLive {
                    readingActive = false
                    if (CaptureTracker.running.value || CaptureTracker.starting) {
                        CaptureTracker.setRunning(false, pendingRootStopReason)
                    }
                    CaptureProxyStatus.setRootCleanupRetrying(
                        true,
                        getString(R.string.capture_root_cleanup_retry_warning),
                    )
                    updateNotification()
                }
            }

            override fun onPortError(port: Int) {
                postLive {
                    readingActive = false
                    unregisterNetworkCallback()
                    CaptureTracker.starting = false
                    if (CaptureTracker.running.value) {
                        CaptureTracker.setRunning(false, CaptureStopReason.Error)
                    }
                    CaptureProxyStatus.setPortError(port)
                    showPortErrorToast(port)
                    if (activeServiceMode == CaptureConnectionMode.Root) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        },
        executor = ROOT_SERIAL_EXECUTOR,
    )

    private fun startRootProxyListener(preferredPort: Int): RootCaptureRunner.ListenerInfo? {
        lastRootListenPortConflict = false
        var acquiredHere = false
        return try {
            if (!ownsSaveGuard.get()) {
                if (!CertificateSaveGuard.beginVpn()) {
                    Timber.w("Capture: certificate save in progress; cannot start root proxy")
                    return null
                }
                ownsSaveGuard.set(true)
                acquiredHere = true
            }
            val jpEnabled = prefs.captureJpRoot.get()
            val contexts = captureServerContexts(
                if (CertStore.exists(this)) CertStore.serverContext(this) else null,
                if (jpEnabled && CertStore.existsJp(this)) CertStore.jpServerContext(this) else null,
                jpEnabled,
            )
            if (contexts.isEmpty()) {
                if (acquiredHere) releaseSaveGuard()
                return null
            }
            val created = ProxyServer(
                sslContexts = contexts,
                options = ROOT_PROXY_OPTIONS,
                onGameHostConnected = { ts -> CaptureProxyStatus.recordGameHostConnected(ts) },
                onUpstreamFailure = { host ->
                    CaptureProxyStatus.incrementUpstreamFailure()
                    handleRootGameUpstreamFailure(host)
                },
                onTlsFailure = {
                    CaptureTracker.addTlsFailure()
                    handleRootGameTlsFailure()
                },
                onGameTlsSuccess = {
                    rootAutoRetreat.recordGameTlsSuccess()
                },
                onGameUpstreamSuccess = {
                    rootAutoRetreat.recordGameUpstreamSuccess()
                },
                onUnexpectedConnection = {
                    CaptureProxyStatus.incrementUnexpectedConnection()
                },
                onNonGameUpstreamFailure = { _ ->
                    rootAutoRetreat.recordNonGameUpstreamFailure()
                    CaptureProxyStatus.incrementNonGameUpstreamFailure()
                },
            )
            val actualPort = try {
                created.start(preferredPort = preferredPort, allowFallback = false)
            } catch (t: Throwable) {
                lastRootListenPortConflict = true
                runCatching { created.stop() }
                throw t
            }
            val oldProxy = proxy
            proxy = created
            boundPort = actualPort
            runCatching { oldProxy?.stop() }
            RootCaptureRunner.ListenerInfo(
                port = actualPort,
                ipv6Listening = created.isIpv6Listening,
            )
        } catch (t: Throwable) {
            Timber.w(t, "Capture: failed to start root transparent proxy on port %d", preferredPort)
            if (acquiredHere) releaseSaveGuard()
            null
        }
    }

    private fun handleRootGameTlsFailure() {
        val reason = rootAutoRetreat.recordGameTlsFailure() ?: return
        triggerRootAutoRetreat(reason)
    }

    private fun handleRootGameUpstreamFailure(host: String) {
        val reason = rootAutoRetreat.recordGameUpstreamFailure() ?: return
        triggerRootAutoRetreat(reason)
    }

    private fun triggerRootAutoRetreat(reason: RootAutoRetreat.Reason) {
        if (!retreatTriggered.compareAndSet(false, true)) return
        val reasonRes = when (reason) {
            RootAutoRetreat.Reason.TlsHandshakeFailures -> R.string.capture_root_retreat_reason_tls
            RootAutoRetreat.Reason.UpstreamFailures -> R.string.capture_root_retreat_reason_upstream
        }
        val message = getString(R.string.capture_root_auto_retreat_message, getString(reasonRes))
        postLive {
            pendingRootStopReason = CaptureStopReason.Error
            CaptureProxyStatus.setRootError(message)
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            postRootRetreatNotification(message)
            stopRootReading()
        }
    }

    private fun postRootRetreatNotification(message: String) {
        ensureNotificationChannel()
        val open = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_key)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RETREAT_NOTIFICATION_ID, n)
    }

    /** H5: runs on the main thread only while this is still the live service instance. */
    private fun postLive(block: () -> Unit) {
        mainHandler.post {
            if (instanceGate.isLive(this)) block()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        networkDebounceJob?.cancel()
        networkDebounceJob = null
        if (!networkCallbackRegistered) return
        val cm = connectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    private fun scheduleDebouncedStatusRefresh() {
        networkDebounceJob?.cancel()
        networkDebounceJob = serviceScope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            CaptureProxyStatus.touchRefresh()
            updateNotification()
        }
    }

    private fun ensureListening(port: Int): Boolean {
        if (proxy != null && boundPort == port && CaptureProxyStatus.state.value.state is CaptureProxyState.Listening) {
            return true
        }
        return bindProxy(port)
    }

    private fun applyPortChange(newPort: Int) {
        if (proxy != null && boundPort == newPort && CaptureProxyStatus.state.value.state is CaptureProxyState.Listening) {
            return
        }
        if (readingActive) {
            dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOff, newPort)
            turnReadingOff(CaptureStopReason.User)
        }
        val oldProxy = proxy
        val created = createConfiguredProxy()
        try {
            val actualPort = created.start(preferredPort = newPort, allowFallback = false)
            proxy = created
            boundPort = actualPort
            runCatching { oldProxy?.stop() }
            CaptureProxyStatus.setListening(actualPort, reading = false)
            Timber.i("Capture: proxy switched to 127.0.0.1:%d", actualPort)
        } catch (t: Throwable) {
            Timber.w(t, "Capture: failed to bind new proxy port %d", newPort)
            runCatching { created.stop() }
            runCatching { oldProxy?.stop() }
            proxy = null
            boundPort = null
            CaptureProxyStatus.setPortError(newPort)
            showPortErrorToast(newPort)
        }
    }

    private fun bindProxy(port: Int): Boolean {
        if (readingActive) {
            turnReadingOff(CaptureStopReason.User)
        }
        val oldProxy = proxy
        val created = createConfiguredProxy()
        return try {
            val actualPort = created.start(preferredPort = port, allowFallback = false)
            proxy = created
            boundPort = actualPort
            runCatching { oldProxy?.stop() }
            CaptureProxyStatus.setListening(actualPort, reading = false)
            Timber.i("Capture: proxy listening on 127.0.0.1:%d", actualPort)
            true
        } catch (t: Throwable) {
            Timber.w(t, "Capture: failed to bind proxy port %d", port)
            runCatching { created.stop() }
            runCatching { oldProxy?.stop() }
            proxy = null
            boundPort = null
            CaptureProxyStatus.setPortError(port)
            false
        }
    }

    private fun createConfiguredProxy(): ProxyServer = ProxyServer(
        sslContexts = emptyMap(),
        options = PROXY_OPTIONS,
        onGameHostConnected = { ts -> CaptureProxyStatus.recordGameHostConnected(ts) },
        onUpstreamFailure = { _ -> CaptureProxyStatus.incrementUpstreamFailure() },
    )

    private fun turnReadingOn() {
        if (readingActive && CaptureTracker.running.value) return
        val activeProxy = proxy
        val port = boundPort
        if (activeProxy == null || port == null || CaptureProxyStatus.state.value.state !is CaptureProxyState.Listening) {
            CaptureTracker.starting = false
            return
        }

        JpCaptureStart.start(
            jpScope,
            captureFeaturesAvailable(prefs.captureTrackerEnabled.get(), Build.VERSION.SDK_INT),
            prefs.captureJpRoot.get(),
            RootCaInjector(applicationContext),
        ) { message ->
            mainHandler.post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }

        try {
            check(CertificateSaveGuard.beginVpn()) { "Certificate save in progress" }
            ownsSaveGuard.set(true)
            val jpEnabled = prefs.captureJpRoot.get()
            val contexts = captureServerContexts(
                if (CertStore.exists(this)) CertStore.serverContext(this) else null,
                if (jpEnabled && CertStore.existsJp(this)) CertStore.jpServerContext(this) else null,
                jpEnabled,
            )
            if (contexts.isEmpty()) {
                Timber.w("Capture: no certificate context available for AdGuard proxy")
                dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOff, boundPort ?: port)
                turnReadingOff(CaptureStopReason.Error)
                mainHandler.post {
                    Toast.makeText(applicationContext, R.string.capture_main_no_certificate, Toast.LENGTH_SHORT).show()
                }
                return
            }
            activeProxy.setContexts(contexts)
            readingActive = true
            CaptureProxyStatus.setListening(port, reading = true)
            CaptureTracker.setRunning(true)
            Timber.i("Capture: AdGuard proxy reading started on 127.0.0.1:%d", port)
        } catch (t: Throwable) {
            Timber.w(t, "Capture: failed to turn proxy reading on")
            dispatchAdGuardSwitch(AdGuardSwitchEvent.ReadingOff, boundPort ?: port)
            turnReadingOff(CaptureStopReason.Error)
            mainHandler.post {
                Toast.makeText(applicationContext, R.string.capture_start_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun turnReadingOff(reason: CaptureStopReason, releaseGuard: Boolean = true) {
        proxy?.setContexts(emptyMap())
        readingActive = false
        if (releaseGuard) releaseSaveGuard()
        if (CaptureTracker.running.value || CaptureTracker.starting) {
            CaptureTracker.setRunning(false, reason)
        }
        CaptureProxyStatus.setReading(false)
    }

    private fun releaseSaveGuard() {
        if (ownsSaveGuard.compareAndSet(true, false)) {
            CertificateSaveGuard.endVpn()
        }
    }

    private fun stopCurrentProxy() {
        runCatching { proxy?.stop() }
        proxy = null
        boundPort = null
    }

    private fun shutdownAndStopSelf(reason: CaptureStopReason) {
        val isRootPath = activeServiceMode == CaptureConnectionMode.Root ||
            rootShuttingDown ||
            rootFlag.isSet() ||
            rootRunner.isReadingWanted() ||
            CaptureProxyStatus.state.value.rootRulesActive ||
            CaptureProxyStatus.state.value.rootCleanupRetrying
        if (isRootPath) {
            pendingRootStopReason = reason
            unregisterNetworkCallback()
            readingActive = false
            if (CaptureTracker.running.value || CaptureTracker.starting) {
                CaptureTracker.setRunning(false, reason)
            }
            CaptureProxyStatus.setReading(false)
            if (rootRunner.isIdle()) {
                // Nothing left to take away (no listener, rules, flag or retry): the service can go at once.
                rootShuttingDown = false
                if (activeServiceMode == CaptureConnectionMode.Root) {
                    CaptureProxyStatus.setRootCleanedUp(preserveError = true)
                } else {
                    CaptureProxyStatus.reset()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            if (!rootShuttingDown) {
                rootShuttingDown = true
                rootRunner.requestShutdown()
            }
            updateNotification()
            return
        }
        if (shuttingDown) return
        shuttingDown = true
        unregisterNetworkCallback()
        dispatchAdGuardSwitch(AdGuardSwitchEvent.LeavingOrShutdown)
        turnReadingOff(reason)
        stopCurrentProxy()
        CaptureProxyStatus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun dispatchAdGuardSwitch(
        event: AdGuardSwitchEvent,
        port: Int = prefs.captureProxyPort.get(),
    ) {
        if (prefs.captureConnectionMode.get() != CaptureConnectionMode.AdGuard) return
        val password = prefs.captureAdGuardAutomationPassword.get()
        val effective = AdGuardAutomation.isAutoSwitchEffective(
            enabled = prefs.captureAdGuardAutoSwitch.get(),
            password = password,
        )
        val commands = AdGuardAutomation.commandsFor(
            event = event,
            autoSwitchEffective = effective,
            readingActive = readingActive || CaptureTracker.running.value,
            startupDisableSent = startupDisableSent,
            port = port,
        )
        if (event == AdGuardSwitchEvent.ServiceStartEnsureOrSticky && commands.isNotEmpty()) {
            startupDisableSent = true
        }
        if (commands.isNotEmpty()) {
            AdGuardAutomation.sendCommands(applicationContext, commands, password)
        }
    }

    private fun parseStopReason(intent: Intent?): CaptureStopReason =
        intent?.getStringExtra(EXTRA_STOP_REASON)
            ?.let { name -> CaptureStopReason.entries.firstOrNull { it.name == name } }
            ?: CaptureStopReason.User

    private fun showPortErrorToast(port: Int) {
        val msg = getString(R.string.capture_proxy_port_in_use, port)
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun startForegroundNotification() {
        ensureNotificationChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        if (shuttingDown) return
        ensureNotificationChannel()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.capture_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this,
            REQUEST_CODE_TOGGLE,
            Intent(this, CaptureProxyService::class.java).setAction(ACTION_TOGGLE_READING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snap = CaptureProxyStatus.state.value
        val displayPort = when (val st = snap.state) {
            is CaptureProxyState.Listening -> st.port
            is CaptureProxyState.PortError -> st.port
            CaptureProxyState.Idle -> prefs.captureProxyPort.get()
        }
        val isRootMode = (activeServiceMode ?: prefs.captureConnectionMode.get()) == CaptureConnectionMode.Root ||
            rootShuttingDown ||
            snap.rootRulesActive ||
            snap.rootCleanupRetrying
        val text = if (isRootMode) {
            when {
                snap.rootCleanupRetrying -> getString(R.string.capture_root_notification_cleanup)
                snap.state is CaptureProxyState.PortError -> getString(R.string.capture_root_notification_port_error, displayPort)
                snap.reading -> getString(R.string.capture_root_notification_collecting, displayPort)
                else -> getString(R.string.capture_root_notification_cleanup)
            }
        } else {
            when {
                snap.state is CaptureProxyState.PortError -> getString(R.string.capture_proxy_notification_port_error, displayPort)
                snap.reading -> getString(R.string.capture_proxy_notification_collecting, displayPort)
                else -> getString(R.string.capture_proxy_notification_waiting, displayPort)
            }
        }
        val actionTitle = getString(
            if (snap.reading) R.string.capture_proxy_notification_action_stop
            else R.string.capture_proxy_notification_action_start
        )

        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_key)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, actionTitle, toggle).build())
            .setOngoing(true)
        if (snap.rootCleanupRetrying) {
            builder.setStyle(
                Notification.BigTextStyle().bigText(
                    "$text\n${getString(R.string.capture_root_cleanup_retry_warning)}"
                )
            )
        }
        return builder.build()
    }

    companion object {
        @Volatile
        var alive: Boolean = false
            private set

        const val ACTION_ENSURE = "io.github.fate_grand_automata.capture.proxy.ENSURE"
        const val ACTION_START_READING = "io.github.fate_grand_automata.capture.proxy.START_READING"
        const val ACTION_STOP_READING = "io.github.fate_grand_automata.capture.proxy.STOP_READING"
        const val ACTION_APPLY_PORT = "io.github.fate_grand_automata.capture.proxy.APPLY_PORT"
        const val ACTION_TOGGLE_READING = "io.github.fate_grand_automata.capture.proxy.TOGGLE_READING"
        const val ACTION_SHUTDOWN = "io.github.fate_grand_automata.capture.proxy.SHUTDOWN"
        const val EXTRA_STOP_REASON = "stop_reason"

        private val instanceGate = InstanceGate()

        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 78
        private const val RETREAT_NOTIFICATION_ID = 79
        private const val REQUEST_CODE_OPEN = 10
        private const val REQUEST_CODE_TOGGLE = 11
        private const val NETWORK_DEBOUNCE_MS = 2_000L

        internal val ROOT_SERIAL_EXECUTOR: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "fga-root-serial").apply { isDaemon = true }
            }

        private val PROXY_OPTIONS = ProxyOptions(
            socks5 = true,
            ipSniff = true,
            maxConnections = 256,
            tunnelIdleTimeout = 10.minutes,
        )

        internal val ROOT_PROXY_OPTIONS = ProxyOptions(
            transparent = true,
            ipv6Loopback = true,
            maxConnections = 1024,
            tunnelIdleTimeout = 10.minutes,
        )

        /**
         * R5 / B8 startup cleanup helper: checks [RootRulesActiveFlag] synchronously and,
         * only when the flag is set, queues cleanup onto [ROOT_SERIAL_EXECUTOR].
         * Never calls `su` when the flag is clear.
         */
        internal fun cleanupLeftoverRootRulesAsync(
            context: Context,
            shell: RootShell = ProcessRootShell(),
        ) {
            val flag = RootRulesActiveFlag.from(context)
            if (!flag.isSet()) return
            ROOT_SERIAL_EXECUTOR.execute {
                if (!CaptureProxyStatus.state.value.reading && flag.isSet()) {
                    val res = runCatching {
                        shell.run(ROOT_SH_COMMAND, RootRedirectScripts.cleanup(killWatchdog = true), 10)
                    }.getOrElse {
                        RootShellOutput(-1, emptyList(), false)
                    }
                    if (RootCaptureRunner.isCleanupOk(res)) {
                        flag.clear()
                    }
                }
            }
        }
    }
}
