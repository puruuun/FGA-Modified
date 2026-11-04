package io.github.fate_grand_automata.ui.adaptive

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.main.MainScreenDestinations
import io.github.fate_grand_automata.ui.prefs.LanguagePref
import io.github.fate_grand_automata.ui.prefs.initializeApplicationLanguage
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class LogEntryTest {
    private val composeRule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val rules = screenshotRules(composeRule)

    @After
    fun resetLocale() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun mainOffersLogsBetweenBattleConfigAndMoreOptions() {
        var destination: MainScreenDestinations? = null
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                MainScreenContent(
                    navigate = { destination = it }, overlayServiceStarted = false,
                    toggleOverlayService = {}, accessibilityServiceStarted = false,
                    toggleAccessibilityService = {}
                )
            }
        }
        val battle = composeRule.onNodeWithText(context.getString(R.string.p_battle_config))
        val logs = composeRule.onNodeWithText(context.getString(R.string.p_logs))
        val more = composeRule.onNodeWithText(context.getString(R.string.p_more_options))
        assertTrue(battle.fetchSemanticsNode().boundsInRoot.bottom < logs.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(logs.fetchSemanticsNode().boundsInRoot.bottom < more.fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithText(context.getString(R.string.p_app_language)).assertDoesNotExist()
        logs.performClick()
        assertEquals(MainScreenDestinations.Logs, destination)
    }

    @Test
    fun advancedLanguageChoiceStillSetsApplicationLocale() {
        val fixture = CappedScreenFixture(context)
        composeRule.setContent {
            FGATheme(darkTheme = false) { fixture.Render(CappedScreen.MoreOptions) }
        }
        composeRule.onNodeWithText(context.getString(R.string.p_advanced)).performClick()
        val language = composeRule.onNodeWithText(context.getString(R.string.p_app_language))
        language.assertIsDisplayed()
        val tuning = composeRule.onNodeWithText(context.getString(R.string.added_tuning_title))
        tuning.assertIsDisplayed()
        assertTrue(language.fetchSemanticsNode().boundsInRoot.bottom <= tuning.fetchSemanticsNode().boundsInRoot.top)
        language.performClick()
        composeRule.onNodeWithText(context.getString(R.string.language_ko)).performClick()
        composeRule.runOnIdle {
            assertEquals("ko", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            initializeApplicationLanguage()
            // A fresh preference, as created after activity recreation, reads the applied locale.
            assertEquals("ko", LanguagePref().get())
        }
    }

    @Test
    fun logsShowsPlaceholder() {
        val fixture = CappedScreenFixture(context)
        var wentBack = false
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                CompositionLocalProvider(LocalNavigateBack provides { wentBack = true }) {
                    fixture.Render(CappedScreen.Logs)
                }
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.p_logs)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.logs_coming_soon)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back))
            .assertIsDisplayed().performClick()
        assertTrue(wentBack)
    }
}
