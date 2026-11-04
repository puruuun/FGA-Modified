package io.github.fate_grand_automata.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.DnsPackets
import io.github.fate_grand_automata.ui.main.MainActivity
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Routes only the game app into a local read-only proxy. The VPN needs default routes and a DNS
 * server: with a narrow route Android does not deliver [Builder.setHttpProxy] to the app. Packets
 * that reach the tun are dropped, except DNS to the fake resolver, which is forwarded.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CaptureVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxy: ProxyServer? = null
    private var drain: Thread? = null
    private val dnsPool = Executors.newCachedThreadPool()
    private var stopReason = CaptureStopReason.User

    override fun onCreate() {
        super.onCreate()
        instance = this
        CaptureTracker.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() must be answered with startForeground() even when we stop at once.
        startForegroundNotification()
        if (intent?.action == ACTION_STOP) {
            stopAll(CaptureStopReason.User)
            return START_NOT_STICKY
        }
        if (tun != null) return START_NOT_STICKY
        try {
            val sslContext = if (CertStore.exists(this)) CertStore.serverContext(this) else null
            if (sslContext == null) Timber.w("Capture: no certificate, the game host is tunnelled and not read")
            val p = ProxyServer(sslContext)
            val port = p.start()
            proxy = p

            val builder = Builder()
                .addAddress("10.255.77.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:77::1", 128)
                .addRoute("::", 0)
                .addDnsServer(FAKE_DNS)
                .setSession(getString(R.string.capture_notification_title))
                .setBlocking(false)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
            builder.addAllowedApplication(GAME_PACKAGE)
            val fd = builder.establish()
            if (fd == null) {
                Timber.w("Capture: VPN permission is missing")
                stopAll(CaptureStopReason.Error)
                return START_NOT_STICKY
            }
            tun = fd
            CaptureTracker.setRunning(true)
            drain = Thread({ drainTun(fd) }, "capture-tun").apply { isDaemon = true; start() }
            Timber.i("Capture: VPN started")
        } catch (e: Exception) {
            Timber.w("Capture: VPN start failed (%s)", e.javaClass.simpleName)
            stopAll(CaptureStopReason.Error)
        }
        return START_NOT_STICKY
    }

    private fun drainTun(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32 * 1024)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) {
                    Thread.sleep(20)
                    continue
                }
                // Everything but DNS to the fake resolver is dropped.
                val query = DnsPackets.parse(buf, n) ?: continue
                dnsPool.execute {
                    runCatching {
                        val answer = forwardDns(query.payload) ?: return@runCatching
                        val reply = DnsPackets.reply(query, answer)
                        synchronized(output) { output.write(reply) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Sends one DNS payload through a protected socket to the underlying network's resolvers. */
    private fun forwardDns(payload: ByteArray): ByteArray? {
        for (server in dnsServers()) {
            try {
                DatagramSocket().use { socket ->
                    if (!protect(socket)) return@use
                    socket.soTimeout = 3000
                    socket.send(DatagramPacket(payload, payload.size, server, 53))
                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packet.length >= 12) return buf.copyOf(packet.length)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun dnsServers(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val system = runCatching {
            cm.allNetworks
                .filter { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false }
                .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() }
                .filterIsInstance<java.net.Inet4Address>()
        }.getOrDefault(emptyList())
        val fallback = listOf("8.8.8.8", "1.1.1.1").map { InetAddress.getByName(it) }
        return (system + fallback).distinct()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.capture_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_key)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_notification_stop), stop).build())
            .setOngoing(true)
            .build()
        // The specialUse type is unknown before Android 14; passing it earlier can throw.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    fun stopAll(reason: CaptureStopReason) {
        stopReason = reason
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
        proxy?.stop()
        proxy = null
        CaptureTracker.setRunning(false, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Timber.i("Capture: VPN stopped (%s)", reason)
    }

    override fun onRevoke() {
        // Another VPN took over, or the user removed the permission.
        stopAll(CaptureStopReason.Revoked)
    }

    override fun onDestroy() {
        if (CaptureTracker.running.value) stopAll(stopReason)
        dnsPool.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.fate_grand_automata.capture.STOP"
        const val GAME_PACKAGE = "com.netmarble.fgok"
        private const val FAKE_DNS = "10.255.77.2"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 77

        @Volatile
        var instance: CaptureVpnService? = null
            private set
    }
}

/** Starts and stops the capture VPN. Only meaningful on Android 10+, which is where the UI is shown. */
object CaptureController {
    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The consent intent, or null when the VPN permission is already granted. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    /** Starts the VPN. The caller has checked consent. */
    fun start(context: Context) {
        if (!isSupported) return
        context.startForegroundService(Intent(context, CaptureVpnService::class.java))
    }

    fun stop(reason: CaptureStopReason = CaptureStopReason.User) {
        if (!isSupported) return
        CaptureVpnService.instance?.stopAll(reason)
    }
}
