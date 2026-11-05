package io.github.fate_grand_automata.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.ui.log.LogScreenContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w1000dp-h800dp-hdpi")
class BattleLogTwoPaneTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val old = battleLogFixture("old").copy(endedAt = 1)
    private val recent = battleLogFixture("new", extras = true)

    private val bondTotals get() = context.getString(R.string.bond_totals)

    @Test
    fun wideShowsListAndNewestDetailTogether() {
        composeRule.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(old, recent), {}, {}) } }
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete_all)).assertDoesNotExist()
        composeRule.onAllNodesWithText(old.configName)[0].assertIsDisplayed()
        // The newest entry has the bond section; it is the one selected by default.
        composeRule.onNodeWithText(bondTotals).assertIsDisplayed()
        composeRule.onAllNodesWithText(recent.configName).assertCountEquals(2)
    }

    @Test
    fun tappingAnotherCardChangesTheDetail() {
        composeRule.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(old, recent), {}, {}) } }
        composeRule.onAllNodesWithText(old.configName)[0].performClick()
        composeRule.onNodeWithText(bondTotals).assertDoesNotExist()
        composeRule.onAllNodesWithText(old.configName).assertCountEquals(2)
        composeRule.onAllNodesWithText(recent.configName).assertCountEquals(1)
    }

    @Test
    fun deletingTheSelectedEntryFallsBackToNewestThenEmpty() {
        var entries by mutableStateOf(listOf(old, recent))
        composeRule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(entries, onDelete = { id -> entries = entries.filterNot { it.id == id } }, onClear = {})
        } }
        composeRule.onAllNodesWithText(old.configName)[0].performClick()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).performClick()
        composeRule.onNodeWithTag("log-card:old").performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_log_delete_selected)).performClick()
        composeRule.onNode(hasText(context.getString(R.string.battle_log_delete)) and hasClickAction()).performClick()
        composeRule.onNodeWithText(bondTotals).assertIsDisplayed()
        composeRule.onAllNodesWithText(old.configName).assertCountEquals(0)

        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).performClick()
        composeRule.onNodeWithTag("log-card:new").performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_log_delete_selected)).performClick()
        composeRule.onNode(hasText(context.getString(R.string.battle_log_delete)) and hasClickAction()).performClick()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_empty)).assertIsDisplayed()
    }

    @Test
    fun reasonIsIncludedInTheSecondLine() {
        val repeated = battleLogFixture("rep").copy(reasonText = context.getString(R.string.times_ran, 8))
        composeRule.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(repeated), {}, {}) } }
        composeRule.onNode(hasText(repeated.reasonText, substring = true) and androidx.compose.ui.test.hasTestTag("log-card:rep")).assertExists()
    }
}
