package io.github.fate_grand_automata.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class OutsideLogUiTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    @Test fun outsideCardSourcesMatchDetailOrderWithInProgressPrefixAndStayOnOneLine() {
        composeRule.setContent { FGATheme(false) {
            OutsideLogCard(outsideFixture(), false, {}, inProgress = true)
        } }
        val summary = "In progress · Gift box · Enhancement · AP recovery · Summon · Other · unknown/sample"
        composeRule.onNodeWithText(summary).assertExists()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(summary, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
    }

    @Test fun friendPointsShowSignedGroupedTotalsAndSummonBreakdown() {
        assertFriendPointsInTotalsAndSource("Friend points")
    }

    @Test
    @Config(qualifiers = "ko-w360dp-h800dp-hdpi")
    fun friendPointsUseKoreanLabelInTotalsAndSummonBreakdown() {
        assertFriendPointsInTotalsAndSource("프렌드 포인트")
    }

    @Test
    @Config(qualifiers = "ja-w360dp-h800dp-hdpi")
    fun friendPointsUseJapaneseLabelInTotalsAndSummonBreakdown() {
        assertFriendPointsInTotalsAndSource("フレンドポイント")
    }

    private fun assertFriendPointsInTotalsAndSource(label: String) {
        val period = OutsidePeriod("fictionalA", 10)
            .add(ChangeSource.of("gacha/draw"), AccountDelta(friendPoint = -1200), 11)
            .add(ChangeSource.of("present/receive"), AccountDelta(friendPoint = 3600), 12)
        val entry = outsideFixture().copy(period = period)
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(entry, {}) } }
        composeRule.onNodeWithContentDescription("$label, +2,400").assertIsDisplayed()
        val summon = hasTestTag("outside-source:Summon:")
        composeRule.onNode(hasScrollAction()).performScrollToNode(summon)
        composeRule.onNode(hasClickAction() and hasAnyAncestor(summon)).performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("$label, −1,200"))
        composeRule.onNode(hasContentDescription("$label, −1,200") and hasAnyAncestor(summon)).assertIsDisplayed()
    }

    @Test fun mixedListOpensOutsideDetailAndBackReturnsToBothCards() {
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(listOf(battleLogFixture()), {}, {}, outsideNames, outsideEntries = listOf(outsideFixture()))
        } }
        composeRule.onNodeWithTag("log-card:outside:outside-sample").performClick()
        composeRule.onNodeWithText("Totals").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Changes outside scripts", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Daily quests").assertIsDisplayed()
    }

    @Test fun itemListsUseSignedGroupedCountsAndLargestUsageFirst() {
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(outsideFixture(), {}, outsideNames) } }
        composeRule.onNodeWithText("+12").assertExists()
        val fifteen = composeRule.onNodeWithText("−15").fetchSemanticsNode().boundsInRoot.top
        val two = composeRule.onNodeWithText("−2").fetchSemanticsNode().boundsInRoot.top
        assertTrue(fifteen <= two)
        composeRule.onNodeWithText("−10,766,000").assertExists()
        composeRule.onNodeWithText("−30").assertExists()
        composeRule.onNodeWithText("Free −25 · Paid −5").assertExists()
    }

    @Test fun sourcesStartCollapsedExpandIndependentlyAndShowSanitizedUnknownPath() {
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(outsideFixture(), {}, outsideNames) } }
        val giftItems = hasText("Gains (+)") and hasAnyAncestor(hasTestTag("outside-source:GiftBox:"))
        val otherItems = hasText("Gains (+)") and hasAnyAncestor(hasTestTag("outside-source:Other:unknown/sample"))
        composeRule.onNode(giftItems).assertDoesNotExist()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Gift box"))
        composeRule.onNodeWithText("Gift box").performClick()
        composeRule.onNode(giftItems).assertExists()
        composeRule.onNodeWithText("+1,234,000").assertExists()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Other · unknown/sample"))
        composeRule.onNodeWithText("Other · unknown/sample").assertIsDisplayed().performClick()
        composeRule.onNode(otherItems).assertExists()
        composeRule.onNodeWithText("Other · unknown/sample").performClick()
        composeRule.onNode(otherItems).assertDoesNotExist()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Gift box"))
        composeRule.onNode(giftItems).assertExists()
        composeRule.onAllNodes(hasText("discard", substring = true)).assertCountEquals(0)
    }

    @Test fun deletingOutsideAndBattleRoutesTheCorrectIds() {
        var battles by mutableStateOf(listOf(battleLogFixture("battle")))
        var outside by mutableStateOf(listOf(outsideFixture("outside")))
        val deleted = mutableListOf<String>()
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(battles, { id ->
                deleted += id
                if (id.startsWith("outside:")) outside = emptyList() else battles = emptyList()
            }, {}, outsideEntries = outside)
        } }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag("log-card:outside:outside").performClick()
        composeRule.onNodeWithText("Delete selected").performClick()
        composeRule.onNodeWithText("Delete 1 selected records?").assertIsDisplayed()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Changes outside scripts").assertDoesNotExist()
        composeRule.onNodeWithText("Daily quests").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithTag("log-card:battle").performClick()
        composeRule.onNodeWithText("Delete selected").performClick()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        assertEquals(listOf("outside:outside", "battle"), deleted)
    }

    @Test fun clearAllRemovesBothKindsAfterConfirmation() {
        var cleared by mutableStateOf(false)
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(if (cleared) emptyList() else listOf(battleLogFixture()), {}, { cleared = true },
                outsideEntries = if (cleared) emptyList() else listOf(outsideFixture()))
        } }
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete all").performClick()
        composeRule.onNodeWithText("Delete all records?").assertIsDisplayed()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Changes outside scripts").assertDoesNotExist()
        composeRule.onNodeWithText("Daily quests").assertDoesNotExist()
        assertTrue(cleared)
    }

    @Test
    @Config(qualifiers = "ko-w360dp-h800dp-hdpi")
    fun periodUsesTheAppLanguageForAmPm() {
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(emptyList(), {}, {}, outsideEntries = listOf(outsideFixture()))
        } }
        composeRule.onNode(hasText("오전", substring = true) or hasText("오후", substring = true)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ko-w360dp-h800dp-hdpi")
    fun externalPaymentReflectShowsTruckInCardAndDetailWhileOtherPathsKeepPrefix() {
        val period = OutsidePeriod("fictionalA", 10)
            .add(ChangeSource.of("externalPayment/reflect"), AccountDelta(chargeStone = 86, freeStone = 82), 11)
            .add(ChangeSource.of("home/top"), AccountDelta(qp = 1000), 12)
        val entry = outsideFixture().copy(period = period)
        composeRule.setContent { FGATheme(false) {
            LogScreenContent(emptyList(), {}, {}, outsideEntries = listOf(entry))
        } }
        composeRule.onNodeWithText("트럭 · 기타 · home/top").assertIsDisplayed()
        composeRule.onAllNodes(hasText("externalPayment/reflect", substring = true)).assertCountEquals(0)
        composeRule.onNodeWithTag("log-card:outside:${entry.id}").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("outside-source:Other:externalPayment/reflect"))
        composeRule.onNode(hasText("트럭") and hasAnyAncestor(hasTestTag("outside-source:Other:externalPayment/reflect"))).assertIsDisplayed()
        composeRule.onAllNodes(hasText("externalPayment/reflect", substring = true)).assertCountEquals(0)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("outside-source:Other:home/top"))
        composeRule.onNode(hasText("기타 · home/top") and hasAnyAncestor(hasTestTag("outside-source:Other:home/top"))).assertIsDisplayed()
    }
}
