package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RequiresApi
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import timber.log.Timber

enum class CaptureServiceTarget {
    VpnService,
    ProxyService,
}

enum class CaptureStartKind {
    StartVpn,
    StartProxyReading,
}

enum class CaptureStopKind {
    StopVpn,
    StopProxyReading,
}

fun captureConsentMode(mode: CaptureConnectionMode): CaptureServiceTarget = when (mode) {
    CaptureConnectionMode.Vpn -> CaptureServiceTarget.VpnService
    CaptureConnectionMode.AdGuard -> CaptureServiceTarget.ProxyService
}

fun captureStartDispatch(mode: CaptureConnectionMode): CaptureStartKind = when (mode) {
    CaptureConnectionMode.Vpn -> CaptureStartKind.StartVpn
    CaptureConnectionMode.AdGuard -> CaptureStartKind.StartProxyReading
}

fun captureStopDispatch(mode: CaptureConnectionMode): CaptureStopKind = when (mode) {
    CaptureConnectionMode.Vpn -> CaptureStopKind.StopVpn
    CaptureConnectionMode.AdGuard -> CaptureStopKind.StopProxyReading
}

fun shouldEnsureCaptureProxy(
    supported: Boolean,
    enabled: Boolean,
    consentAgreed: Boolean,
    mode: CaptureConnectionMode,
): Boolean = supported && enabled && consentAgreed && mode == CaptureConnectionMode.AdGuard

/**
 * Starts and stops the capture transport (VPN or resident AdGuard proxy).
 * Never calls [CaptureProxyService] directly; communicates solely via service intents.
 */
object CaptureController {
    @Volatile
    private var appContext: Context? = null

    val isSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun connectionMode(context: Context): CaptureConnectionMode {
        init(context)
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        return CaptureConnectionMode.parse(prefs.getString("capture_connection_mode", null))
    }

    fun shouldEnsureProxy(prefs: PrefsCore): Boolean = shouldEnsureCaptureProxy(
        supported = isSupported,
        enabled = prefs.captureTrackerEnabled.get(),
        consentAgreed = CaptureConsent.agreed(prefs.captureConsentVersion.get()),
        mode = prefs.captureConnectionMode.get(),
    )

    fun shouldEnsureProxy(context: Context): Boolean {
        init(context)
        val prefs = PrefsCore(
            PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)),
            context,
        )
        return shouldEnsureProxy(prefs)
    }

    /** The consent intent, or null when permission is already granted or AdGuard proxy mode is active. */
    fun consentIntent(context: Context): Intent? {
        init(context)
        return when (captureConsentMode(connectionMode(context))) {
            CaptureServiceTarget.VpnService -> VpnService.prepare(context)
            CaptureServiceTarget.ProxyService -> null
        }
    }

    /**
     * Starts capture (or turns reading on in AdGuard mode). Returns true if the start command could
     * be dispatched, or false if startup is blocked (e.g. proxy port conflict or FGS restriction).
     */
    fun start(context: Context): Boolean {
        init(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return when (captureStartDispatch(connectionMode(context))) {
            CaptureStartKind.StartVpn -> {
                context.startForegroundService(Intent(context, CaptureVpnService::class.java))
                true
            }

            CaptureStartKind.StartProxyReading -> {
                val proxyState = CaptureProxyStatus.state.value.state
                if (proxyState is CaptureProxyState.PortError) {
                    CaptureTracker.starting = false
                    val msg = context.getString(R.string.capture_proxy_port_in_use, proxyState.port)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
                    }
                    ensureProxy(context)
                    return false
                }
                sendProxyCommandQ(context, CaptureProxyService.ACTION_START_READING).also { sent ->
                    if (!sent) CaptureTracker.starting = false
                }
            }
        }
    }

    fun stop(reason: CaptureStopReason = CaptureStopReason.User, context: Context? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val ctx = context?.also(::init) ?: appContext
        val mode = ctx?.let(::connectionMode) ?: CaptureConnectionMode.Vpn
        when (captureStopDispatch(mode)) {
            CaptureStopKind.StopVpn -> stopVpn(reason)
            CaptureStopKind.StopProxyReading -> {
                if (ctx != null) {
                    sendProxyCommandQ(
                        ctx,
                        CaptureProxyService.ACTION_STOP_READING,
                        CaptureProxyService.EXTRA_STOP_REASON to reason.name,
                    )
                }
            }
        }
    }

    fun isVpnActive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CaptureVpnService.instance != null

    fun isProxyAlive(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CaptureProxyService.alive

    fun stopVpn(reason: CaptureStopReason = CaptureStopReason.User) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        CaptureVpnService.instance?.stopAll(reason)
    }

    fun ensureProxy(context: Context): Boolean {
        init(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !shouldEnsureProxy(context)) return false
        return ensureProxyQ(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureProxyQ(context: Context): Boolean = try {
        context.startForegroundService(
            Intent(context, CaptureProxyService::class.java).setAction(CaptureProxyService.ACTION_ENSURE)
        )
        true
    } catch (t: Throwable) {
        // Android 12+ may restrict background foreground-service starts at some call sites.
        Timber.d(t, "Capture: ensureProxy skipped (%s)", t.javaClass.simpleName)
        false
    }

    fun applyProxyPort(context: Context) {
        init(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !shouldEnsureProxy(context)) return
        sendProxyCommandQ(context, CaptureProxyService.ACTION_APPLY_PORT)
    }

    fun shutdownProxy(context: Context) {
        init(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        shutdownProxyQ(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun shutdownProxyQ(context: Context) {
        val intent = Intent(context, CaptureProxyService::class.java)
            .setAction(CaptureProxyService.ACTION_SHUTDOWN)
        val sent = runCatching {
            if (CaptureProxyStatus.state.value.state != CaptureProxyState.Idle) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
            true
        }.getOrDefault(false)
        if (!sent) {
            runCatching { context.stopService(Intent(context, CaptureProxyService::class.java)) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sendProxyCommandQ(
        context: Context,
        action: String,
        extra: Pair<String, String>? = null,
    ): Boolean = try {
        val intent = Intent(context, CaptureProxyService::class.java).setAction(action)
        if (extra != null) intent.putExtra(extra.first, extra.second)
        context.startForegroundService(intent)
        true
    } catch (t: Throwable) {
        Timber.d(t, "Capture: proxy command %s failed (%s)", action, t.javaClass.simpleName)
        false
    }
}
