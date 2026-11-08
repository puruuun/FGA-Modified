package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/** Restores only placed widgets, off the application/receiver main thread. */
object WidgetRefresh {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var updateService: suspend (Context) -> Unit = { ServiceWidget().updateAll(it) }
    internal var updateAp: suspend (Context) -> Unit = { ApWidgetRefresh.updateAll(it) }

    internal fun hasApWidget(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, ApWidgetReceiver::class.java)).isNotEmpty()

    suspend fun updatePlaced(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetIds(ComponentName(context, ServiceWidgetReceiver::class.java)).isNotEmpty()) {
            runCatching { updateService(context) }.onFailure { Timber.w(it, "Service widget refresh failed") }
        }
        if (hasApWidget(context)) {
            runCatching { updateAp(context) }.onFailure { Timber.w(it, "AP widget refresh failed") }
        }
    }

    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        scope.launch { updatePlaced(app) }
    }
}

class WidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext
        WidgetRefresh.scope.launch {
            try {
                WidgetRefresh.updatePlaced(app)
            } finally {
                pending?.finish()
            }
        }
    }
}