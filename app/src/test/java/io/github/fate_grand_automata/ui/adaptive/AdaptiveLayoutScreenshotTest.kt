package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.ui.ScreenshotWidths
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the battle config screen at 360/600/840/1280/1600 dp in English and Korean, plus the dark
 * theme in English. Real prefs classes over Robolectric's in-memory SharedPreferences stand in for
 * user data. Images land in `build/outputs/roborazzi/adaptive/`, named
 * `battle_config_<W>x<H>dp[_ko][_dark].png`. Recorded by `:app:recordRoborazziCi`, never part of
 * the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AdaptiveLayoutScreenshotTest(
    private val widthDp: Int,
    private val locale: String?
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shoot(id: String, dark: Boolean) {
        val config = BattleConfigCore(id, context).apply {
            name.set("Sample farming config")
            notes.set("Wave 3 boss: use NP chain")
            support.selectionMode.set(SupportSelectionModeEnum.Preferred)
            support.supportClass.set(SupportClass.Caster)
        }

        composeRule.shoot(
            folder = "adaptive",
            name = "battle_config",
            widthDp = widthDp,
            heightDp = 1500,
            dark = dark,
            locale = locale
        ) {
            BattleConfigContent(
                config = config,
                maxSkillText = "",
                onExport = {},
                onCopy = {},
                onDelete = {},
                navigate = {}
            )
        }
    }

    @Test
    fun battleConfig() = shoot("adaptive_battle_config", dark = false)

    @Test
    fun battleConfigDark() {
        if (locale != null) return // dark mode is covered in English only

        shoot("adaptive_battle_config_dark", dark = true)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp locale={1}")
        fun params(): List<Array<Any?>> =
            ScreenshotWidths.flatMap { width ->
                listOf<String?>(null, "ko").map { locale -> arrayOf<Any?>(width, locale) }
            }
    }
}

/** Records all ten newly capped screens at 1600 dp, in English and Korean, for planner review. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CappedScreensScreenshotTest(private val screen: CappedScreen, private val locale: String?) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    @Test
    fun cappedScreen() {
        val fixture = CappedScreenFixture(ApplicationProvider.getApplicationContext())
        composeRule.shoot(
            folder = "adaptive/width-cap",
            name = screen.name,
            widthDp = 1600,
            heightDp = 1000,
            locale = locale,
            beforeCapture = { composeRule.awaitCappedScreen(screen) }
        ) {
            fixture.Render(screen)
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} locale={1}")
        fun params(): List<Array<Any?>> = CappedScreen.entries.flatMap { screen ->
            listOf<String?>(null, "ko").map { locale -> arrayOf<Any?>(screen, locale) }
        }
    }
}
