package io.github.fate_grand_automata.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import io.github.fate_grand_automata.runner.ScriptRunnerService
import kotlinx.coroutines.Dispatchers
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

class ApWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Load before the first frame, so a new session never shows "no data" while the summaries load.
        val initial = runCatching { loadApWidgetState(context) }.getOrDefault(ApWidgetState.NoData)
        provideContent { ApWidgetSessionContent(initial) }
    }
}

/** Both state sources are observed inside the live composition. */
@Composable
internal fun ApWidgetSessionContent(initial: ApWidgetState) {
    val state by rememberApWidgetState(LocalContext.current, initial)
    ApWidgetContent(state, ScriptRunnerService.serviceStarted.value)
}

@Composable
internal fun rememberApWidgetState(
    context: Context,
    initial: ApWidgetState,
    load: suspend (Context) -> ApWidgetState = { loadApWidgetState(it) },
): State<ApWidgetState> {
    val revision by ApWidgetRefresh.revision.collectAsState()
    // Starts from the state loaded in provideGlance and reloads on every requested update.
    return produceState(initial, context, revision) {
        value = runCatching { load(context) }.getOrDefault(ApWidgetState.NoData)
    }
}
private val rowPadding = 8.dp
private val gap = 6.dp
private val tileWidth = 40.dp

@Composable
fun ApWidgetContent(state: ApWidgetState, serviceStarted: Boolean = false) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fields = apWidgetFields(size.width.value)
    val infoHeight = size.height - rowPadding * 2
    // One account fills a 1-row widget. Taller legacy widgets can show several accounts.
    val itemHeight = minOf(infoHeight, 60.dp)
    val infoWidth = size.width - rowPadding * 2 - tileWidth - gap
    Row(
        modifier = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(rowPadding),
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
            is ApWidgetState.Data -> LazyColumn(GlanceModifier.defaultWeight().fillMaxHeight()) {
                items(state.models, itemId = { apWidgetItemId(it.accountId) }) { model ->
                    ApInfo(model, fields, infoWidth, GlanceModifier.fillMaxWidth().height(itemHeight))
                }
            }
        }
        Spacer(GlanceModifier.width(gap))
        ServiceToggleTile(serviceStarted, GlanceModifier.width(tileWidth).fillMaxHeight())
    }
}

@Composable
private fun ApInfo(model: ApWidgetModel, fields: ApWidgetFields, width: Dp, modifier: GlanceModifier) {
    val context = LocalContext.current
    val texts = apWidgetTexts(context, model)
    Column(
        modifier = modifier.clickable(actionStartActivity(WidgetIntents.openAccount(context, model.accountId))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        }
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                texts.ap,
                style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = fields.apTextSize.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                texts.fullLine,
                style = TextStyle(color = ColorProvider(R.color.widget_full_at), fontSize = fields.fullTextSize.sp, fontWeight = FontWeight.Bold),
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
        ApWidgetRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ApWidgetRefresh.cancel(context)
    }
}