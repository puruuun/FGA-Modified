package io.github.fate_grand_automata.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.width
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.capture.CaptureTracker

enum class ServiceWidgetAction { Start, Stop }

data class ServiceWidgetModel(
    val labelRes: Int,
    val descriptionRes: Int,
    val iconRes: Int,
    val action: ServiceWidgetAction,
)

/** The toggle is a pure function of [ScriptRunnerService.serviceStarted]. */
fun serviceWidgetModel(started: Boolean) =
    if (started) ServiceWidgetModel(R.string.widget_service_on, R.string.widget_service_stop, R.drawable.ic_stop, ServiceWidgetAction.Stop)
    else ServiceWidgetModel(R.string.widget_service_off, R.string.widget_service_start, R.drawable.ic_play, ServiceWidgetAction.Start)

class ServiceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { ServiceWidgetSessionContent() }
    }

    companion object {
        /** Called when the service starts or stops; updates every placed service and AP widget (both carry the toggle). */
        fun requestUpdate(context: Context) {
            WidgetRefresh.requestUpdate(context)
        }
    }
}

/** Re-reads the theme and opacity on every requested update, so a settings change redraws a live session. */
@Composable
internal fun ServiceWidgetSessionContent() {
    val context = LocalContext.current
    val revision by WidgetRefresh.revision.collectAsState()
    val settings = remember(revision) { loadWidgetSettings(context) }
    val collecting by CaptureTracker.running.collectAsState()
    ServiceWidgetContent(
        ScriptRunnerService.serviceStarted.value, settings, collecting,
        widgetLogButtonAvailable(context, settings),
    )
}

/**
 * The start/stop tile: a play or stop button in a circle with ON/OFF below. Shared by both widgets.
 * The tile stays fully visible; with [asCard] (the 1x1 widget, where the tile is the whole card) its
 * background follows the card opacity and the icon and label stay opaque.
 */
@Composable
fun ServiceToggleTile(
    started: Boolean,
    modifier: GlanceModifier = GlanceModifier,
    settings: WidgetSettings = WidgetSettings(),
    asCard: Boolean = false,
    capture: Boolean = false,
) {
    val context = LocalContext.current
    val model = if (capture) serviceWidgetModel(started).copy(
        iconRes = R.drawable.ic_widget_log,
        descriptionRes = if (started) R.string.capture_main_stop else R.string.capture_main_start,
    ) else serviceWidgetModel(started)
    val palette = widgetPalette(settings.theme)
    val action: Action = if (capture) {
        actionSendBroadcast(Intent(context, ToggleCaptureReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
    } else when (model.action) {
        ServiceWidgetAction.Start -> actionStartActivity(WidgetIntents.startService(context))
        ServiceWidgetAction.Stop -> actionRunCallback<StopServiceCallback>()
    }
    val accent = if (capture && started) R.color.widget_log_on_foreground else if (started) palette.on else palette.accent
    val background = if (capture && started) R.drawable.widget_log_tile_on else if (asCard) widgetTileCardRes(settings) else palette.tileBackground
    val circle = if (capture && started) R.drawable.widget_log_circle_on else if (started) palette.toggleCircleOn else palette.toggleCircle
    val labelColor = if (capture && started) R.color.widget_log_on_foreground else if (started) palette.on else palette.secondary
    Column(
        modifier = modifier
            .background(ImageProvider(background))
            .clickable(action),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(30.dp)
                .background(ImageProvider(circle)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(model.iconRes),
                contentDescription = context.getString(model.descriptionRes),
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(ColorProvider(accent)),
            )
        }
        Text(
            text = context.getString(model.labelRes),
            style = TextStyle(
                color = ColorProvider(labelColor),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
fun ServiceWidgetContent(
    started: Boolean,
    settings: WidgetSettings = WidgetSettings(),
    collecting: Boolean = false,
    logAvailable: Boolean = false,
) {
    val showLog = widgetLogTileVisible(logAvailable, LocalSize.current.width.value.toInt(), SERVICE_LOG_TILE_MIN_WIDTH_DP)
    if (showLog) {
        Row(GlanceModifier.fillMaxSize()) {
            ServiceToggleTile(collecting, GlanceModifier.defaultWeight().fillMaxHeight(), settings, asCard = true, capture = true)
            Spacer(GlanceModifier.width(4.dp))
            ServiceToggleTile(started, GlanceModifier.defaultWeight().fillMaxHeight(), settings, asCard = true)
        }
    } else ServiceToggleTile(started, GlanceModifier.fillMaxSize(), settings, asCard = true)
}

/** Stops the service like the main-screen button does, without opening the UI. Only when it is running. */
internal fun stopServiceIfStarted(context: Context) {
    if (ScriptRunnerService.serviceStarted.value) ScriptRunnerService.stopService(context)
}

/** Stops the service from the 1x1 Glance widget. */
class StopServiceCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        stopServiceIfStarted(context)
    }
}

/** Stops the service from the stop tile of the plain-RemoteViews AP widget (not exported, explicit broadcast). */
class StopServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        stopServiceIfStarted(context.applicationContext)
    }
}

class ServiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServiceWidget()
}
