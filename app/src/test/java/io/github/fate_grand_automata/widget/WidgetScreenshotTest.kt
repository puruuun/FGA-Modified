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
    private val now get() = System.currentTimeMillis() / 1000

    private class Fixed(override val sizeMode: SizeMode, private val content: @Composable () -> Unit) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent(content)
    }

    private fun shoot(name: String, size: DpSize, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("ko-w480dp-h900dp-${if (dark) "night" else "notnight"}-xhdpi")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        android.provider.Settings.System.putString(context.contentResolver, android.provider.Settings.System.TIME_12_24, "12")
        val widget = Fixed(SizeMode.Exact, content)
        val views = runBlocking { widget.runComposition(context, AppWidgetId(1), sizes = listOf(size)).first() }
        val density = context.resources.displayMetrics.density
        val parent = android.appwidget.AppWidgetHostView(context)
        val view = views.apply(context, parent)
        parent.addView(view)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        fun dump(v: View) {
            if (v is android.widget.ListView) println("WIDGET LIST adapter=${v.adapter} count=${v.count} children=${v.childCount} width=${v.width} height=${v.height}")
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i))
        }
        dump(view)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = java.io.File("build/outputs/roborazzi/widgets/${name}${if (dark) "_dark" else ""}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val model = ApWidgetModel(
        accountId = "7001", name = "칼데아_마스터", ap = 57, apMax = 142,
        fullAt = java.time.Instant.parse("2026-11-08T11:23:00Z").epochSecond, dataTime = now - 5,
    )

    private val wide = DpSize(272.dp, 66.dp)
    private val narrow = DpSize(200.dp, 66.dp)
    private val fullAp get() = model.copy(ap = 142, fullAt = null)
    private val several get() = ApWidgetState.Data(listOf(model, model.copy(accountId = "7002", name = "두 번째 계정"), model.copy(accountId = "7003", name = "세 번째 계정")))

    @Test fun serviceStopped() = shoot("service_stopped_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = false) }

    @Test fun serviceRunning() = shoot("service_running_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = true) }

    @Test fun ap4x1() = shoot("ap_4x1", wide) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun ap4x1Running() = shoot("ap_4x1_on", wide) { ApWidgetContent(ApWidgetState.Data(listOf(model)), serviceStarted = true) }

    @Test fun ap3x1() = shoot("ap_3x1", narrow) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun apFull4x1() = shoot("ap_full_ap_4x1", wide) { ApWidgetContent(ApWidgetState.Data(listOf(fullAp))) }

    @Test fun apFull3x1() = shoot("ap_full_ap_3x1", narrow) { ApWidgetContent(ApWidgetState.Data(listOf(fullAp))) }

    @Test fun apNoData() = shoot("ap_no_data_4x1", wide) { ApWidgetContent(ApWidgetState.NoData) }

    @Test fun apAt180() = shoot("ap_width_180", DpSize(180.dp, 66.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun apFullAt180() = shoot("ap_full_ap_width_180", DpSize(180.dp, 66.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(fullAp))) }

    @Test fun apAt230() = shoot("ap_width_230", DpSize(230.dp, 66.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun apAt255() = shoot("ap_width_255", DpSize(255.dp, 66.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun apAt268() = shoot("ap_width_268", DpSize(268.dp, 66.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    @Test fun ap4x1Several() = shoot("ap_4x1_several", wide) { ApWidgetContent(several) }

    @Test fun ap3x1Several() = shoot("ap_3x1_several", narrow) { ApWidgetContent(several) }

    @Test fun ap180Several() = shoot("ap_width_180_several", DpSize(180.dp, 66.dp)) { ApWidgetContent(several) }

    @Test fun apAlreadyPlaced3x2Several() = shoot("ap_3x2_legacy_several", DpSize(250.dp, 140.dp)) { ApWidgetContent(several) }

    @Test fun apAlreadyPlaced3x2() = shoot("ap_3x2_legacy", DpSize(250.dp, 110.dp)) { ApWidgetContent(ApWidgetState.Data(listOf(model))) }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(false, true)
    }
}
