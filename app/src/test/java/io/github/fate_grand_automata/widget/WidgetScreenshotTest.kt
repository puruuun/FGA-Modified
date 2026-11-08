package io.github.fate_grand_automata.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.runComposition
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the real Glance widgets (as RemoteViews) in light and dark, for the owner's review. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(androidx.glance.ExperimentalGlanceApi::class)
class WidgetScreenshotTest(private val dark: Boolean) {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000L

    private class Fixed(override val sizeMode: SizeMode, private val content: @Composable () -> Unit) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent(content)
    }

    private fun shoot(name: String, size: DpSize, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("ko-w480dp-h900dp-${if (dark) "night" else "notnight"}-xhdpi")
        val widget = Fixed(SizeMode.Exact, content)
        val views = runBlocking { widget.runComposition(context, AppWidgetId(1), sizes = listOf(size)).first() }
        val density = context.resources.displayMetrics.density
        val parent = FrameLayout(context)
        val view = views.apply(context, parent)
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/outputs/roborazzi/widgets/${name}${if (dark) "_dark" else ""}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val model = ApWidgetModel(
        accountId = "7001", name = "칼데아_마스터", server = "KR", ap = 57, apMax = 142,
        fullAt = now + (3 * 60 + 23) * 60, minutesLeft = 3 * 60 + 23, dataTime = now - 5,
    )

    private val wide = DpSize(272.dp, 66.dp)
    private val narrow = DpSize(200.dp, 66.dp)
    private val fullAp get() = model.copy(ap = 144, fullAt = null, minutesLeft = null)

    @Test fun serviceStopped() = shoot("service_stopped_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = false) }

    @Test fun serviceRunning() = shoot("service_running_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = true) }

    @Test fun ap4x1() = shoot("ap_4x1", wide) { ApWidgetContent(ApWidgetState.Data(model)) }

    @Test fun ap4x1Running() = shoot("ap_4x1_on", wide) { ApWidgetContent(ApWidgetState.Data(model), serviceStarted = true) }

    @Test fun ap3x1() = shoot("ap_3x1", narrow) { ApWidgetContent(ApWidgetState.Data(model)) }

    @Test fun apFull4x1() = shoot("ap_full_ap_4x1", wide) { ApWidgetContent(ApWidgetState.Data(fullAp)) }

    @Test fun apFull3x1() = shoot("ap_full_ap_3x1", narrow) { ApWidgetContent(ApWidgetState.Data(fullAp)) }

    @Test fun apNoData() = shoot("ap_no_data_4x1", wide) { ApWidgetContent(ApWidgetState.NoData) }

    @Test fun apAlreadyPlaced3x2() = shoot("ap_3x2_legacy", DpSize(250.dp, 110.dp)) { ApWidgetContent(ApWidgetState.Data(model)) }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(false, true)
    }
}
