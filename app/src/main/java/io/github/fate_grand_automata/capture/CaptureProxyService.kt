package io.github.fate_grand_automata.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.main.MainActivity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes

/**
 * Resident local proxy service for AdGuard mode. Keeps listening on 127.0.0.1:<port> whenever
 * capture is enabled, consent is agreed, and connection mode is AdGuard, and toggles MITM
 * collection on/off without tearing down the listening socket.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@AndroidEntryPoint
class CaptureProxyService : Service() {
    @Inject lateinit var prefs: PrefsCore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var proxy: ProxyServer? = null
    private var boundPort: Int? = null
    private var ownsSaveGuard = false
    private var readingActive = false
    private var shuttingDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CaptureController.init(applicationContext)
        CaptureTracker.init(applicationContext)
        CaptureProxyStatus.reset()
        observeStartConditions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val action = intent?.action
        if (action == ACTION_SHUTDOWN) {
            shutdownAndStopSelf(CaptureStopReason.User)
            return START_NOT_STICKY
        }

        if (!CaptureController.shouldEnsureProxy(prefs)) {
            shutdownAndStopSelf(CaptureStopReason.User)
            return START_NOT_STICKY
        }

        val targetPort = prefs.captureProxyPort.get()
        when (action) {
            null -> {
                // Service restarted by START_STICKY: rebind the listener with reading left off.
                turnReadingOff(CaptureStopReason.User)
                ensureListening(targetPort)
            }

            ACTION_ENSURE -> {
                ensureListening(targetPort)
            }

            ACTION_START_READING -> {
                val listening = ensureListening(targetPort)
                if (listening) {
                    turnReadingOn()
                } else {
                    CaptureTracker.starting = false
                    showPortErrorToast(targetPort)
                }
            }

            ACTION_STOP_READING -> {
                val reason = intent.getStringExtra(EXTRA_STOP_REASON)
                    ?.let { name -> CaptureStopReason.entries.firstOrNull { it.name == name } }
                    ?: CaptureStopReason.User
                turnReadingOff(reason)
            }

            ACTION_TOGGLE_READING -> {
                if (readingActive || CaptureTracker.running.value) {
                    turnReadingOff(CaptureStopReason.User)
                } else {
                    val listening = ensureListening(targetPort)
                    if (listening) {
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
        }

        updateNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // AdGuard keeps pointing at FGA even after swiping FGA away from Recents; stay alive.
    }

    override fun onDestroy() {
        shuttingDown = true
        serviceScope.cancel()
        jpScope.cancel()
        turnReadingOff(CaptureStopReason.User)
        stopCurrentProxy()
        CaptureProxyStatus.reset()
        super.onDestroy()
    }

    private fun observeStartConditions() {
        serviceScope.launch {
            combine(
                prefs.captureTrackerEnabled.asFlow(),
                prefs.captureConsentVersion.asFlow(),
                prefs.captureConnectionMode.asFlow(),
            ) { enabled, consentVersion, mode ->
                shouldEnsureCaptureProxy(
                    supported = CaptureController.isSupported,
                    enabled = enabled,
                    consentAgreed = CaptureConsent.agreed(consentVersion),
                    mode = mode,
                )
            }.collect { canRun ->
                if (!canRun && !shuttingDown) {
                    shutdownAndStopSelf(CaptureStopReason.User)
                }
            }
        }
    }

    private fun ensureListening(port: Int): Boolean {
        if (proxy != null && boundPort == port && CaptureProxyStatus.state.value.state is CaptureProxyState.Listening) {
            return true
        }
        return bindProxy(port)
    }

    private fun applyPortChange(newPort: Int) {
        if (readingActive || CaptureTracker.running.value || CaptureTracker.starting) {
            turnReadingOff(CaptureStopReason.User)
        }
        if (proxy != null && boundPort == newPort && CaptureProxyStatus.state.value.state is CaptureProxyState.Listening) {
            return
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
        val oldProxy = proxy
        val created = createConfiguredProxy()
        return try {
            val actualPort = created.start(preferredPort = port, allowFallback = false)
            proxy = created
            boundPort = actualPort
            runCatching { oldProxy?.stop() }
            CaptureProxyStatus.setListening(actualPort, reading = readingActive)
            Timber.i("Capture: proxy listening on 127.0.0.1:%d", actualPort)
            true
        } catch (t: Throwable) {
            Timber.w(t, "Capture: failed to bind proxy port %d", port)
            runCatching { created.stop() }
            runCatching { oldProxy?.stop() }
            proxy = null
            boundPort = null
            readingActive = false
            releaseSaveGuard()
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
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }

        try {
            check(CertificateSaveGuard.beginVpn()) { "Certificate save in progress" }
            ownsSaveGuard = true
            val jpEnabled = prefs.captureJpRoot.get()
            val contexts = captureServerContexts(
                if (CertStore.exists(this)) CertStore.serverContext(this) else null,
                if (jpEnabled && CertStore.existsJp(this)) CertStore.jpServerContext(this) else null,
                jpEnabled,
            )
            if (contexts.isEmpty()) {
                Timber.w("Capture: no certificate context available for AdGuard proxy")
                turnReadingOff(CaptureStopReason.Error)
                Handler(Looper.getMainLooper()).post {
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
            turnReadingOff(CaptureStopReason.Error)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, R.string.capture_start_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun turnReadingOff(reason: CaptureStopReason) {
        proxy?.setContexts(emptyMap())
        readingActive = false
        releaseSaveGuard()
        if (CaptureTracker.running.value || CaptureTracker.starting) {
            CaptureTracker.setRunning(false, reason)
        }
        CaptureProxyStatus.setReading(false)
    }

    private fun releaseSaveGuard() {
        if (ownsSaveGuard) {
            ownsSaveGuard = false
            CertificateSaveGuard.endVpn()
        }
    }

    private fun stopCurrentProxy() {
        runCatching { proxy?.stop() }
        proxy = null
        boundPort = null
    }

    private fun shutdownAndStopSelf(reason: CaptureStopReason) {
        shuttingDown = true
        turnReadingOff(reason)
        stopCurrentProxy()
        CaptureProxyStatus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showPortErrorToast(port: Int) {
        val msg = getString(R.string.capture_proxy_port_in_use, port)
        Handler(Looper.getMainLooper()).post {
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
        val text = when (snap.state) {
            is CaptureProxyState.PortError -> getString(R.string.capture_proxy_notification_port_error, displayPort)
            is CaptureProxyState.Listening, CaptureProxyState.Idle -> {
                if (snap.reading) {
                    getString(R.string.capture_proxy_notification_collecting, displayPort)
                } else {
                    getString(R.string.capture_proxy_notification_waiting, displayPort)
                }
            }
        }
        val actionTitle = getString(
            if (snap.reading) R.string.capture_proxy_notification_action_stop
            else R.string.capture_proxy_notification_action_start
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_key)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, actionTitle, toggle).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_ENSURE = "io.github.fate_grand_automata.capture.proxy.ENSURE"
        const val ACTION_START_READING = "io.github.fate_grand_automata.capture.proxy.START_READING"
        const val ACTION_STOP_READING = "io.github.fate_grand_automata.capture.proxy.STOP_READING"
        const val ACTION_APPLY_PORT = "io.github.fate_grand_automata.capture.proxy.APPLY_PORT"
        const val ACTION_TOGGLE_READING = "io.github.fate_grand_automata.capture.proxy.TOGGLE_READING"
        const val ACTION_SHUTDOWN = "io.github.fate_grand_automata.capture.proxy.SHUTDOWN"
        const val EXTRA_STOP_REASON = "stop_reason"

        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 78
        private const val REQUEST_CODE_OPEN = 10
        private const val REQUEST_CODE_TOGGLE = 11

        private val PROXY_OPTIONS = ProxyOptions(
            socks5 = true,
            ipSniff = true,
            maxConnections = 256,
            tunnelIdleTimeout = 10.minutes,
        )
    }
}
