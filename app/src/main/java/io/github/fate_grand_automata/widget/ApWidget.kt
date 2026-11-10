package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.os.Build
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.PrefMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun captureEnabled(context: Context): Boolean =
    PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))
        .bool("capture_tracker_enabled", false).get()

suspend fun loadApWidgetState(context: Context, now: Long = System.currentTimeMillis() / 1000): ApWidgetState =
    withContext(Dispatchers.IO) {
        val enabled = captureEnabled(context)
        if (!enabled || Build.VERSION.SDK_INT < 29) return@withContext ApWidgetState.NoData
        CaptureTracker.initSummaries(context)
        apWidgetState(enabled, Build.VERSION.SDK_INT, CaptureTracker.accountSummaries(now), now)
    }

/**
 * Plain AppWidgetProvider: its component name is what placed widgets are bound to, so it stays
 * `ApWidgetReceiver` even though the rendering no longer goes through Glance.
 */
open class ApWidgetReceiver : AppWidgetProvider() {
    /** The account info + recent logs widget overrides this; everything else is shared. */
    protected open val withLogs: Boolean = false

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ApWidgetRefresh.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ApWidgetRefresh.schedule(context)
        render(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        render(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        ApWidgetShown.remove(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The other AP widget kind may still be placed and needs the periodic refresh.
        if (!WidgetRefresh.hasApWidget(context)) ApWidgetRefresh.cancel(context)
    }

    /** Loading the summaries touches disk, so it runs off the receiver's main thread. */
    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        WidgetRefresh.scope.launch {
            try {
                ApWidgetRenderer.update(app, manager, ids, withLogs)
            } finally {
                pending?.finish()
            }
        }
    }
}

/**
 * "계정 정보 + 최근 로그": the AP widget's top row with a row of recent-log cards under it. Its own component, so
 * placed widgets keep their kind; it shares the AP widget's refresh path.
 */
class ApWidgetLogsReceiver : ApWidgetReceiver() {
    override val withLogs: Boolean = true
}
