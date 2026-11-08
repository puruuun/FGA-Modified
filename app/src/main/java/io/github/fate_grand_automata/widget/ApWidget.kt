package io.github.fate_grand_automata.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
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
import java.util.Date

/** Strings shown by the AP widget; built apart from Glance so they can be unit tested. */
data class ApWidgetTexts(
    val name: String,
    val server: String,
    val apLine: String,
    /** "Full 9:41 PM (in 4h 35m)", or "AP full". */
    val fullLine: String,
    /** The compact variant of [fullLine]: no time left. */
    val fullLineShort: String,
    val dataLine: String,
)

fun apWidgetTexts(context: Context, model: ApWidgetModel): ApWidgetTexts {
    val clock = android.text.format.DateFormat.getTimeFormat(context)
    fun time(epochSeconds: Long) = clock.format(Date(epochSeconds * 1000))
    fun duration(minutes: Long) =
        if (minutes >= 60) context.getString(R.string.widget_duration_hm, minutes / 60, minutes % 60)
        else context.getString(R.string.widget_duration_m, minutes)
    val full = context.getString(R.string.widget_ap_full)
    val fullAt = model.fullAt
    return ApWidgetTexts(
        name = model.name,
        server = model.server,
        apLine = context.getString(R.string.widget_ap_value, model.ap.toString(), model.apMax.toString()),
        fullLine = if (fullAt == null) full
        else context.getString(R.string.widget_ap_full_at, time(fullAt), duration(model.minutesLeft ?: 0)),
        fullLineShort = if (fullAt == null) full else context.getString(R.string.widget_ap_full_at_short, time(fullAt)),
        dataLine = context.getString(R.string.widget_ap_data_time, time(model.dataTime)),
    )
}

/** Whether log-collection data may be read right now (switch on, Android 10+). */
fun captureEnabled(context: Context): Boolean =
    PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))
        .bool("capture_tracker_enabled", false).get()

suspend fun loadApWidgetState(context: Context, appWidgetId: Int, now: Long = System.currentTimeMillis() / 1000): ApWidgetState =
    withContext(Dispatchers.IO) {
        val bound = ApWidgetBindings(context).accountFor(appWidgetId)
        val enabled = captureEnabled(context)
        if (bound == null || !enabled || Build.VERSION.SDK_INT < 29) return@withContext ApWidgetState.NoData
        CaptureTracker.init(context)
        apWidgetState(bound, enabled, Build.VERSION.SDK_INT, CaptureTracker.accountSummaries(now), now)
    }

class ApWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val state = runCatching { loadApWidgetState(context, appWidgetId) }.getOrDefault(ApWidgetState.NoData)
        provideContent { ApWidgetContent(state) }
    }
}

@Composable
fun ApWidgetContent(state: ApWidgetState) {
    val context = LocalContext.current
    val size = LocalSize.current
    val base = GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background))
    when (state) {
        ApWidgetState.NoData -> Column(
            modifier = base.padding(8.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                context.getString(R.string.widget_ap_no_data),
                style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 13.sp),
            )
        }

        is ApWidgetState.Data -> {
            val texts = apWidgetTexts(context, state.model)
            val open: Action = actionStartActivity(WidgetIntents.openAccount(context, state.model.accountId))
            if (apWidgetLayout(size.width.value, size.height.value) == ApWidgetLayout.Compact) {
                Column(
                    modifier = base.padding(horizontal = 10.dp, vertical = 4.dp).clickable(open),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        texts.apLine,
                        style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Text(
                        texts.fullLineShort,
                        style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            } else {
                Column(
                    modifier = base.padding(horizontal = 12.dp, vertical = 8.dp).clickable(open),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            texts.name,
                            modifier = GlanceModifier.defaultWeight(),
                            style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            texts.server,
                            style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 11.sp),
                        )
                    }
                    Text(
                        texts.apLine,
                        style = TextStyle(color = ColorProvider(R.color.widget_on_surface), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(1.dp))
                    LinearProgressIndicator(
                        progress = state.model.fraction,
                        modifier = GlanceModifier.fillMaxWidth(),
                        color = ColorProvider(R.color.widget_accent),
                        backgroundColor = ColorProvider(R.color.widget_track),
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    Text(
                        texts.fullLine,
                        style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 11.sp),
                        maxLines = 1,
                    )
                    Text(
                        texts.dataLine,
                        style = TextStyle(color = ColorProvider(R.color.widget_secondary), fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
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
