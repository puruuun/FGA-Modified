package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Before/after set for the card priority row restyle (#611). Images land in
 * `build/outputs/roborazzi/cardpriority611/`. Recorded by `:app:recordRoborazziCi`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CardPriority611ScreenshotTest(private val dark: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private fun shot(waves: Int, width: Int, chainShown: Boolean = false, name: String = "Waves$waves") {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = BattleConfigCore("cp611_$waves", context)
        val items = List(waves) { config.cardPriority.get().first() }.map { scores ->
            CardPriorityListItem(
                scores = scores.toMutableStateList(),
                servantPriority = listOf(TeamSlot.A, TeamSlot.B, TeamSlot.C, TeamSlot.D, TeamSlot.E, TeamSlot.F).toMutableStateList(),
                servantCards = ServantCardKey.all.toMutableStateList(),
                rearrangeCards = mutableStateOf(false),
                braveChains = mutableStateOf(BraveChainEnum.None),
                style = mutableStateOf(CardPriorityStyle.Affinity),
                useServantPriority = mutableStateOf(true),
                useChainPriority = mutableStateOf(chainShown)
            )
        }.toMutableStateList()

        composeRule.shoot(
            "cardpriority611", name, width, 800,
            dark = dark, locale = "ko"
        ) {
            CardPriorityView(items, config.cardPrioritySplit, config.cardSwapDetection, chainShown = chainShown)
        }
    }

    @Test fun oneWave360() = shot(1, 360)
    @Test fun oneWave1280() = shot(1, 1280)
    @Test fun threeWaves360() = shot(3, 360)
    @Test fun threeWaves1280() = shot(3, 1280)
    @Test fun fiveWaves360() = shot(5, 360)
    // #654: Chain Priority shown and on; the switch, the order panel and the Always option are hidden while not shown.
    @Test fun chainPriority360() = shot(2, 360, chainShown = true, name = "ChainPriority")
    @Test fun chainPriority1280() = shot(2, 1280, chainShown = true, name = "ChainPriority")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params(): List<Array<Any?>> = listOf(arrayOf<Any?>(false), arrayOf<Any?>(true))
    }
}
