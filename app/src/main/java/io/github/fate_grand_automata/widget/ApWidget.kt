package io.github.fate_grand_automata.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.size
import io.github.fate_grand_automata.runner.ScriptRunnerService
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.PrefMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether log-collection data may be read right now (switch on, Android 10+). */
fun captureEnabled(context: Context): Boolean =
    PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))
        .bool("capture_tracker_enabled", false).get()

suspend fun loadApWidgetState(context: Context, appWidgetId: Int, now: Long = System.currentTimeMillis() / 1000): ApWidgetState =
    withContext(Dispatchers.IO) {
        val bound = ApWidgetBindings(context).accountFor(appWidgetId)
        val enabled = captureEnabled(context)
        if (bound == null || !enabled || Build.VERSION.SDK_INT < 29) return@withContext ApWidgetState.NoData
        CaptureTracker.initSummaries(context)
        apWidgetState(bound, enabled, Build.VERSION.SDK_INT, CaptureTracker.accountSummaries(now), now)
    }

class ApWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = runCatching { loadApWidgetState(context, appWidgetId) }.getOrDefault(ApWidgetState.NoData)
        val started = ScriptRunnerService.serviceStarted.value
        provideContent { ApWidgetContent(state, started) }
    }
}

/** Re-renders every AP widget from the stored summaries; nothing is fetched. */
class RefreshApWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withContext(Dispatchers.IO) { CaptureTracker.initSummaries(context) }
        ApWidget().updateAll(context)
    }
}

private val rowPadding = 8.dp
private val gap = 6.dp
private val maxRowHeight = 76.dp

@Composable
fun ApWidgetContent(state: ApWidgetState, serviceStarted: Boolean = false) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fields = apWidgetFields(size.width.value)
    val tileWidth = if (fields.showRefresh) 60.dp else 48.dp
    val rowHeight = if (size.height > maxRowHeight) maxRowHeight else size.height
    Box(
        modifier = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(rowHeight).padding(rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                ApWidgetState.NoData -> Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                        .clickable(actionStartActivity(WidgetIntents.openApp(context))),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        context.getString(R.string.widget_ap_no_data),
                        style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 13.sp),
                        maxLines = 2,
                    )
                }

                is ApWidgetState.Data -> {
                    val infoWidth = size.width - rowPadding * 2 - tileWidth - gap
                    ApInfo(state.model, fields, infoWidth, GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
            Spacer(GlanceModifier.width(gap))
            ServiceToggleTile(serviceStarted, GlanceModifier.width(tileWidth).fillMaxHeight())
        }
    }
}

@Composable
private fun ApInfo(model: ApWidgetModel, fields: ApWidgetFields, width: Dp, modifier: GlanceModifier) {
    val context = LocalContext.current
    val texts = apWidgetTexts(context, model)
    val open: Action = actionStartActivity(WidgetIntents.openAccount(context, model.accountId))
    Column(modifier = modifier.clickable(open), verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier.background(ImageProvider(R.drawable.widget_chip_background)).padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    texts.server,
                    style = TextStyle(color = ColorProvider(R.color.widget_chip_text), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.width(5.dp))
            Text(
                texts.name,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                texts.age,
                style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 10.sp),
                maxLines = 1,
            )
            if (fields.showRefresh) {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = context.getString(R.string.widget_refresh),
                    modifier = GlanceModifier.size(18.dp).padding(2.dp).clickable(actionRunCallback<RefreshApWidgetCallback>()),
                    colorFilter = ColorFilter.tint(ColorProvider(R.color.widget_secondary)),
                )
            }
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (fields.showApLabel) {
                Text(
                    texts.apLabel,
                    style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(
                texts.ap,
                style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(3.dp))
            Text(
                texts.apMax,
                style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = if (fields.showRefresh) 12.sp else 11.sp),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            Spacer(GlanceModifier.defaultWeight())
            Text(
                when {
                    fields.showRemaining -> texts.fullLine
                    fields.showFullWord -> texts.fullLineShort
                    else -> texts.fullLineTime
                },
                style = TextStyle(color = ColorProvider(R.color.widget_full_at), fontSize = if (fields.showRefresh) 11.sp else 10.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        val fill = width * model.fraction
        Box(modifier = GlanceModifier.fillMaxWidth().height(6.dp).background(ImageProvider(R.drawable.widget_bar_track))) {
            if (fill >= 1.dp) {
                Box(modifier = GlanceModifier.width(fill).fillMaxHeight().background(ImageProvider(R.drawable.widget_bar_fill))) {}
            }
        }
    }
}

class ApWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ApWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ApWidgetRefresh.schedule(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // WorkManager keeps the job over reboots; KEEP makes this a no-op when it already exists.
        ApWidgetRefresh.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val bindings = ApWidgetBindings(context)
        appWidgetIds.forEach(bindings::remove)
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ApWidgetRefresh.cancel(context)
    }
}
