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
class ApWidgetReceiver : AppWidgetProvider() {
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

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ApWidgetRefresh.cancel(context)
    }

    /** Loading the summaries touches disk, so it runs off the receiver's main thread. */
    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        WidgetRefresh.scope.launch {
            try {
                ApWidgetRenderer.update(app, manager, ids)
            } finally {
                pending?.finish()
            }
        }
    }
}
