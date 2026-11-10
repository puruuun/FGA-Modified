package io.github.fate_grand_automata.widget

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterViewFlipper
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.main.MainActivity
import io.github.fate_grand_automata.ui.main.Route
import io.github.fate_grand_automata.ui.main.WidgetNavigationEffects
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35])
class WidgetAccountNavigationTest {
    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val requests = WidgetLaunchRequests()
    private val backStack = mutableStateListOf<NavKey>(Route.Home, Route.Logs)
    private val originalLoader = ApWidgetRenderer.loader
    private val models = listOf("7001", "jp:7002", "7003").map { id ->
        ApWidgetModel(id, "Account $id", 57, 142, null, 1_800_000_000L)
    }

    @After fun restore() { ApWidgetRenderer.loader = originalLoader }

    private fun show(onboarding: Boolean = false) {
        compose.setContent {
            WidgetNavigationEffects(requests, onboarding, backStack) { false }
            NavDisplay(backStack = backStack, onBack = {}, entryProvider = entryProvider {
                entry<Route.Home> { Text("Home") }
                entry<Route.Logs> { Text("Logs") }
                entry<Route.AccountDashboard> { Text("Dashboard ${it.userId}") }
            })
        }
        compose.waitForIdle()
    }

    private fun host(models: List<ApWidgetModel> = this.models, shown: String? = null): Pair<Int, ViewGroup> {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(ApWidgetReceiver::class.java, R.layout.widget_ap_root)
        ApWidgetShown.set(context, id, shown ?: models.first().accountId)
        val views = buildApWidgetViews(context, id, ApWidgetState.Data(models), WidgetSettings(), false, 400, shown)
        // The stock shadow's reapply path has no AppWidgetHostView parent for collection adapters.
        shadowOf(manager).setAlwaysRecreateViewsDuringUpdate(true)
        manager.updateAppWidget(id, views)
        shadowOf(manager).setAlwaysRecreateViewsDuringUpdate(false)
        shadowOf(Looper.getMainLooper()).idle()
        val host = shadowOf(manager).getViewFor(id) as ViewGroup
        // View.post only runs once the view is attached to a window, and a tap performs its click through post.
        Robolectric.buildActivity(Activity::class.java).setup().get()
            .addContentView(host, ViewGroup.LayoutParams(600, 120))
        shadowOf(Looper.getMainLooper()).idle()
        // Lay out the actual host so touch dispatch exercises the visible full-card target.
        host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 600, 120)
        return id to host
    }
    private fun findById(v: View, id: Int): View? =
        if (v.id == id) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { findById(g.getChildAt(it), id) } }

    private fun tapCard(host: ViewGroup, expectedId: String) {
        val flipper = host.findViewById<AdapterViewFlipper>(R.id.ap_flipper)
        // Collection items are applied asynchronously: drain the background work and the main looper.
        settleMainLooperAndAsyncTasks()
        val card = assertNotNull(flipper.currentView?.let { findById(it, R.id.ap_item_root) })
        // A root-only fill-in tag has no listener and fails this assertion on the old layout.
        assertTrue(card.hasOnClickListeners())
        assertTrue(card.isClickable)
        val bounds = Rect(0, 0, card.width, card.height)
        host.offsetDescendantRectToMyCoords(card, bounds)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            assertTrue(host.dispatchTouchEvent(event))
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        val intent = assertNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals(expectedId, intent.getStringExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT))
        assertNull(shadowOf(context).nextStartedActivity, "One tap must send one activity intent")
        compose.runOnIdle { requests.consume(intent) }
        compose.waitForIdle()
    }

    private fun assertDashboard(id: String) {
        assertEquals(listOf(Route.Home, Route.AccountDashboard(id)), backStack.toList())
        compose.onNodeWithText("Dashboard $id").assertIsDisplayed()
        assertNull(requests.accountPending)
    }

    @Test fun singleAccountCardSendsFillInAndOpensItsDashboard() {
        show()
        tapCard(host(models.take(1)).second, "7001")
        assertDashboard("7001")
    }

    @Test fun rememberedAccountSendsItsOwnFillIn() {
        show()
        tapCard(host(shown = "jp:7002").second, "jp:7002")
        assertDashboard("jp:7002")
    }

    @Test fun nextAndPreviousPartialUpdatesKeepCardIntentInSync() = runBlocking {
        show()
        val (id, host) = host()
        ApWidgetRenderer.loader = { ApWidgetState.Data(models) }
        tapCard(host, "7001")
        assertDashboard("7001")
        // Production step() remembers the shown account; Robolectric cannot apply the partial update, so the displayed child it sets is applied here.
        for ((direction, expected) in listOf(1 to "jp:7002", 1 to "7003", -1 to "jp:7002", -1 to "7001", -1 to "7003")) {
            ApWidgetRenderer.step(context, id, direction)
            host.findViewById<AdapterViewFlipper>(R.id.ap_flipper).displayedChild = models.indexOfFirst { it.accountId == expected }
            shadowOf(Looper.getMainLooper()).idle()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 120)
            tapCard(host, expected)
            assertDashboard(expected)
            assertEquals(expected, ApWidgetShown.get(context, id))
        }
    }

    @Test fun onboardingDropsTheAccountTap() {
        show(onboarding = true)
        tapCard(host(models.take(1)).second, "7001")
        assertEquals(listOf(Route.Home, Route.Logs), backStack.toList())
        assertNull(requests.accountPending)
        assertFalse(requests.startPending)
    }
}
