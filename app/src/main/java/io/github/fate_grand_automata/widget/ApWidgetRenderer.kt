package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.WidgetDisplayStyle
import kotlinx.coroutines.launch
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
                manager.updateAppWidget(
                    id,
                    buildApWidgetViews(
                        context, id, state, settings, started, widthDp(context, manager, id), ApWidgetShown.get(context, id),
                        heightDp = chooseWidgetHeightDp(manager.getAppWidgetOptions(id), isLandscape(context)),
                    ),
                )
            }.onFailure { Timber.w(it, "AP widget %d update failed", id) }
        }
    }

    /** Shows the next or previous account (wrapping around) on one widget and remembers it. */
    internal suspend fun step(context: Context, appWidgetId: Int, direction: Int) {
        val state = runCatching { loader(context) }.getOrDefault(ApWidgetState.NoData) as? ApWidgetState.Data ?: return
        val index = ApWidgetShown.step(state.models, ApWidgetShown.get(context, appWidgetId), direction)
        ApWidgetShown.set(context, appWidgetId, state.models[index].accountId)
        val views = RemoteViews(context.packageName, R.layout.widget_ap_root)
        views.setDisplayedChild(R.id.ap_flipper, index)
        runCatching { AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(appWidgetId, views) }
            .onFailure { Timber.w(it, "AP widget %d step failed", appWidgetId) }
    }

    /** The width the launcher gives this widget in the current orientation. */
    internal fun widthDp(context: Context, manager: AppWidgetManager, id: Int): Int =
        chooseWidgetWidthDp(manager.getAppWidgetOptions(id), isLandscape(context))

    internal fun isLandscape(context: Context) =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

/**
 * Picks the widget width in dp from the launcher's option bundle for the current orientation.
 * Android 12+ launchers list the possible sizes ([AppWidgetManager.OPTION_APPWIDGET_SIZES]): portrait is the
 * narrowest, landscape the widest. Older ones only give min/max: portrait is MIN_WIDTH, landscape MAX_WIDTH.
 * (Reading MIN_WIDTH in landscape picked the smallest type: Lawnchair reports 183 dp there for a 378 dp widget.)
 */
@Suppress("DEPRECATION")
fun chooseWidgetWidthDp(options: Bundle, landscape: Boolean): Int {
    val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) }.getOrNull()
    } else null
    val fromSizes = sizes?.map { it.width }?.filter { it > 0 }?.let { if (landscape) it.maxOrNull() else it.minOrNull() }
    val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    val width = fromSizes?.toInt() ?: (if (landscape) max.takeIf { it > 0 } ?: min else min.takeIf { it > 0 } ?: max)
    return width.takeIf { it > 0 } ?: ApWidgetRenderer.DEFAULT_WIDTH_DP
}

/** Height paired with the current orientation's width, or the provider's 40 dp minimum. */
@Suppress("DEPRECATION")
fun chooseWidgetHeightDp(options: Bundle, landscape: Boolean): Int {
    val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) }.getOrNull()
    } else null
    val valid = sizes?.filter { it.width > 0 && it.height > 0 }
    val size = if (landscape) valid?.maxByOrNull { it.width } else valid?.minByOrNull { it.width }
    val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    val max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    val height = size?.height?.toInt() ?: (if (landscape) min.takeIf { it > 0 } ?: max else max.takeIf { it > 0 } ?: min)
    return height.takeIf { it > 0 } ?: 40
}

