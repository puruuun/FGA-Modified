package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.View
import android.widget.AdapterViewFlipper
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko-xhdpi")
class WidgetLogLayoutTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = context.resources.displayMetrics.density
    private val model = ApWidgetModel("1", "칼데아_마스터", 142, 142, 1_800_000_000, 1_799_999_400)
    private val state = ApWidgetState.Data(listOf(model, model.copy(accountId = "2")))
    private fun measure(view: View, width: Int, height: Int = 66) {
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun realHostPreservesOrderInsetsAndNonOverlappingTextAtEveryWidthAndTypeStep() {
        android.provider.Settings.System.putString(context.contentResolver, android.provider.Settings.System.TIME_12_24, "12")
        val widths = (180..378).toList()
        for (theme in WidgetTheme.entries) for (enabled in listOf(false, true)) for (width in widths) {
            val host = AppWidgetHostView(context)
            val views = buildApWidgetViews(context, 7, state, WidgetSettings(theme, showLogButton = enabled), false, width,
                now = 1_800_000_000, logAvailable = enabled)
            val root = views.apply(context, host)
            host.addView(root)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            measure(host, width)
            val flipper = root.findViewById<AdapterViewFlipper>(R.id.ap_flipper)
            val info = flipper.parent as View
            val nav = root.findViewById<View>(R.id.ap_nav)
            val log = root.findViewById<View>(R.id.ap_log_tile)
            val service = root.findViewById<View>(R.id.ap_tile)
            val showLog = enabled && width >= AP_LOG_TILE_MIN_WIDTH_DP
            assertEquals(if (showLog) View.VISIBLE else View.GONE, log.visibility, "$width $theme")
            assertTrue(info.right <= nav.left)
            assertTrue(nav.right <= if (showLog) log.left else service.left)
            if (showLog) assertTrue(log.right <= service.left)
            assertEquals(22f, (root.width - service.right) / density, 0.1f)
            val item = flipper.adapter.getView(0, null, flipper)
            measure(item, (flipper.width / density).toInt(), 50)
            assertEquals(22f, (info.left + flipper.left + item.paddingLeft) / density, 0.1f)
            assertTrue((nav.left - info.right) / density >= 10)
            val ap = item.findViewById<TextView>(R.id.ap_item_ap)
            val full = item.findViewById<TextView>(R.id.ap_item_full)
            assertTrue(ap.right <= full.left, "AP/time overlap at $width $theme log=$enabled")
            for (text in listOf(ap, full)) {
                assertTrue(text.paint.measureText(text.text.toString()) <= text.width + 1,
                    "Clipped ${text.text} at $width $theme log=$enabled, ${text.width}")
            }
        }
    }

    @Test fun logTileSendsItsOwnBroadcastAndUsesAmberWithReadableTextInBothThemes() {
        for (theme in WidgetTheme.entries) for (collecting in listOf(false, true)) {
            val host = AppWidgetHostView(context)
            val root = buildApWidgetViews(context, 7, state, WidgetSettings(theme, showLogButton = true), false, 272,
                collecting = collecting, logAvailable = true).apply(context, host)
            root.findViewById<View>(R.id.ap_log_tile).performClick()
            val sent = shadowOf(context as android.app.Application).broadcastIntents.last()
            assertEquals(ToggleCaptureReceiver::class.java.name, sent.component!!.className)
            val label = root.findViewById<TextView>(R.id.ap_log_label)
            assertEquals(if (collecting) "ON" else "OFF", label.text.toString())
            if (collecting) {
                assertEquals(android.graphics.Color.parseColor("#1B1B1B"), label.currentTextColor)
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(label.currentTextColor,
                    android.graphics.Color.parseColor("#FFD54F")) >= 7)
            }
        }
    }

    @Test fun oneCellHasOnlyTheServiceTileEvenWhenLogIsAvailable() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(72.dp, 72.dp))
        provideComposable { ServiceWidgetContent(false, logAvailable = true) }
        onAllNodes(hasText("OFF")).assertCountEquals(1)
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun twoCellsHaveLogThenServiceWhenEnabled() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(150.dp, 72.dp))
        provideComposable { ServiceWidgetContent(false, collecting = true, logAvailable = true) }
        onNode(hasText("ON")).assertExists()
        onNode(hasText("OFF")).assertExists()
        onNode(hasStartActivityClickAction(WidgetIntents.startService(context))).assertExists()
    }

    @Test fun twoCellsKeepOnlyServiceWhenDisabled() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(150.dp, 72.dp))
        provideComposable { ServiceWidgetContent(false, logAvailable = false) }
        onAllNodes(hasText("OFF")).assertCountEquals(1)
    }
}
