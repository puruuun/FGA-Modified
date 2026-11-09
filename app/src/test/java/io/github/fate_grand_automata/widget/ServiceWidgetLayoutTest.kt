package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-xhdpi")
@OptIn(androidx.glance.ExperimentalGlanceApi::class)
class ServiceWidgetLayoutTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun actualGlanceViewsFitBothTilesSideBySideOnlyAtTwoCells() = runBlocking {
        for (width in listOf(72, 119, 120, 140, 150, 160)) for (theme in WidgetTheme.entries) {
            val widget = object : GlanceAppWidget() {
                override val sizeMode = SizeMode.Exact
                override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
                    ServiceWidgetContent(false, WidgetSettings(theme, showLogButton = true), collecting = true, logAvailable = true)
                }
            }
            val size = DpSize(width.dp, 72.dp)
            val views = widget.runComposition(context, AppWidgetId(1), sizes = listOf(size)).first()
            val host = AppWidgetHostView(context)
            val root = views.apply(context, host)
            host.addView(root)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val density = context.resources.displayMetrics.density
            val w = (width * density).toInt()
            val h = (72 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            fun labels(view: View): List<TextView> = when (view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
                else -> emptyList()
            }
            val texts = labels(root).filter { it.text.toString() in listOf("ON", "OFF") }
            assertEquals(if (width >= SERVICE_LOG_TILE_MIN_WIDTH_DP) listOf("ON", "OFF") else listOf("OFF"), texts.map { it.text.toString() })
            val bounds = texts.map { text -> Rect(0, 0, text.width, text.height).also { (root as ViewGroup).offsetDescendantRectToMyCoords(text, it) } }
            bounds.forEach { assertTrue(it.width() > 0 && it.left >= 0 && it.right <= w, "$width $theme $it") }
            if (bounds.size == 2) assertTrue(bounds[0].right < bounds[1].left, "$width $theme $bounds")
        }
    }
}
