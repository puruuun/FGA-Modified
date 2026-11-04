package io.github.fate_grand_automata.ui.adaptive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.captureBitmap
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone-sized review images, recorded locally with :app:recordRoborazziCi. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LogEntryScreenshotTest(private val screen: CappedScreen, private val locale: String?) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    @Test
    fun phoneScreen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = CappedScreenFixture(context)
        composeRule.shoot(
            folder = "adaptive/log-entry",
            name = if (screen == CappedScreen.MoreOptions) "Advanced" else screen.name,
            widthDp = 360,
            heightDp = 800,
            locale = locale,
            beforeCapture = {
                if (screen == CappedScreen.MoreOptions) {
                    composeRule.onNodeWithText(context.getString(R.string.p_advanced)).performClick()
                }
                composeRule.mainClock.advanceTimeBy(500)
                composeRule.waitForIdle()
                // Prime the native canvas before recording first-use vector icons.
                composeRule.captureBitmap()
                composeRule.waitForIdle()
            }
        ) {
            CompositionLocalProvider(LocalNavigateBack provides if (screen == CappedScreen.Main) null else ({})) {
                fixture.Render(screen)
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} locale={1}")
        fun params(): List<Array<Any?>> =
            listOf(CappedScreen.Main, CappedScreen.MoreOptions, CappedScreen.Logs).flatMap { screen ->
                listOf<String?>(null, "ko").map { locale -> arrayOf<Any?>(screen, locale) }
            }
    }
}
