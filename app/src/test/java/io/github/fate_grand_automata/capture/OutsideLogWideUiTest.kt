package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w840dp-h1000dp-hdpi")
class OutsideLogWideUiTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    @Test fun twoPanesSelectNewestOutsideThenSwitchToBattleAndBack() {
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(listOf(battleLogFixture()), {}, {}, outsideNames, outsideEntries = listOf(outsideFixture()))
        } }
        composeRule.onNodeWithText("Totals").assertIsDisplayed()
        composeRule.onAllNodesWithText("Changes outside scripts").assertCountEquals(2)
        composeRule.onNodeWithText("Daily quests").performClick()
        composeRule.onNodeWithText("Totals").assertDoesNotExist()
        composeRule.onAllNodesWithText("Daily quests").assertCountEquals(2)
        composeRule.onNodeWithText("Changes outside scripts").performClick()
        composeRule.onNodeWithText("Totals").assertIsDisplayed()
    }
}
