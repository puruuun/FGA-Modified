package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ServiceWidgetAction { Start, Stop }

data class ServiceWidgetModel(val labelRes: Int, val action: ServiceWidgetAction)

/** The service widget is a pure function of [ScriptRunnerService.serviceStarted]. */
fun serviceWidgetModel(started: Boolean) =
    if (started) ServiceWidgetModel(R.string.widget_service_stop, ServiceWidgetAction.Stop)
    else ServiceWidgetModel(R.string.widget_service_start, ServiceWidgetAction.Start)

class ServiceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val started = ScriptRunnerService.serviceStarted.value
        provideContent { ServiceWidgetContent(started) }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Called when the service starts or stops; updates every placed instance. */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            scope.launch { runCatching { ServiceWidget().updateAll(app) } }
        }
    }
}

@Composable
fun ServiceWidgetContent(started: Boolean) {
    val context = LocalContext.current
    val model = serviceWidgetModel(started)
    val action: Action = when (model.action) {
        ServiceWidgetAction.Start -> actionStartActivity(WidgetIntents.startService(context))
        ServiceWidgetAction.Stop -> actionRunCallback<StopServiceCallback>()
    }
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background))
            .padding(4.dp)
            .clickable(action),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = GlanceModifier.size(36.dp),
        )
        Text(
            text = context.getString(model.labelRes),
            style = TextStyle(
                color = ColorProvider(if (started) R.color.widget_stop else R.color.widget_on_surface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
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
