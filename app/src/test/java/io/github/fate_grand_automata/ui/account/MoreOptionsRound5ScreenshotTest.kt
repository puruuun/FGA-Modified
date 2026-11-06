package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Focused light-only renders of the existing More options fixtures for round 5. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MoreOptionsRound5ScreenshotTest(private val width: Int, private val height: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}")
        fun sizes() = listOf(arrayOf(360, 800), arrayOf(1280, 720))
    }

    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun tab(name: String, title: Int) = compose.shoot(
        "dashboard-round4", name, width, height, dark = false, locale = "ko",
        beforeCapture = { compose.onNodeWithText(fixture.context.getString(title)).performClick() }
    ) {
        CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.More() }
    }

    @Test fun additionalFeatures() = tab("AdditionalFeatures", R.string.p_additional_features)
    @Test fun advanced() = tab("Advanced", R.string.p_advanced)
}
