package io.github.fate_grand_automata.capturespike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class CaptureVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxy: ProxyServer? = null
    private var drain: Thread? = null

    override fun onCreate() {
        super.onCreate()
        SpikeLog.log("service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (tun != null) return START_NOT_STICKY
        try {
            val sslContext = if (CertStore.leafExists(this)) CertStore.serverContext(this) else null
            if (sslContext == null) SpikeLog.log("no leaf certificate: game host will be tunnelled, not intercepted")
            val p = ProxyServer(this, sslContext)
            val port = p.start()
            proxy = p

            val builder = Builder()
                .addAddress("10.255.77.1", 32)
                // 2026-11-04 tablet check: with only a narrow route the VPN was not the apps' default
                // network, so they kept the Wi-Fi proxy (PAC) and their direct sockets died in the VPN.
                // A default route makes the VPN their default network, which is what carries
                // setHttpProxy to them; packets that still arrive on the tun are dropped.
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:77::1", 128)
                .addRoute("::", 0)
                .addDnsServer("10.255.77.2")
                .setSession("FGA capture spike")
                .setBlocking(false)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
            try {
                builder.addAllowedApplication(GAME_PACKAGE)
                // Control app for the 2026-11-04 tablet check: Chrome is known to follow the system
                // proxy, so its connections tell whether the VPN proxy reaches apps at all
                builder.addAllowedApplication(CONTROL_PACKAGE)
                SpikeLog.log("VPN apps: $GAME_PACKAGE, $CONTROL_PACKAGE")
            } catch (e: Exception) {
                SpikeLog.log("addAllowedApplication($GAME_PACKAGE) failed: $e")
                stopAll()
                return START_NOT_STICKY
            }
            val fd = builder.establish()
            if (fd == null) {
                SpikeLog.log("VPN establish() returned null (consent missing?)")
                stopAll()
                return START_NOT_STICKY
            }
            tun = fd
            drain = Thread {
                val input = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32 * 1024)
                try {
                    while (true) {
                        if (input.read(buf) <= 0) Thread.sleep(50)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            SpikeLog.log("VPN started (proxy port $port)")
        } catch (e: Exception) {
            SpikeLog.log("VPN start failed: $e")
            stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("spike", "FGA capture spike", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "spike")
            .setContentTitle("FGA 캡처 시험")
            .setContentText("VPN running (127.0.0.1 proxy)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun stopAll() {
        drain?.interrupt()
        drain = null
        runCatching { tun?.close() }
        tun = null
        proxy?.stop()
        proxy = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        SpikeLog.log("VPN stopped")
    }

    override fun onRevoke() {
        SpikeLog.log("VPN revoked by system")
        stopAll()
    }

    override fun onDestroy() {
        runCatching { tun?.close() }
        proxy?.stop()
        SpikeLog.log("service onDestroy")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.fate_grand_automata.capturespike.STOP"
        const val GAME_PACKAGE = "com.netmarble.fgok"
        const val CONTROL_PACKAGE = "com.android.chrome"
    }
}
