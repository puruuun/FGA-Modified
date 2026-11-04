package io.github.fate_grand_automata.ui.adaptive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.CappedContentTag
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalAdaptiveLayout
import io.github.fate_grand_automata.ui.captureBitmap
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs in Main CI. Each screen must match its uncapped baseline through the cap boundary. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CappedScreenIdentityTest(private val screen: CappedScreen, private val widthDp: Int) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    @Test
    fun widthCapPreservesPixelsOrCentresWideContent() {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h1000dp-hdpi")
        val fixture = CappedScreenFixture(ApplicationProvider.getApplicationContext())
        var capped by mutableStateOf(false)
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                CompositionLocalProvider(LocalAdaptiveLayout provides capped) {
                    fixture.Render(screen)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.awaitCappedScreen(screen)
        val uncapped = composeRule.captureBitmap()

        composeRule.runOnIdle { capped = true }
        composeRule.waitForIdle()
        val actual = composeRule.captureBitmap()
        assertEquals(uncapped.width, actual.width)
        assertEquals(uncapped.height, actual.height)

        val expectedWidth = minOf(widthDp, 1280)
        composeRule.onNodeWithTag(CappedContentTag)
            .assertWidthIsEqualTo(expectedWidth.dp)
            .assertLeftPositionInRootIsEqualTo(((widthDp - expectedWidth) / 2).dp)
        if (widthDp <= 1280) {
            assertTrue("$screen at $widthDp dp must be pixel-identical", uncapped.sameAs(actual))
        } else {
            assertFalse("$screen at 1600 dp must differ from the uncapped control", uncapped.sameAs(actual))
            // Both outer margins retain the same window background as the uncapped screen.
            assertEquals(uncapped.getPixel(0, 0), actual.getPixel(0, 0))
            assertEquals(actual.getPixel(0, 0), actual.getPixel(actual.width - 1, 0))
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} at {1}dp")
        fun params(): List<Array<Any>> = CappedScreen.entries.flatMap { screen ->
            listOf(360, 1280, 1600).map { width -> arrayOf<Any>(screen, width) }
        }
    }
}
