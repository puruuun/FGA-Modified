package io.github.fate_grand_automata.ui.more

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Composable
internal fun BatteryPreferenceMoreOptions() {
    val context = LocalContext.current
    val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("battery-pref", Context.MODE_PRIVATE)), context)
    CompositionLocalProvider(LocalNavigateBack provides {}) {
        MoreOptionsContent(
            prefs = prefs,
            storageSummary = "",
            extractSummary = "",
            extractSupportImages = {},
            additionalFeatures = {},
            goToFineTune = {},
            goToAddedFeatureTuning = {},
            goToLicense = {},
            goToWeeklySettings = {},
            pickDirectory = {},
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class BatteryOptimizationPreferenceTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val batteryTitle get() = app.getString(R.string.p_battery_optimization_action)

    private fun openAdvanced(unavailableActions: Set<String> = emptySet()): MutableList<Intent> {
        val attempts = mutableListOf<Intent>()
        compose.setContent {
            val activity = LocalContext.current
            val context = object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) {
                    attempts += intent
                    if (intent.action in unavailableActions) throw ActivityNotFoundException()
                    super.startActivity(intent)
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                FGATheme { BatteryPreferenceMoreOptions() }
            }
        }
        compose.onNodeWithText(app.getString(R.string.p_advanced)).performClick()
        compose.onNodeWithTag("more-tab:Advanced").performScrollToNode(hasText(batteryTitle))
        return attempts
    }

    @Test fun buttonFollowsAutoStartWithoutSummaryAndOpensBatteryList() {
        openAdvanced()
        val autoStart = compose.onNodeWithText(app.getString(R.string.p_auto_start_service))
        val battery = compose.onNodeWithText(batteryTitle)
        autoStart.assertIsDisplayed()
        battery.assertIsDisplayed().assertHasClickAction()
        assertEquals(
            autoStart.fetchSemanticsNode().boundsInRoot.bottom,
            battery.fetchSemanticsNode().boundsInRoot.top,
            0.5f
        )
        assertEquals(listOf(batteryTitle), battery.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
        battery.performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun missingBatteryListFallsBackToThisApplicationsDetails() {
        val attempts = openAdvanced(setOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        compose.onNodeWithText(batteryTitle).performClick()
        assertEquals(
            listOf(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            attempts.map { it.action }
        )
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${BuildConfig.APPLICATION_ID}", started.dataString)
    }

    @Test fun missingBothSettingsScreensDoesNotCrashOrShowAStatusLine() {
        val attempts = openAdvanced(setOf(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ))
        compose.onNodeWithText(batteryTitle).performClick().assertIsDisplayed()
        assertEquals(2, attempts.size)
        assertEquals(null, shadowOf(app).nextStartedActivity)
        assertEquals(listOf(batteryTitle), compose.onNodeWithText(batteryTitle)
            .fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
    }
}
