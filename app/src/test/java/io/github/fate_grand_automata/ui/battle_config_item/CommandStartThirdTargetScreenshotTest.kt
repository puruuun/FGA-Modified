package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the battle config with all three command start targets on, in Korean, at phone and tablet width.
 * Images land in `build/outputs/roborazzi/command-start-third/`. Recorded by `:app:recordRoborazziCi`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CommandStartThirdTargetScreenshotTest(private val widthDp: Int) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun threeTargets() {
        val config = BattleConfigCore("third_shot_$widthDp", context).apply {
            name.set("Sample farming config")
            commandStartMode.set(CommandStartMode.CardCount)
            secondaryStartEnabled.set(true)
            tertiaryStartEnabled.set(true)
            commandStartServant.set(1)
            secondaryStartServant.set(2)
            tertiaryStartServant.set(3)
        }
        composeRule.shoot(
            folder = "command-start-third",
            name = "three_targets",
            widthDp = widthDp,
            heightDp = 2600,
            locale = "ko"
        ) {
            BattleConfigContent(config, "", {}, {}, {}, {})
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp")
        fun params(): List<Array<Any?>> = listOf(360, 840).map { arrayOf<Any?>(it) }
    }
}
