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
import io.github.fate_grand_automata.prefs.core.WidgetTheme

/** Renders the real Glance widgets (as RemoteViews) in light and dark, for the owner's review. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
@OptIn(androidx.glance.ExperimentalGlanceApi::class)
class WidgetScreenshotTest(private val light: Boolean) {
    private val settings get() = WidgetSettings(if (light) WidgetTheme.Light else WidgetTheme.Dark, 0)
    private val half get() = settings.copy(transparencyPercent = 50)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now get() = System.currentTimeMillis() / 1000

    private class Fixed(override val sizeMode: SizeMode, private val content: @Composable () -> Unit) : GlanceAppWidget() {
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent(content)
    }

    private fun shoot(name: String, size: DpSize, backdrop: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("ko-w480dp-h900dp-notnight-xhdpi")
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
            if (v is android.widget.StackView) { println("WIDGET STACK adapter=${v.adapter} count=${v.adapter?.count} children=${v.childCount} width=${v.width} height=${v.height} pad=${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom}"); for (i in 0 until v.childCount) { val c = v.getChildAt(i); println("WIDGET STACK child $i l=${c.left} t=${c.top} w=${c.width} h=${c.height} sx=${c.scaleX} sy=${c.scaleY} a=${c.alpha}") } }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i))
        }
        dump(view)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // A see-through card needs something behind it to be judged: a blue-grey wallpaper stand-in.
        if (backdrop) canvas.drawColor(android.graphics.Color.parseColor("#7C93B3"))
        view.draw(canvas)
        val file = java.io.File("build/outputs/roborazzi/widgets/${name}${if (light) "_light" else ""}.png")
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

    @Test fun serviceStopped() = shoot("service_stopped_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = false, settings = settings) }

    @Test fun serviceRunning() = shoot("service_running_1x1", DpSize(72.dp, 72.dp)) { ServiceWidgetContent(started = true, settings = settings) }

    @Test fun serviceStoppedHalfOpacity() = shoot("service_stopped_1x1_opacity50", DpSize(72.dp, 72.dp), backdrop = true) { ServiceWidgetContent(started = false, settings = half) }

    private fun data(m: ApWidgetModel = model) = ApWidgetState.Data(listOf(m))

    @Test fun ap4x1() = shootAp("ap_4x1", wide, data())
    @Test fun ap4x1Running() = shootAp("ap_4x1_on", wide, data(), started = true)
    @Test fun ap3x1() = shootAp("ap_3x1", narrow, data())
    @Test fun apFull4x1() = shootAp("ap_full_ap_4x1", wide, data(fullAp))
    @Test fun apFull3x1() = shootAp("ap_full_ap_3x1", narrow, data(fullAp))
    @Test fun apNoData() = shootAp("ap_no_data_4x1", wide, ApWidgetState.NoData)
    @Test fun apAt180() = shootAp("ap_width_180", DpSize(180.dp, 66.dp), data())
    @Test fun apFullAt180() = shootAp("ap_full_ap_width_180", DpSize(180.dp, 66.dp), data(fullAp))
    @Test fun apAt230() = shootAp("ap_width_230", DpSize(230.dp, 66.dp), data())
    @Test fun apAt255() = shootAp("ap_width_255", DpSize(255.dp, 66.dp), data())
    @Test fun apAt268() = shootAp("ap_width_268", DpSize(268.dp, 66.dp), data())
    @Test fun ap4x1Several() = shootAp("ap_4x1_several", wide, several)
    @Test fun ap3x1Several() = shootAp("ap_3x1_several", narrow, several)
    @Test fun ap180Several() = shootAp("ap_width_180_several", DpSize(180.dp, 66.dp), several)
    @Test fun ap230Several() = shootAp("ap_width_230_several", DpSize(230.dp, 66.dp), several)
    @Test fun ap183Several() = shootAp("ap_width_183_several", DpSize(183.dp, 74.dp), several)
    @Test fun apAlreadyPlaced3x2() = shootAp("ap_3x2_legacy", DpSize(250.dp, 110.dp), data())
    @Test fun ap4x1HalfOpacity() = shootAp("ap_4x1_opacity50", wide, several, settings = half, backdrop = true)
    @Test fun ap3x1HalfOpacity() = shootAp("ap_3x1_opacity50", narrow, several, settings = half, backdrop = true)
    @Test fun ap4x1FullyTransparent() = shootAp("ap_4x1_opacity100", wide, data(), settings = settings.copy(transparencyPercent = 100), backdrop = true)

    /** The top-level RemoteViews, applied into an AppWidgetHostView like the launcher does. */
    private fun shootAp(
        name: String, size: DpSize, state: ApWidgetState, settings: WidgetSettings = this.settings,
        started: Boolean = false, backdrop: Boolean = false,
    ) {
        RuntimeEnvironment.setQualifiers("ko-w480dp-h900dp-notnight-xhdpi")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        android.provider.Settings.System.putString(context.contentResolver, android.provider.Settings.System.TIME_12_24, "12")
        val views = buildApWidgetViews(context, 1, state, settings, started, size.width.value.toInt(), now = now)
        val density = context.resources.displayMetrics.density
        val parent = android.appwidget.AppWidgetHostView(context)
        parent.addView(views.apply(context, parent))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (backdrop) canvas.drawColor(android.graphics.Color.parseColor("#7C93B3"))
        parent.draw(canvas)
        val file = java.io.File("build/outputs/roborazzi/widgets/${name}${if (light) "_light" else ""}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One stack card on its own, in case Robolectric cannot lay out the StackView's children. */
    @Test fun singleCard() {
        RuntimeEnvironment.setQualifiers("ko-w480dp-h900dp-notnight-xhdpi")
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        val views = buildApItemViews(context, apItemContent(context, model), widgetPalette(settings.theme), ApWidgetFields(18, 11))
        val density = context.resources.displayMetrics.density
        val w = (200 * density).toInt()
        val h = (50 * density).toInt()
        val parent = android.widget.FrameLayout(context)
        val view = views.apply(context, parent)
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor(if (light) "#FFFFFF" else "#0E1626"))
        view.draw(canvas)
        val file = java.io.File("build/outputs/roborazzi/widgets/ap_single_card${if (light) "_light" else ""}.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "light={0}")
        fun params() = listOf(false, true)
    }
}
