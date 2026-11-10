package io.github.fate_grand_automata.ui.log

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class LogSourceUiTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val outside = outsideFixture().copy(period = OutsidePeriod("fictionalA", 1)
        .add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 100), 2)
        .add(ChangeSource(ChangeSource.Group.Summon, script = true), AccountDelta(friendPoint = -200), 3))

    @Test fun sourceDraftAndAppliedFilterSurviveRestoreAndSummaryCanClearIt() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) {
            LogScreenContent(listOf(battleLogFixture()), {}, {}, outsideEntries = listOf(outside,
                outside.copy(id = "manual", period = OutsidePeriod("fictionalA", 1)
                    .add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 1), 2))))
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-filter-source:Script").assertIsSelected()
        rule.onNodeWithTag("log-filter-source:Shop").assertIsNotSelected()
        rule.onNodeWithText("적용").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-filter-summary").assertExists()
        rule.onNodeWithText("스크립트").assertExists()
        rule.onNodeWithTag("log-filter-active", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("log-card:outside:manual").assertDoesNotExist()
        rule.onNodeWithTag("log-card:outside:" + outside.id).assertExists()
        rule.onNodeWithTag("log-card:" + battleLogFixture().id).assertExists()
        rule.onNodeWithText("해제").performClick()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        rule.onNodeWithTag("log-card:outside:manual").assertExists()
    }

    @Test fun sourceAllShowsBothKindsAndCancelKeepsTheAppliedChoice() {
        rule.setContent { FGATheme(false) {
            LogScreenContent(listOf(battleLogFixture()), {}, {}, outsideEntries = listOf(outside))
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Shop").performScrollTo().performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-card:" + battleLogFixture().id).assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        rule.onNodeWithText("취소").performClick()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Shop").assertIsSelected()
        rule.onNodeWithTag("log-filter-source:all").performScrollTo().performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        rule.onNodeWithTag("log-card:" + battleLogFixture().id).assertExists()
        rule.onNodeWithTag("log-card:outside:" + outside.id).assertExists()
    }
}
