package io.github.fate_grand_automata.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.battlelog.gainsNamesFixture
import io.github.fate_grand_automata.ui.log.BattleLogDetail
import io.github.fate_grand_automata.ui.log.LogScreenContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h1400dp-hdpi")
class GainsLogScreenTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun oldEntryHasNoGainsSectionOrMark() {
        composeRule.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(battleLogFixture()), {}, {}) } }
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_tracked)).assertDoesNotExist()
        composeRule.onNodeWithText("Daily quests").performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_title)).assertDoesNotExist()
    }

    @Test fun trackedCardOpensSortedNamedGainsAndMissingNotice() {
        val entry = battleLogFixture().copy(gains = gainsFixture(true))
        composeRule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(listOf(entry), {}, {}, gainsNamesFixture)
        } }
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_tracked)).assertIsDisplayed()
        composeRule.onNodeWithText(entry.configName).performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_missing, "2")).performScrollTo().assertIsDisplayed()
        val large = composeRule.onNodeWithText("무간의 톱니바퀴").performScrollTo().fetchSemanticsNode().boundsInRoot
        val small = composeRule.onNodeWithText("검의 휘석").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(large.top < small.top)
        composeRule.onNodeWithText("+7,404,000").assertExists()
        composeRule.onAllNodesWithText(context.getString(R.string.capture_gains_holdings, "12,345")).assertCountEquals(2)
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_unknown)).performScrollTo().assertIsDisplayed()
    }

    @Test fun breakdownStartsCollapsedAndCanBeExpandedAndCollapsed() {
        val entry = battleLogFixture().copy(gains = gainsFixture())
        val firstBattle = context.getString(R.string.capture_gains_battle, "1", "10001")
        composeRule.setContent { FGATheme(darkTheme = false) { BattleLogDetail(entry, {}, gainsNamesFixture) } }
        composeRule.onNodeWithText(firstBattle).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_breakdown)).performScrollTo().performClick()
        composeRule.onNodeWithText(firstBattle).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_collapse)).performScrollTo().performClick()
        composeRule.onNodeWithText(firstBattle).assertDoesNotExist()
    }
}
