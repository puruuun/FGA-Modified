package io.github.fate_grand_automata.widget

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterViewFlipper
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import io.github.fate_grand_automata.runner.ScriptRunnerService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds the top-level AP widget RemoteViews and applies them inside a real AppWidgetHostView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "ko")
class ApWidgetViewsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000L
    private val originalLoader = ApWidgetRenderer.loader

    @After fun restore() {
        ApWidgetRenderer.loader = originalLoader
        (ScriptRunnerService.serviceStarted as androidx.compose.runtime.MutableState<Boolean>).value = false
    }

    private fun model(id: String) = ApWidgetModel(
        accountId = id, name = "계정 $id", ap = 57, apMax = 142, fullAt = now + 3600, dataTime = now - 600,
    )
    private fun data(n: Int) = ApWidgetState.Data((1..n).map { model("700$it") })

    private fun host(views: RemoteViews): AppWidgetHostView {
        val host = AppWidgetHostView(context)
        host.addView(views.apply(context, host))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return host
    }

    private fun find(v: View): AdapterViewFlipper? =
        if (v is AdapterViewFlipper) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }

    private fun build(state: ApWidgetState, theme: WidgetTheme = WidgetTheme.Dark, started: Boolean = false, width: Int = 250) =
        buildApWidgetViews(context, 21, state, WidgetSettings(theme, 0), started, width, now = now)

    @Test fun oneAndThreeAccountsFillTheTopLevelStackInBothThemes() {
        for (theme in WidgetTheme.entries) for (n in listOf(1, 3)) {
            val host = host(build(data(n), theme))
            val stack = assertNotNull(find(host), "$theme $n")
            assertEquals(n, stack.adapter.count)
            assertTrue(stack.adapter.hasStableIds())
            assertEquals((1..n).map { apWidgetItemId("700$it") }, (0 until n).map { stack.adapter.getItemId(it) })
            assertEquals(View.VISIBLE, stack.visibility)
            assertEquals(View.GONE, host.findViewById<View>(R.id.ap_no_data).visibility)
        }
    }

    @Test fun noDataShowsTheMessageAndNoStack() {
        val host = host(build(ApWidgetState.NoData))
        val message = host.findViewById<TextView>(R.id.ap_no_data)
        assertEquals(View.VISIBLE, message.visibility)
        assertEquals("계정 데이터 없음", message.text.toString())
        assertEquals(View.GONE, host.findViewById<View>(R.id.ap_flipper).visibility)
        assertNull(find(host)?.adapter)
        // The message opens the app.
        message.performClick()
        assertEquals(
            io.github.fate_grand_automata.ui.main.MainActivity::class.java.name,
            shadowOf(context).nextStartedActivity.component!!.className,
        )
    }

    @Test fun tileFollowsTheServiceStateAndTheTheme() {
        for (theme in WidgetTheme.entries) {
            val palette = widgetPalette(theme)
            val off = host(build(data(1), theme, started = false))
            assertEquals("OFF", off.findViewById<TextView>(R.id.ap_tile_label).text.toString())
            assertEquals(ContextCompat.getColor(context, palette.secondary), off.findViewById<TextView>(R.id.ap_tile_label).currentTextColor)
            val on = host(build(data(1), theme, started = true))
            assertEquals("ON", on.findViewById<TextView>(R.id.ap_tile_label).text.toString())
            assertEquals(ContextCompat.getColor(context, palette.on), on.findViewById<TextView>(R.id.ap_tile_label).currentTextColor)
        }
    }

    @Test fun startTileOpensTheStartFlowEvenWithTheStackTemplateSet() {
        val host = host(build(data(3), started = false))
        host.findViewById<View>(R.id.ap_tile).performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(WidgetIntents.startService(context).action, intent.action)
        assertTrue(intent.getBooleanExtra(WidgetIntents.EXTRA_WIDGET_START, false))
    }

    @Test fun stopTileSendsAnExplicitBroadcastAndNeverOpensTheUi() {
        val host = host(build(data(3), started = true))
        host.findViewById<View>(R.id.ap_tile).performClick()
        val sent = shadowOf(context).broadcastIntents
        assertEquals(StopServiceReceiver::class.java.name, sent.single().component!!.className)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun stopReceiverStopsOnlyARunningService() {
        val flag = ScriptRunnerService.serviceStarted as androidx.compose.runtime.MutableState<Boolean>
        flag.value = false
        StopServiceReceiver().onReceive(context, android.content.Intent())
        assertNull(shadowOf(context).nextStoppedService)
        flag.value = true
        StopServiceReceiver().onReceive(context, android.content.Intent())
        assertNotNull(shadowOf(context).nextStoppedService)
    }

    @Test fun tilePendingIntentsAreImmutableAndTheTemplateIsMutable() {
        val stop = shadowOf(tilePendingIntent(context, ServiceWidgetAction.Stop))
        assertTrue(stop.isBroadcastIntent)
        assertTrue(stop.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        val start = shadowOf(tilePendingIntent(context, ServiceWidgetAction.Start))
        assertTrue(start.isActivityIntent)
        assertTrue(start.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadowOf(openAccountTemplate(context)).flags and android.app.PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test fun textSizesFollowTheReportedWidth() {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        assertEquals(ApWidgetRenderer.DEFAULT_WIDTH_DP, ApWidgetRenderer.widthDp(context, manager, id))
        manager.updateAppWidgetOptions(id, Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) })
        assertEquals(180, ApWidgetRenderer.widthDp(context, manager, id))
        fun apSize(width: Int): Float {
            val stack = assertNotNull(find(host(build(data(1), width = width))))
            val item = stack.adapter.getView(0, null, stack)
            return item.findViewById<TextView>(R.id.ap_item_ap).textSize / context.resources.displayMetrics.scaledDensity
        }
        assertEquals(8f, apSize(180), 0.1f)
        assertEquals(18f, apSize(272), 0.1f)
    }

    private fun lawnchair() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 183)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 74)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 378)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 188)
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(android.util.SizeF(183.6f, 188.0f), android.util.SizeF(378.5f, 74.3f)))
    }

    @Test fun lawnchairLandscapeUsesTheWideSizeAndPortraitTheNarrowOne() {
        assertEquals(378, chooseWidgetWidthDp(lawnchair(), landscape = true))
        assertEquals(183, chooseWidgetWidthDp(lawnchair(), landscape = false))
        assertEquals(ApWidgetFields(18, 11), apWidgetFields(chooseWidgetWidthDp(lawnchair(), true).toFloat()))
        assertEquals(ApWidgetFields(8, 7), apWidgetFields(chooseWidgetWidthDp(lawnchair(), false).toFloat()))
    }

    @Test fun withoutSizesLandscapeUsesMaxWidthAndPortraitMinWidth() {
        val options = lawnchair().apply { remove(AppWidgetManager.OPTION_APPWIDGET_SIZES) }
        assertEquals(378, chooseWidgetWidthDp(options, landscape = true))
        assertEquals(183, chooseWidgetWidthDp(options, landscape = false))
    }

    @Test fun missingOptionsFallBackToTheOtherValueThenTheDefault() {
        val onlyMin = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200) }
        assertEquals(200, chooseWidgetWidthDp(onlyMin, landscape = true))
        val onlyMax = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300) }
        assertEquals(300, chooseWidgetWidthDp(onlyMax, landscape = false))
        assertEquals(ApWidgetRenderer.DEFAULT_WIDTH_DP, chooseWidgetWidthDp(Bundle(), landscape = true))
    }

    @Test fun theCurrentConfigurationDecidesTheOrientation() {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        manager.updateAppWidgetOptions(id, lawnchair())
        assertEquals(378, ApWidgetRenderer.widthDp(landscapeContext(true), manager, id))
        assertEquals(183, ApWidgetRenderer.widthDp(landscapeContext(false), manager, id))
    }

    private fun landscapeContext(landscape: Boolean): Context {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.orientation = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        return context.createConfigurationContext(config)
    }

    @Test fun theCardHasNoBackgroundOfItsOwn() {
        for (theme in WidgetTheme.entries) {
            val flipper = find(host(build(data(2), theme)))!!
            val item = flipper.adapter.getView(0, null, flipper)
            assertNull(item.findViewById<View>(R.id.ap_item_root).background, "$theme")
        }
    }

    private fun flipper(shown: String?, models: List<ApWidgetModel> = data(3).models): AdapterViewFlipper {
        val views = buildApWidgetViews(context, 21, ApWidgetState.Data(models), WidgetSettings(), false, 250, shown, now)
        return find(host(views))!!
    }

    @Test fun theShownAccountIsTheDisplayedChildAndFallsBackToTheFirst() {
        assertEquals(2, flipper("7003").displayedChild)
        assertEquals(0, flipper(null).displayedChild)
        assertEquals(0, flipper("gone").displayedChild)
    }

    @Test fun aReorderKeepsShowingTheSameAccount() {
        val reordered = listOf(model("7003"), model("7001"), model("7002"))
        assertEquals(0, flipper("7003", reordered).displayedChild)
        assertEquals(1, flipper("7001", reordered).displayedChild)
        assertEquals(2, flipper("7002", reordered).displayedChild)
    }

    @Test fun theButtonColumnIsHiddenWithOneAccountAndShownWithSeveral() {
        assertEquals(View.GONE, host(build(data(1))).findViewById<View>(R.id.ap_nav).visibility)
        assertEquals(View.VISIBLE, host(build(data(2))).findViewById<View>(R.id.ap_nav).visibility)
        assertEquals(View.GONE, host(build(ApWidgetState.NoData)).findViewById<View>(R.id.ap_nav).visibility)
    }

    @Test fun eachButtonSendsItsOwnExplicitBroadcastForThisWidgetId() {
        val host = host(build(data(3)))
        host.findViewById<View>(R.id.ap_nav_down).performClick()
        host.findViewById<View>(R.id.ap_nav_up).performClick()
        val sent = shadowOf(context).broadcastIntents
        assertEquals(2, sent.size)
        sent.forEach { assertEquals(ApWidgetNavReceiver::class.java.name, it.component!!.className) }
        assertEquals(listOf(ApWidgetNavReceiver.NEXT, ApWidgetNavReceiver.PREVIOUS), sent.map { it.getIntExtra(ApWidgetNavReceiver.EXTRA_DIRECTION, 0) })
        assertEquals(listOf(21, 21), sent.map { it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) })
        // Every widget id and direction has its own request code.
        val codes = listOf(1, 2).flatMap { id -> listOf(1, -1).map { shadowOf(navPendingIntent(context, id, it)).requestCode } }
        assertEquals(4, codes.toSet().size)
    }

    @Test fun cardTapAndTileStillWorkNextToTheButtons() {
        val host = host(build(data(3), started = false))
        host.findViewById<View>(R.id.ap_tile).performClick()
        assertEquals(WidgetIntents.startService(context).action, shadowOf(context).nextStartedActivity.action)
        assertTrue(shadowOf(openAccountTemplate(context)).flags and android.app.PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test fun nextAndPreviousWrapAroundInTheTrackersOrder() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val a = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        ApWidgetRenderer.loader = { data(3) }
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.NEXT)
        assertEquals("7002", ApWidgetShown.get(context, a))
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.NEXT)
        assertEquals("7003", ApWidgetShown.get(context, a))
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.NEXT)
        assertEquals("7001", ApWidgetShown.get(context, a))
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.PREVIOUS)
        assertEquals("7003", ApWidgetShown.get(context, a))
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.PREVIOUS)
        assertEquals("7002", ApWidgetShown.get(context, a))
    }

    @Test fun stepUpdatesOnlyItsOwnWidgetAndSurvivesAFullRender() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val a = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        val b = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        ApWidgetRenderer.loader = { data(3) }
        ApWidgetRenderer.step(context, a, ApWidgetNavReceiver.NEXT)
        assertEquals("7002", ApWidgetShown.get(context, a))
        assertNull(ApWidgetShown.get(context, b))
        // The 15-minute refresh redraws everything and keeps the choice.
        ApWidgetRenderer.updateAll(context)
        assertEquals("7002", ApWidgetShown.get(context, a))
        assertNull(ApWidgetShown.get(context, b))
    }

    @Test fun theReceiverRunsTheStepForTheIdInTheIntentOnly() {
        val manager = AppWidgetManager.getInstance(context)
        val a = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        val b = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        ApWidgetRenderer.loader = { data(3) }
        ApWidgetNavReceiver().onReceive(
            context,
            android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, b).putExtra(ApWidgetNavReceiver.EXTRA_DIRECTION, -1),
        )
        val end = System.currentTimeMillis() + 3000
        while (ApWidgetShown.get(context, b) == null && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("7003", ApWidgetShown.get(context, b))
        assertNull(ApWidgetShown.get(context, a))
    }

    @Test fun deletingAWidgetForgetsItsAccount() {
        ApWidgetShown.set(context, 31, "7002")
        ApWidgetShown.set(context, 32, "7003")
        ApWidgetReceiver().onDeleted(context, intArrayOf(31))
        assertNull(ApWidgetShown.get(context, 31))
        assertEquals("7003", ApWidgetShown.get(context, 32))
    }

    @Test fun stepWithNoAccountsDoesNothing() = runBlocking {
        ApWidgetRenderer.loader = { ApWidgetState.NoData }
        ApWidgetRenderer.step(context, 41, ApWidgetNavReceiver.NEXT)
        assertNull(ApWidgetShown.get(context, 41))
    }

    @Test fun pureStepWrapsAndFallsBack() {
        val m = data(3).models
        assertEquals(1, ApWidgetShown.step(m, "7001", 1))
        assertEquals(0, ApWidgetShown.step(m, "7003", 1))
        assertEquals(2, ApWidgetShown.step(m, "7001", -1))
        assertEquals(1, ApWidgetShown.step(m, null, 1))
        assertEquals(1, ApWidgetShown.step(m, "gone", 1))
        assertEquals(0, ApWidgetShown.step(emptyList(), "x", 1))
    }

    @Test fun updateAllDrawsEveryPlacedWidgetThroughAHostView() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        ApWidgetRenderer.loader = { data(3) }
        ApWidgetRenderer.updateAll(context)
        val hostView = shadow.getViewFor(id)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        // The shadow host reapplies the views; adapter contents are checked where the views are applied fresh.
        assertEquals(View.VISIBLE, hostView.findViewById<View>(R.id.ap_flipper).visibility)
        assertEquals(View.GONE, hostView.findViewById<View>(R.id.ap_no_data).visibility)
        assertEquals(1, manager.getAppWidgetIds(ComponentName(context, ApWidgetReceiver::class.java)).size)
        // A later refresh with no data replaces the stack with the message.
        ApWidgetRenderer.loader = { ApWidgetState.NoData }
        ApWidgetRenderer.updateAll(context)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, hostView.findViewById<View>(R.id.ap_no_data).visibility)
    }

    @Test fun noPlacedWidgetLoadsNothing() = runBlocking {
        var loads = 0
        ApWidgetRenderer.loader = { loads++; data(1) }
        ApWidgetRenderer.updateAll(context)
        assertEquals(0, loads)
    }
}