/** The complete widget: card, account stack (or "no data"), and the start/stop tile. */
fun buildApWidgetViews(
    context: Context,
    appWidgetId: Int,
    state: ApWidgetState,
    settings: WidgetSettings,
    serviceStarted: Boolean,
    widthDp: Int,
    /** The account this widget shows; the first one when it is null or no longer exists. */
    shownAccountId: String? = null,
    now: Long = System.currentTimeMillis() / 1000,
    collecting: Boolean = CaptureTracker.running.value,
    logAvailable: Boolean = widgetLogButtonAvailable(context, settings),
    heightDp: Int = 74,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ap_root)
    val palette = widgetPalette(settings.theme)
    fun color(res: Int) = ContextCompat.getColor(context, res)

    views.setInt(R.id.ap_root, "setBackgroundResource", widgetCardRes(settings))
    val showLog = widgetLogTileVisible(logAvailable, widthDp, AP_LOG_TILE_MIN_WIDTH_DP)
    val allAccounts = settings.displayStyle == WidgetDisplayStyle.AllAccounts
    val density = context.resources.displayMetrics.density
    views.setViewPadding(R.id.ap_root, (8 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), (8 * density).toInt())
    views.setViewVisibility(R.id.ap_compact_age, View.GONE)
    views.setViewVisibility(R.id.ap_log_label, View.VISIBLE)
    views.setViewVisibility(R.id.ap_tile_label, View.VISIBLE)
    views.setTextViewTextSize(R.id.ap_compact_age, TypedValue.COMPLEX_UNIT_SP, 9f)
    if (allAccounts && state is ApWidgetState.Data && heightDp < 74) {
        val vertical = if (heightDp < 56) 0 else (2 * density).toInt()
        views.setViewPadding(R.id.ap_root, (8 * density).toInt(), vertical, (22 * density).toInt(), vertical)
        if (heightDp < 56) {
            views.setViewVisibility(R.id.ap_log_label, View.GONE)
            views.setViewVisibility(R.id.ap_tile_label, View.GONE)
            views.setTextViewTextSize(R.id.ap_compact_age, TypedValue.COMPLEX_UNIT_SP, 7f)
        }
    }
    views.setViewVisibility(R.id.ap_log_tile, if (showLog) View.VISIBLE else View.GONE)
    if (showLog) {
        views.setInt(R.id.ap_log_tile, "setBackgroundResource", if (collecting) R.drawable.widget_log_tile_on else palette.tileBackground)
        views.setInt(R.id.ap_log_circle, "setBackgroundResource", if (collecting) R.drawable.widget_log_circle_on else palette.toggleCircle)
        views.setInt(R.id.ap_log_icon, "setColorFilter", color(if (collecting) R.color.widget_log_on_foreground else palette.accent))
        views.setContentDescription(R.id.ap_log_tile, context.getString(if (collecting) R.string.capture_main_stop else R.string.capture_main_start))
        views.setTextViewText(R.id.ap_log_label, context.getString(if (collecting) R.string.widget_service_on else R.string.widget_service_off))
        views.setTextColor(R.id.ap_log_label, color(if (collecting) R.color.widget_log_on_foreground else palette.secondary))
        views.setOnClickPendingIntent(R.id.ap_log_tile, capturePendingIntent(context))
    }

    when (state) {
        is ApWidgetState.Data -> {
            views.setViewVisibility(R.id.ap_no_data, View.GONE)
            if (allAccounts) {
                views.setViewVisibility(R.id.ap_flipper, View.GONE)
                val cells = allAccountsCellCount(widthDp, showLog, settings.showServiceButton, state.models.size)
                addAllAccountsRow(
                    context, views, state.models, palette, cells, settings.showServer, now,
                    heightDp = heightDp, compactAge = compactAllAccountsAge(widthDp, showLog, settings.showServiceButton),
                )
            } else {
                views.setViewVisibility(R.id.ap_flipper, View.VISIBLE)
                views.setViewVisibility(R.id.ap_all, View.GONE)
                val index = ApWidgetShown.indexOf(state.models, shownAccountId)
                addApFlipper(
                    context, views, appWidgetId, state.models, palette, apWidgetFields(widthDp.toFloat(), showLog), index, now,
                    showServer = settings.showServer,
                )
            }
            // The up/down buttons only make sense with more than one account, and only in the stack style.
            val several = state.models.size > 1 && !allAccounts
            views.setViewVisibility(R.id.ap_nav, if (several) View.VISIBLE else View.GONE)
            if (several) {
                views.setInt(R.id.ap_nav_up_icon, "setColorFilter", color(palette.secondary))
                views.setInt(R.id.ap_nav_down_icon, "setColorFilter", color(palette.secondary))
                views.setOnClickPendingIntent(R.id.ap_nav_up, navPendingIntent(context, appWidgetId, ApWidgetNavReceiver.PREVIOUS))
                views.setOnClickPendingIntent(R.id.ap_nav_down, navPendingIntent(context, appWidgetId, ApWidgetNavReceiver.NEXT))
            }
        }
        ApWidgetState.NoData -> {
            views.setViewVisibility(R.id.ap_flipper, View.GONE)
            views.setViewVisibility(R.id.ap_all, View.GONE)
            views.setViewVisibility(R.id.ap_nav, View.GONE)
            views.setViewVisibility(R.id.ap_no_data, View.VISIBLE)
            views.setTextColor(R.id.ap_no_data, color(palette.secondary))
            views.setOnClickPendingIntent(R.id.ap_no_data, activityPendingIntent(context, WidgetIntents.openApp(context)))
        }
    }

    // The start/stop tile, same look as the Glance tile of the 1x1 widget.
    val model = serviceWidgetModel(serviceStarted)
    val accent = if (serviceStarted) palette.on else palette.accent
    views.setViewVisibility(R.id.ap_tile, if (settings.showServiceButton) View.VISIBLE else View.GONE)
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

/** Tap on one account of the all-accounts row; the per-account data URI keeps the PendingIntents apart. */
internal fun openAccountPendingIntent(context: Context, accountId: String): PendingIntent =
    PendingIntent.getActivity(context, 0, WidgetIntents.openAccount(context, accountId), immutableFlags())

private fun activityPendingIntent(context: Context, intent: Intent): PendingIntent =
    PendingIntent.getActivity(context, 0, intent, immutableFlags())

private fun immutableFlags() =
    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

/** An explicit broadcast per widget id and direction; the request code keeps all of them apart. */
internal fun navPendingIntent(context: Context, appWidgetId: Int, direction: Int): PendingIntent {
    val intent = Intent(context, ApWidgetNavReceiver::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .putExtra(ApWidgetNavReceiver.EXTRA_DIRECTION, direction)
    val requestCode = appWidgetId * 2 + if (direction > 0) 1 else 0
    return PendingIntent.getBroadcast(context, requestCode, intent, immutableFlags())
}

/**
 * The up/down buttons of one AP widget. It remembers the shown account id for that widget id only and moves
 * the flipper there with a partial update, so the fade plays and nothing else is redrawn.
 */
class ApWidgetNavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val direction = intent.getIntExtra(EXTRA_DIRECTION, 0)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || direction == 0) return
        val pending = goAsync()
        val app = context.applicationContext
        WidgetRefresh.scope.launch {
            try {
                ApWidgetRenderer.step(app, id, if (direction > 0) NEXT else PREVIOUS)
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DIRECTION = "io.github.fate_grand_automata.extra.AP_DIRECTION"
        const val NEXT = 1
        const val PREVIOUS = -1
    }
}
