package io.github.fate_grand_automata.ui.adaptive

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
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
 * Before/after set for the palette change: the screens the colour roles reach, at phone width in
 * the light and dark theme. Images land in `build/outputs/roborazzi/palette595/`. Recorded by
 * `:app:recordRoborazziCi`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Palette595ScreenshotTest(private val dark: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private fun screen(screen: CappedScreen, height: Int = 900) {
        val fixture = CappedScreenFixture(ApplicationProvider.getApplicationContext())
        composeRule.shoot("palette595", screen.name, 360, height, dark = dark, locale = "ko") {
            fixture.Render(screen)
        }
    }

    @Test fun main() = screen(CappedScreen.Main)
    @Test fun battleConfigList() = screen(CappedScreen.BattleConfigList)
    @Test fun cardPriority() = screen(CappedScreen.CardPriority)
    @Test fun preferredSupport() = screen(CappedScreen.PreferredSupport, 1400)
    @Test fun moreOptions() = screen(CappedScreen.MoreOptions, 1400)
    @Test fun fineTune() = screen(CappedScreen.FineTune)
    @Test fun spam() = screen(CappedScreen.Spam)

    @Test
    fun battleConfig() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = BattleConfigCore("palette595", context).apply { name.set("Sample farming config") }
        composeRule.shoot("palette595", "BattleConfig", 360, 1500, dark = dark, locale = "ko") {
            BattleConfigContent(
                config = config, maxSkillText = "", onExport = {}, onCopy = {}, onDelete = {}, navigate = {}
            )
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params(): List<Array<Any?>> = listOf(arrayOf<Any?>(false), arrayOf<Any?>(true))
    }
}
