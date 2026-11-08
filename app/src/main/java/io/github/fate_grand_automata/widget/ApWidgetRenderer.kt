package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService
import timber.log.Timber

/**
 * The AP widget is plain RemoteViews: the platform accepts a collection adapter only on the top-level views
 * given to [AppWidgetManager.updateAppWidget], so the StackView cannot live inside a Glance composition.
 */
object ApWidgetRenderer {
    /** Width assumed when the launcher reports none: the default 4x1 size. */
    internal const val DEFAULT_WIDTH_DP = 250

    /** Replaced in tests. */
    internal var loader: suspend (Context) -> ApWidgetState = { loadApWidgetState(it) }

    /** Redraws every placed AP widget. The one entry point for boot, app start, saves, settings and the worker. */
    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ApWidgetReceiver::class.java))
        update(context, manager, ids)
    }

    suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val state = runCatching { loader(context) }.onFailure { Timber.w(it, "AP widget load failed") }
            .getOrDefault(ApWidgetState.NoData)
        val settings = loadWidgetSettings(context)
        val started = ScriptRunnerService.serviceStarted.value
        for (id in ids) {
            runCatching {
                manager.updateAppWidget(id, buildApWidgetViews(context, id, state, settings, started, widthDp(manager, id)))
            }.onFailure { Timber.w(it, "AP widget %d update failed", id) }
        }
    }

    /** The narrow (portrait) width the launcher reports, so the text never outgrows the widget. */
    internal fun widthDp(manager: AppWidgetManager, id: Int): Int =
        manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
}

/** The complete widget: card, account stack (or "no data"), and the start/stop tile. */
fun buildApWidgetViews(
    context: Context,
    appWidgetId: Int,
    state: ApWidgetState,
    settings: WidgetSettings,
    serviceStarted: Boolean,
    widthDp: Int,
    now: Long = System.currentTimeMillis() / 1000,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ap_root)
    val palette = widgetPalette(settings.theme)
    fun color(res: Int) = ContextCompat.getColor(context, res)

    views.setInt(R.id.ap_root, "setBackgroundResource", widgetCardRes(settings))

    when (state) {
        is ApWidgetState.Data -> {
            views.setViewVisibility(R.id.ap_stack, View.VISIBLE)
            views.setViewVisibility(R.id.ap_no_data, View.GONE)
            addApStack(context, views, appWidgetId, state.models, palette, apWidgetFields(widthDp.toFloat()), now)
        }
        ApWidgetState.NoData -> {
            views.setViewVisibility(R.id.ap_stack, View.GONE)
            views.setViewVisibility(R.id.ap_no_data, View.VISIBLE)
            views.setTextColor(R.id.ap_no_data, color(palette.secondary))
            views.setOnClickPendingIntent(R.id.ap_no_data, activityPendingIntent(context, WidgetIntents.openApp(context)))
        }
    }

    // The start/stop tile, same look as the Glance tile of the 1x1 widget.
    val model = serviceWidgetModel(serviceStarted)
    val accent = if (serviceStarted) palette.on else palette.accent
    views.setInt(R.id.ap_tile, "setBackgroundResource", palette.tileBackground)
    views.setInt(R.id.ap_tile_circle, "setBackgroundResource", if (serviceStarted) palette.toggleCircleOn else palette.toggleCircle)
    views.setImageViewResource(R.id.ap_tile_icon, model.iconRes)
    views.setInt(R.id.ap_tile_icon, "setColorFilter", color(accent))
    views.setContentDescription(R.id.ap_tile_icon, context.getString(model.descriptionRes))
    views.setTextViewText(R.id.ap_tile_label, context.getString(model.labelRes))
    views.setTextColor(R.id.ap_tile_label, color(if (serviceStarted) palette.on else palette.secondary))
    views.setOnClickPendingIntent(R.id.ap_tile, tilePendingIntent(context, model.action))
    return views
}

/** Start opens the app's start flow; stop goes to a broadcast receiver and never opens the UI. */
internal fun tilePendingIntent(context: Context, action: ServiceWidgetAction): PendingIntent = when (action) {
    ServiceWidgetAction.Start -> activityPendingIntent(context, WidgetIntents.startService(context))
    ServiceWidgetAction.Stop -> PendingIntent.getBroadcast(
        context, 0, Intent(context, StopServiceReceiver::class.java), immutableFlags(),
    )
}

private fun activityPendingIntent(context: Context, intent: Intent): PendingIntent =
    PendingIntent.getActivity(context, 0, intent, immutableFlags())

private fun immutableFlags() =
    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
