package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
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
    val context = LocalContext.current
    val state by rememberApWidgetState(context, initial)
    // The theme and opacity are re-read on every requested update, like the account data.
    val revision by ApWidgetRefresh.revision.collectAsState()
    val settings = remember(revision) { loadWidgetSettings(context) }
    ApWidgetContent(state, ScriptRunnerService.serviceStarted.value, settings)
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

/** Matches the card's corner radius, so the text does not crowd the rounded edge. Left side only. */
internal val infoStartPadding = 14.dp

/** Test tag of the account stack, the only place the account cards live. */
internal const val AP_STACK_TAG = "ap_stack"

@Composable
private fun rememberAppWidgetId(): Int {
    val context = LocalContext.current
    val glanceId = LocalGlanceId.current
    return remember(glanceId) {
        runCatching { GlanceAppWidgetManager(context).getAppWidgetId(glanceId) }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
    }
}

@Composable
fun ApWidgetContent(state: ApWidgetState, serviceStarted: Boolean = false, settings: WidgetSettings = WidgetSettings()) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fields = apWidgetFields(size.width.value)
    val palette = widgetPalette(settings.theme)
    Row(
        modifier = GlanceModifier.fillMaxSize().background(ImageProvider(widgetCardRes(settings))).padding(rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            ApWidgetState.NoData -> Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                    .padding(start = infoStartPadding)
                    .clickable(actionStartActivity(WidgetIntents.openApp(context))),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    context.getString(R.string.widget_ap_no_data),
                    style = TextStyle(color = ColorProvider(palette.secondary), fontSize = 13.sp),
                    maxLines = 2,
                )
            }
            is ApWidgetState.Data -> {
                val appWidgetId = rememberAppWidgetId()
                // A StackView snaps to exactly one card per swipe and has no scrollbar. Glance has no such
                // element, so the cards are plain RemoteViews hosted through AndroidRemoteViews.
                val stack = remember(state, settings.theme, fields, appWidgetId) {
                    buildApStackViews(context, appWidgetId, state.models, palette, fields)
                }
                AndroidRemoteViews(
                    remoteViews = stack,
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                        .padding(start = infoStartPadding)
                        .semantics { testTag = AP_STACK_TAG },
                )
            }
        }
        Spacer(GlanceModifier.width(gap))
        ServiceToggleTile(serviceStarted, GlanceModifier.width(tileWidth).fillMaxHeight(), settings)
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
