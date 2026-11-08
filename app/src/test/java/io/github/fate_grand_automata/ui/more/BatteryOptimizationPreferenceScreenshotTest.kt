package io.github.fate_grand_automata.ui.more

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class BatteryOptimizationPreferenceScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun advanced360() = compose.shoot(
        "battery-pref", "Advanced", 360, 800, locale = "ko",
        beforeCapture = {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            compose.onNodeWithText(context.getString(R.string.p_advanced)).performClick()
            compose.onNodeWithTag("more-tab:Advanced").performScrollToNode(
                hasText(context.getString(R.string.p_battery_optimization_action))
            )
        }
    ) { BatteryPreferenceMoreOptions() }
}
