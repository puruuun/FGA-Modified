package io.github.fate_grand_automata.ui.more

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.AppTheme
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.util.AutomataApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class DarkModePreferenceTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val app get() = ApplicationProvider.getApplicationContext<Application>() as AutomataApplication

    private val darkModeTitle get() = app.getString(R.string.p_app_theme)
    private val languageTitle get() = app.getString(R.string.p_app_language)
    private val systemLabel get() = app.getString(R.string.p_app_theme_system)
    private val lightLabel get() = app.getString(R.string.p_app_theme_light)
    private val darkLabel get() = app.getString(R.string.p_app_theme_dark)

    @Before
    fun resetBefore() {
        app.prefsCore.appTheme.resetToDefault()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun resetAfter() {
        app.prefsCore.appTheme.resetToDefault()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun openAdvanced() {
        compose.setContent {
            FGATheme {
                CompositionLocalProvider(LocalNavigateBack provides {}) {
                    MoreOptionsContent(
                        prefs = app.prefsCore,
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
        }
        compose.onNodeWithText(app.getString(R.string.p_advanced)).performClick()
        compose.onNodeWithTag("more-tab:Advanced").performScrollToNode(hasText(darkModeTitle))
    }

    @Test
    fun darkModeItemFollowsLanguageShowsSummaryAndChoosingDarkAppliesNightMode() {
        openAdvanced()

        val languageNode = compose.onNodeWithText(languageTitle).assertIsDisplayed()
        val darkModeNode = compose.onNodeWithText(darkModeTitle).assertIsDisplayed()

        assertEquals(
            languageNode.fetchSemanticsNode().boundsInRoot.bottom,
            darkModeNode.fetchSemanticsNode().boundsInRoot.top,
            0.5f,
        )
        assertEquals(
            listOf(darkModeTitle, systemLabel),
            darkModeNode.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
        )

        darkModeNode.performClick()
        compose.onAllNodesWithText(systemLabel).onLast().assertIsDisplayed()
        compose.onNodeWithText(lightLabel).assertIsDisplayed()
        compose.onNodeWithText(darkLabel).assertIsDisplayed().performClick()

        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(AppTheme.Dark, app.prefsCore.appTheme.get())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
        assertEquals(
            listOf(darkModeTitle, darkLabel),
            compose.onNodeWithText(darkModeTitle).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
        )
    }

    @Test
    fun darkModeStringsExistInEnglishKoreanAndJapanese() {
        val expected = mapOf(
            "en" to listOf("Dark mode", "Follow system", "Light", "Dark"),
            "ko" to listOf("다크 모드", "시스템 설정 따름", "라이트", "다크"),
            "ja" to listOf("ダークモード", "システム設定に従う", "ライト", "ダーク"),
        )
        for ((tag, strings) in expected) {
            val config = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = app.createConfigurationContext(config)
            assertEquals(
                strings,
                listOf(
                    localized.getString(R.string.p_app_theme),
                    localized.getString(R.string.p_app_theme_system),
                    localized.getString(R.string.p_app_theme_light),
                    localized.getString(R.string.p_app_theme_dark),
                ),
            )
        }
    }
}
