package io.github.fate_grand_automata.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.PrefMaker
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class ApWidgetConfigActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val updates = mutableListOf<Unit>()
    private val originalLauncher = ApWidgetRefresh.launcher

    @Before fun setUp() {
        context.getSharedPreferences("fga_widget_bindings", Context.MODE_PRIVATE).edit().clear().commit()
        ApWidgetRefresh.launcher = { updates += Unit }
    }

    @After fun tearDown() {
        ApWidgetRefresh.launcher = originalLauncher
        CaptureTracker.delete()
    }

    private fun captureSwitch(on: Boolean) {
        PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))
            .bool("capture_tracker_enabled", false).set(on)
    }

    private fun collect(id: String, name: String, host: String = "yfgo-game.netmarble.com") {
        CaptureTracker.init(context)
        CaptureTracker.onGameResponse(
            "https://$host/login/top",
            """{"response":[{"nid":"login","resCode":"00","success":{}},{"nid":"userdata","resCode":"00","success":{}}],
            "cache":{"replaced":{"userGame":[{"userId":$id,"name":"$name","actMax":100}]},"serverTime":1800000000}}""".toByteArray(),
            null,
        )
    }

    private fun launch(widgetId: Int = 5) = ActivityScenario.launchActivityForResult<ApWidgetConfigActivity>(
        Intent(context, ApWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
    )

    private fun message() = context.getString(R.string.widget_ap_config_unavailable)

    private fun assertCanceledWithMessage(scenario: ActivityScenario<ApWidgetConfigActivity>) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(message()).assertExists() }.isSuccess
        }
        scenario.onActivity { assertEquals(Activity.RESULT_CANCELED, shadowOf(it).resultCode) }
        assertNull(ApWidgetBindings(context).accountFor(5))
    }

    @Test fun logCollectionOffExplainsAndCancels() {
        captureSwitch(false)
        collect("7001", "Fictional")
        assertCanceledWithMessage(launch())
    }

    @Test fun noStoredAccountExplainsAndCancels() {
        captureSwitch(true)
        assertCanceledWithMessage(launch())
    }

    @Test @Config(sdk = [28]) fun android9ExplainsAndCancelsEvenWithAccounts() {
        captureSwitch(true)
        collect("7001", "Fictional")
        assertCanceledWithMessage(launch())
    }

    @Test fun closeButtonFinishesWithCanceled() {
        captureSwitch(false)
        val scenario = launch()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(message()).assertExists() }.isSuccess }
        compose.onNodeWithText(context.getString(R.string.widget_ap_config_close)).performClick()
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }

    @Test fun choosingAnAccountSavesTheBindingAndReturnsOk() {
        captureSwitch(true)
        collect("7001", "Fictional")
        val scenario = launch(widgetId = 5)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("widgetAccount:7001").assertExists() }.isSuccess }
        compose.onNodeWithText("Fictional").assertExists()
        compose.onNodeWithText("KR").assertExists()
        compose.onNodeWithTag("widgetAccount:7001").performClick()
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertEquals(5, result.resultData.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals("7001", ApWidgetBindings(context).accountFor(5))
        // The new widget gets its first update right away.
        assertEquals(1, updates.size)
    }

    @Test fun severalAccountsAreListedAndAnotherWidgetKeepsItsOwnChoice() {
        captureSwitch(true)
        collect("7001", "First")
        collect("7002", "Second", "game.fate-go.jp")
        ApWidgetBindings(context).bind(1, "7001")
        val scenario = launch(widgetId = 2)
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("widgetAccount:7002").assertExists() }.isSuccess }
        compose.onNodeWithTag("widgetAccount:7001").assertExists()
        compose.onNodeWithText("JP").assertExists()
        compose.onNodeWithTag("widgetAccount:7002").performClick()
        scenario.result
        assertEquals("7001", ApWidgetBindings(context).accountFor(1))
        assertEquals("7002", ApWidgetBindings(context).accountFor(2))
    }

    @Test fun missingWidgetIdCancelsWithoutAScreen() {
        captureSwitch(true)
        collect("7001", "Fictional")
        val scenario = ActivityScenario.launchActivityForResult<ApWidgetConfigActivity>(Intent(context, ApWidgetConfigActivity::class.java))
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }
}
