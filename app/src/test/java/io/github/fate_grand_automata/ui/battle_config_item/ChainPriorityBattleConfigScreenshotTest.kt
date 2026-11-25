package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the battle config with Chain Priority on for the last of one or three waves (#721), in Korean light and
 * dark and in Japanese, at phone and tablet width. Images land in `build/outputs/roborazzi/chain-priority-battle-config/`. Recorded by
 * `:app:recordRoborazziCi`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ChainPriorityBattleConfigScreenshotTest(
    private val dark: Boolean,
    private val locale: String,
    private val widthDp: Int
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Chain Priority is on for the last wave only.
    private fun shot(waves: Int) {
        val config = BattleConfigCore("chain_shot_${dark}_${locale}_${widthDp}_$waves", context).apply {
            name.set("Sample farming config")
            cardPriority.set(CardPriorityPerWave.of(List(waves) { "A, B, Q, WA, WB, WQ, RA, RB, RQ" }.joinToString("\n")))
            useChainPriorities.set(List(waves) { it == waves - 1 })
            braveChains.set(listOf(BraveChainEnum.Always))
        }
        composeRule.shoot(
            folder = "chain-priority-battle-config",
            name = "chain_on_${waves}wave",
            widthDp = widthDp,
            heightDp = 1400,
            dark = dark,
            locale = locale
        ) {
            BattleConfigContent(config, "", {}, {}, {}, {})
        }
    }

    @Test fun oneWave() = shot(1)

    @Test fun threeWaves() = shot(3)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} {1} {2}dp")
        fun params(): List<Array<Any?>> = listOf(360, 1280).flatMap { w ->
            listOf(arrayOf<Any?>(false, "ko", w), arrayOf<Any?>(true, "ko", w), arrayOf<Any?>(false, "ja", w))
        }
    }
}
