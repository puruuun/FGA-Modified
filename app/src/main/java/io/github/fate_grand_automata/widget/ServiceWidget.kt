package io.github.fate_grand_automata.widget

import android.content.Context
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
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService

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
    override val sizeMode = SizeMode.Single

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
    ServiceWidgetContent(ScriptRunnerService.serviceStarted.value, settings)
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
) {
    val context = LocalContext.current
    val model = serviceWidgetModel(started)
    val palette = widgetPalette(settings.theme)
    val action: Action = when (model.action) {
        ServiceWidgetAction.Start -> actionStartActivity(WidgetIntents.startService(context))
        ServiceWidgetAction.Stop -> actionRunCallback<StopServiceCallback>()
    }
    val accent = if (started) palette.on else palette.accent
    val background = if (asCard) widgetTileCardRes(settings) else palette.tileBackground
    Column(
        modifier = modifier
            .background(ImageProvider(background))
            .clickable(action),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.size(30.dp)
                .background(ImageProvider(if (started) palette.toggleCircleOn else palette.toggleCircle)),
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
                color = ColorProvider(if (started) palette.on else palette.secondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
fun ServiceWidgetContent(started: Boolean, settings: WidgetSettings = WidgetSettings()) {
    ServiceToggleTile(started, GlanceModifier.fillMaxSize(), settings, asCard = true)
}

/** Stops the service like the main-screen button does, without opening the UI. */
class StopServiceCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (ScriptRunnerService.serviceStarted.value) ScriptRunnerService.stopService(context)
    }
}

class ServiceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ServiceWidget()
}
