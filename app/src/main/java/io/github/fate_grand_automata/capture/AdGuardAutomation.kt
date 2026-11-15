package io.github.fate_grand_automata.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber

sealed interface AdGuardCommand {
    val action: String

    data class ProxyDefault(val port: Int) : AdGuardCommand {
        override val action: String = AdGuardAutomation.ACTION_PROXY_DEFAULT
    }

    data class ProxyState(val enable: Boolean) : AdGuardCommand {
        override val action: String = AdGuardAutomation.ACTION_PROXY_STATE
    }
}

enum class AdGuardSwitchEvent {
    ReadingOn,
    ReadingOff,
    ServiceStartEnsureOrSticky,
    LeavingOrShutdown,
}

object AdGuardAutomation {
    const val PACKAGE_NAME = "com.adguard.android"
    const val AUTOMATION_RECEIVER = "com.adguard.android.receiver.AutomationReceiver"

    const val ACTION_PROXY_DEFAULT = "proxy_default"
    const val ACTION_PROXY_STATE = "proxy_state"

    const val EXTRA_PASSWORD = "password"
    const val EXTRA_QUIET = "quiet"
    const val EXTRA_ENABLE = "enable"
    const val EXTRA_SERVER = "server"

    private const val PROXY_TYPE = "SOCKS5"
    private const val PROXY_HOST = "127.0.0.1"

    fun isInstalled(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE_NAME, 0)
        }
        true
    }.getOrDefault(false)

    fun isAutoSwitchEffective(enabled: Boolean, password: String): Boolean =
        enabled && password.isNotBlank()

    fun buildExtras(command: AdGuardCommand, password: String): Map<String, String> = when (command) {
        is AdGuardCommand.ProxyDefault -> mapOf(
            EXTRA_PASSWORD to password,
            EXTRA_QUIET to "true",
            EXTRA_SERVER to "type=$PROXY_TYPE&host=$PROXY_HOST&port=${command.port}",
        )

        is AdGuardCommand.ProxyState -> mapOf(
            EXTRA_PASSWORD to password,
            EXTRA_QUIET to "true",
            EXTRA_ENABLE to command.enable.toString(),
        )
    }

    fun buildIntent(command: AdGuardCommand, password: String): Intent =
        Intent(command.action).apply {
            component = ComponentName(PACKAGE_NAME, AUTOMATION_RECEIVER)
            buildExtras(command, password).forEach { (key, value) ->
                putExtra(key, value)
            }
        }

    fun formatCommandForLog(command: AdGuardCommand): String = when (command) {
        is AdGuardCommand.ProxyDefault -> "action=$ACTION_PROXY_DEFAULT port=${command.port}"
        is AdGuardCommand.ProxyState -> "action=$ACTION_PROXY_STATE enable=${command.enable}"
    }

    fun commandsFor(
        event: AdGuardSwitchEvent,
        autoSwitchEffective: Boolean,
        readingActive: Boolean,
        startupDisableSent: Boolean,
        port: Int,
    ): List<AdGuardCommand> {
        if (!autoSwitchEffective) return emptyList()
        return when (event) {
            AdGuardSwitchEvent.ReadingOn -> listOf(
                AdGuardCommand.ProxyDefault(port),
                AdGuardCommand.ProxyState(enable = true),
            )

            AdGuardSwitchEvent.ReadingOff -> listOf(
                AdGuardCommand.ProxyState(enable = false),
            )

            AdGuardSwitchEvent.ServiceStartEnsureOrSticky -> {
                if (!readingActive && !startupDisableSent) {
                    listOf(AdGuardCommand.ProxyState(enable = false))
                } else {
                    emptyList()
                }
            }

            AdGuardSwitchEvent.LeavingOrShutdown -> listOf(
                AdGuardCommand.ProxyState(enable = false),
            )
        }
    }

    fun sendCommands(context: Context, commands: List<AdGuardCommand>, password: String) {
        for (command in commands) {
            runCatching {
                context.sendBroadcast(buildIntent(command, password))
            }.onFailure { t ->
                Timber.w(
                    "AdGuard auto-switch broadcast failed (%s): %s",
                    formatCommandForLog(command),
                    t::class.simpleName ?: "Exception",
                )
            }
        }
    }
}
