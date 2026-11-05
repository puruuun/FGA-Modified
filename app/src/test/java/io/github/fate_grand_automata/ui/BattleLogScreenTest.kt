package io.github.fate_grand_automata.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.ui.log.BattleLogDetail
import io.github.fate_grand_automata.ui.log.LogScreenContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class BattleLogScreenTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun newestFirstAndCardOpensDetailWithOptionalSections() {
        val old = battleLogFixture("old").copy(endedAt = 1)
        val recent = battleLogFixture("new", extras = true)
        composeRule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(listOf(old, recent), {}, {})
        } }
        val first = composeRule.onNodeWithText(recent.configName).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithText(old.configName).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(first.top < second.top)
        composeRule.onNodeWithText(recent.configName).performClick()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_detail)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bond_totals)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("x12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.p_logs)).assertIsDisplayed()
    }

    @Test
    fun detailOmitsDisabledMaterialsAndBond() {
        composeRule.setContent { FGATheme(darkTheme = false) {
            BattleLogDetail(battleLogFixture(), {})
        } }
        composeRule.onNodeWithText(context.getString(R.string.bond_totals)).assertDoesNotExist()
        composeRule.onNodeWithText("x12").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.retreat_count, 3, 1, 2))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deleteAndClearRequireConfirmationAndCanBeCancelled() {
        var entries by mutableStateOf(listOf(battleLogFixture()))
        var deletes = 0
        var clears = 0
        composeRule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(entries,
                onDelete = { id -> deletes++; entries = entries.filterNot { it.id == id } },
                onClear = { clears++; entries = emptyList() })
        } }
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).performClick()
        composeRule.onNodeWithTag("log-card:sample").performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_log_delete_selected)).performClick()
        assertEquals(0, deletes)
        composeRule.onAllNodesWithText(context.getString(android.R.string.cancel)).onLast().performClick()
        assertEquals(0, deletes)
        composeRule.onNodeWithText(context.getString(R.string.capture_log_delete_selected)).performClick()
        composeRule.onNode(hasText(context.getString(R.string.battle_log_delete)) and hasClickAction()).performClick()
        assertEquals(1, deletes)
        composeRule.onNodeWithText(context.getString(R.string.battle_log_empty)).assertIsDisplayed()
        composeRule.runOnIdle { entries = listOf(battleLogFixture()) }
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete_all)).performClick()
        assertEquals(0, clears)
        composeRule.onAllNodesWithText(context.getString(android.R.string.cancel)).onLast().performClick()
        assertEquals(0, clears)
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete_all)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.battle_log_delete)).performClick()
        assertEquals(1, clears)
        composeRule.onNodeWithText(context.getString(R.string.battle_log_empty)).assertIsDisplayed()
    }
}
