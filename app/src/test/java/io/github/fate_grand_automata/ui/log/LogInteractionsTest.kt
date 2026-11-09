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
        rule.onAllNodesWithContentDescription("Delete").assertCountEquals(1)
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
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-resources").performScrollToNode(hasText("무간의 톱니바퀴"))
        rule.onNodeWithText("무간의 톱니바퀴").performClick()
        rule.onNodeWithText("Today").performClick()
        rule.onNodeWithText("Apply").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Today · 무간의 톱니바퀴").assertIsDisplayed()
        rule.onNodeWithText("No records match the active filters.").assertIsDisplayed()
        rule.onNodeWithTag("log-filter-active", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Clear").performClick()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
    }

    @Test fun currencyFallbackKeepsAccessibilityAndTapOrLongPressShowsNoPopup() {
        rule.setContent { FGATheme(darkTheme = false) { ResourceTile(LogResource(id = 1, count = 12), GameNameMap()) } }
        rule.onNodeWithText("QP").assertIsDisplayed()
        rule.onNodeWithContentDescription("QP, +12").assertIsDisplayed().assertHasNoClickAction().performTouchInput { click(); longClick() }
        rule.onAllNodes(isDialog()).assertCountEquals(0)
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
        java.io.File(context.filesDir, "capture/icons/3.png").delete()
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
        rule.onNodeWithText("QP").assertDoesNotExist()
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun loweringRetentionRequiresConfirmationAndCancelKeepsRecordsForBothKinds() {
        val changes = mutableListOf<Int>()
        val battles = (1..70).map { battle.copy(id = "b$it") }
        val outside = (1..80).map { live.copy(id = "o$it") }
        rule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(battles, {}, {}, outsideEntries = outside, onRetention = changes::add)
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithText("200").assertIsSelected()
        rule.onNodeWithText("50").performScrollTo().performClick()
        rule.onNodeWithText("Apply").performClick()
        assertTrue(changes.isEmpty())
        rule.onNodeWithText("Keep the newest 50 battle records and 50 outside records? Older records will be permanently deleted.").assertIsDisplayed()
        rule.onAllNodesWithText("Cancel").onLast().performClick()
        assertTrue(changes.isEmpty())
        rule.onNodeWithText("Apply").performClick()
        rule.onAllNodesWithText("Apply").onLast().performClick()
        assertEquals(listOf(50), changes)
        rule.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun raisingRetentionDoesNotAskToTrimAndDialogDraftSurvivesRotation() {
        val changes = mutableListOf<Int>()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(darkTheme = false) { LogScreenContent(listOf(battle), {}, {}, retention = 100, onRetention = changes::add) } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithText("200").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("200").performScrollTo().assertIsSelected()
        rule.onNodeWithText("Apply").performClick()
        assertEquals(listOf(200), changes)
    }

    @Test fun customRangeDraftSurvivesRotationAndApplyPassesTheInclusiveDates() {
        val day = java.time.Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
        var applied = false
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(darkTheme = false) {
            LogFilterDialog(LogTimeFilter.Custom, "2:6510", day, day, 200, 0, 0, listOf(CaptureLogItem(outside = live.copy(endedAt = day))), outsideNames, null, {},
                { time, resource, start, end, retention, _ ->
                    assertEquals(LogTimeFilter.Custom, time)
                    assertEquals("2:6510", resource)
                    assertEquals(day, start); assertEquals(day, end); assertEquals(200, retention)
                    applied = true
                })
        } }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Custom range").assertIsSelected().performClick()
        rule.onAllNodes(isDialog()).assertCountEquals(2)
        rule.onAllNodesWithText("Cancel").onLast().performClick()
        rule.onNodeWithText("Custom range").performClick()
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertTrue(applied)
    }
}
