package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Restores only placed widgets, off the application/receiver main thread. */
object WidgetRefresh {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val updates = MutableStateFlow(0L)

    /** Bumped on every update of the placed widgets, so live Glance sessions re-read the widget settings. */
    internal val revision = updates.asStateFlow()

    internal var updateService: suspend (Context) -> Unit = { updateReceiverWidgets(it, ServiceWidgetReceiver::class.java, ServiceWidget()) }
    internal var updateAp: suspend (Context) -> Unit = { ApWidgetRefresh.updateAll(it) }

    /**
     * Updates the widgets placed for one receiver, found by its component.
     * Glance's own updateAll finds them through a stored receiver-to-class-name map, which goes stale
     * when a new build renames or merges the widget classes; the receiver component never changes.
     */
    internal suspend fun updateReceiverWidgets(context: Context, receiver: Class<*>, widget: GlanceAppWidget) {
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver))
        if (ids.isEmpty()) return
        val glance = GlanceAppWidgetManager(context)
        for (id in ids) widget.update(context, glance.getGlanceIdBy(id))
    }

    /** Either AP widget: the plain one or the one with recent logs. Both share the refresh path. */
    internal fun hasApWidget(context: Context): Boolean = AppWidgetManager.getInstance(context).let { manager ->
        manager.getAppWidgetIds(ComponentName(context, ApWidgetReceiver::class.java)).isNotEmpty() || hasLogsWidget(context)
    }

    internal fun hasLogsWidget(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, ApWidgetLogsReceiver::class.java)).isNotEmpty()

    suspend fun updatePlaced(context: Context) {
        updates.update { it + 1 }
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