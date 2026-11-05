package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class LogInteractionsTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val battle = battleLogFixture("battle")
    private val live = outsideFixture()

    @Test fun deleteModeSelectsBothKindsConfirmsAndCancelDropsSelection() {
        val deleted = mutableListOf<String>()
        var clears = 0
        rule.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(battle), deleted::add, { clears++ }, openOutsideEntries = listOf(live)) } }
        rule.onNodeWithText("Delete all").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Delete").assertCountEquals(0)
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete selected").assertIsNotEnabled()
        rule.onNodeWithTag("log-card:battle").performClick()
        rule.onNodeWithTag("log-card:outside:${live.id}").performClick()
        rule.onNodeWithText("Delete selected").performClick()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("Delete 2 selected records?").assertIsDisplayed()
        rule.onAllNodesWithText("Cancel").onLast().performClick() // Dismiss confirmation only.
        rule.onNodeWithText("Delete selected").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(setOf("battle", "outside:${live.id}"), deleted.toSet())
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithTag("log-card:battle").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Delete selected").assertDoesNotExist()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete selected").assertIsNotEnabled()
        rule.onNodeWithText("Delete all").performClick()
        assertEquals(0, clears)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, clears)
    }

    @Test fun timeAndResourceFiltersSurviveRecreationAndDescribeEmptyState() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(battle.copy(endedAt = 1)), {}, {}, outsideNames, outsideEntries = listOf(live.copy(endedAt = 1))) } }
        rule.onNodeWithText("Gained resource: All resources").performClick()
        rule.onNodeWithText("무간의 톱니바퀴").performClick()
        rule.onNodeWithText("Today").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Gained resource: 무간의 톱니바퀴").assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsSelected()
        rule.onNodeWithText("No records match the active filters.").assertIsDisplayed()
    }

    @Test fun currencyFallbackShowsNameAndAccessibilityLabelAndLongPressName() {
        rule.setContent { FGATheme(darkTheme = false) { ResourceTile(LogResource(id = 1, count = 12), GameNameMap()) } }
        rule.onNodeWithText("QP").assertIsDisplayed()
        rule.onNodeWithContentDescription("QP, +12").assertIsDisplayed().performTouchInput { longClick() }
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test fun outsideDetailOmitsUnchangedCurrenciesAndUnusedFruit() {
        val empty = live.copy(period = live.period.copy(total = io.github.fate_grand_automata.scripts.capture.AccountDelta(),
            fruitUsed = mapOf(100L to 0L), sources = emptyList()))
        rule.setContent { FGATheme(darkTheme = false) { OutsideLogDetail(empty, {}, outsideNames) } }
        rule.onNodeWithText("Totals").assertIsDisplayed()
        for (id in iconItemIds) rule.onNodeWithTag("resource:2:$id").assertDoesNotExist()
    }

    @Test fun deleteSelectionSurvivesRecreationAndCancelClearsIt() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(battle), {}, {}, openOutsideEntries = listOf(live)) } }
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithTag("log-card:outside:${live.id}").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-card:outside:${live.id}").assertIsSelected()
        rule.onNodeWithTag("log-card:battle").assertIsNotSelected()
        rule.onNodeWithText("Delete selected").assertIsEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete selected").assertIsNotEnabled()
    }

    @Test fun failedLocalIconFallsBackAndSuccessfulCurrencyIconHasNoNameText() {
        val names = localIconNames(context).copy(itemIcons = localIconNames(context).itemIcons + (3L to "/missing/fake-log-icon.png"))
        val loader = localIconLoader(context)
        rule.setContent { FGATheme(darkTheme = false) {
            CompositionLocalProvider(LocalLogIconLoader provides loader) {
            androidx.compose.foundation.layout.Column {
                ResourceTile(LogResource(id = 1, count = 12), names)
                ResourceTile(LogResource(id = 3, count = 2), names)
            }
            }
        } }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Mana prisms").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Mana prisms").assertIsDisplayed()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("log-icon-loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("QP").assertDoesNotExist()
        rule.onNodeWithContentDescription("QP, +12").assertIsDisplayed()
        rule.onNodeWithContentDescription("QP, +12").performTouchInput { longClick() }
        rule.onNodeWithText("QP").assertIsDisplayed()
    }
}
