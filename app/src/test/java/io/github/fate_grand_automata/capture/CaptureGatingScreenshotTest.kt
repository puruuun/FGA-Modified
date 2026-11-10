package io.github.fate_grand_automata.capture

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.ScreenshotDir
import io.github.fate_grand_automata.ui.account.DashboardUiFixture
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic data only: the owner's review images for the log-collection gating and the log filter dialog. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class CaptureGatingScreenshotTest(private val width: Int) {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun logFilterDialogWithSearch() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-notnight-hdpi")
        val names = GameNameMap(svtData = (1L..20L).associateWith {
            AtlasBasic(it, if (it % 2 == 0L) "합성 사과 $it" else "합성 바나나 $it", "normal")
        })
        val keys = (1L..20L).map { "${LogResource.SVT}:$it" }.toSet()
        compose.setContent { FGATheme(darkTheme = false) {
            LogFilterDialog(LogTimeFilter.All, null, null, null, 200, 0, 0, resourceLogFixture(keys), names, null, {}, { _, _, _, _, _, _, _ -> })
        } }
        compose.onNodeWithTag("log-filter-search").performTextInput("사과")
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(isDialog()).captureRoboImage("$ScreenshotDir/issue-239/log-filter-search_${width}dp.png")
    }

    @Test fun additionalFeaturesSwitchOff() = additional(false)
    @Test fun additionalFeaturesSwitchOn() = additional(true)

    private fun additional(on: Boolean) {
        val fixture = DashboardUiFixture()
        compose.shoot("issue-239", "additional-features-${if (on) "on" else "off"}", width, 800, locale = "ko",
            beforeCapture = {
                compose.onNodeWithText(fixture.context.getString(R.string.p_additional_features)).performClick()
            }) {
            CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons { fixture.More(captureOn = on) } }
        }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp")
        fun widths() = listOf(360, 1280).map { arrayOf<Any>(it) }
    }
}
