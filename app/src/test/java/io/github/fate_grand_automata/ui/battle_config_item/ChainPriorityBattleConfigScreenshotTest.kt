package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the battle config with Chain Priority on in the first entry (#721), in Korean light and dark and in
 * Japanese. Images land in `build/outputs/roborazzi/chain-priority-battle-config/`. Recorded by
 * `:app:recordRoborazziCi`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ChainPriorityBattleConfigScreenshotTest(private val dark: Boolean, private val locale: String) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun chainPriorityOn() {
        val config = BattleConfigCore("chain_shot_${dark}_$locale", context).apply {
            name.set("Sample farming config")
            useChainPriorities.set(listOf(true))
            braveChains.set(listOf(BraveChainEnum.Always))
        }
        composeRule.shoot(
            folder = "chain-priority-battle-config",
            name = "chain_on",
            widthDp = 360,
            heightDp = 2000,
            dark = dark,
            locale = locale
        ) {
            BattleConfigContent(config, "", {}, {}, {}, {})
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} {1}")
        fun params(): List<Array<Any?>> = listOf(
            arrayOf<Any?>(false, "ko"),
            arrayOf<Any?>(true, "ko"),
            arrayOf<Any?>(false, "ja")
        )
    }
}
