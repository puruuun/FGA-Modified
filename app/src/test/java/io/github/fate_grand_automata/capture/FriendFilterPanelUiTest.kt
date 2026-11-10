package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.FriendManagerContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Side panel on wide windows (apply at once), top panel with a draft on narrow ones (apply with "N칸 보기"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class FriendFilterPanelUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun show(width: Int, height: Int) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h${height}dp-notnight-hdpi")
        compose.setContent { FGATheme { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(5)
    }

    private fun rows(count: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().size == count }
        compose.onAllNodesWithTag("friends-row").assertCountEquals(count)
    }

    private fun openPanel() = compose.onNodeWithTag("friends-filter").performClick()

    private fun inPanel(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag("friends-filter-panel"))

    @Test fun wide1280ShowsTheListBesideARightPanelAndAppliesWithoutAButton() {
        show(1280, 800)
        openPanel()
        val density = compose.density.density
        val panel = compose.onNodeWithTag("friends-filter-panel").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("friends-list").fetchSemanticsNode().boundsInRoot
        assertEquals(1280 * density, panel.right, 2f)
        assertEquals(360 * density, panel.width, 2f)
        assertTrue("the list sits left of the panel", list.right <= panel.left + 1f && list.width > 0f)
        compose.onNodeWithTag("friends-apply").assertDoesNotExist()
        compose.onNodeWithTag("friends-result-count").assertTextEquals("결과 5칸")

        compose.onNodeWithTag("friends-deck-event").performClick()
        rows(1)
        compose.onNodeWithTag("friends-result-count").assertTextEquals("결과 1칸")
        compose.onNodeWithTag("friends-deck-main").performClick()
        rows(5)
        compose.onNodeWithTag("friends-level-100").performScrollTo().performClick()
        rows(0)
        compose.onNodeWithTag("friends-result-count").assertTextEquals("결과 0칸")

        compose.onNodeWithTag("friends-reset").performClick()
        rows(5)
        compose.onNodeWithTag("friends-level-100").performScrollTo().assertIsNotSelected()
        compose.onNodeWithTag("friends-deck-event").performClick()
        rows(1)
        compose.onNodeWithTag("friends-close").performClick()
        compose.onNodeWithTag("friends-filter-panel").assertDoesNotExist()
        rows(1) // closing keeps what was applied
    }

    @Test fun wideFriendNameAppliesAfterTheDebounce() {
        show(1280, 800)
        openPanel()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("friends-name").performTextInput("친구 A")
        compose.mainClock.advanceTimeBy(100)
        compose.onAllNodesWithTag("friends-row").assertCountEquals(5)
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        rows(2)
        compose.onNodeWithTag("friends-result-count").assertTextEquals("결과 2칸")
    }

    @Test fun landscapePhone800x360UsesTheSidePanelAndKeepsTheFooterVisible() {
        show(800, 360)
        openPanel()
        val density = compose.density.density
        val panel = compose.onNodeWithTag("friends-filter-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(800 * density, panel.right, 2f)
        assertEquals(360 * density, panel.height, 2f)
        compose.onNodeWithTag("friends-reset").assertIsDisplayed()
        compose.onNodeWithTag("friends-close").assertIsDisplayed()
        compose.onNodeWithTag("friends-result-count").assertIsDisplayed()
        compose.onNodeWithTag("friends-deck-grand").performClick()
        rows(0)
        compose.onNodeWithTag("friends-panel-x").performClick()
        compose.onNodeWithTag("friends-filter-panel").assertDoesNotExist()
    }

    @Test fun narrowChangesApplyOnlyAfterShowSlotsAndClosingDiscardsThem() {
        show(360, 800)
        openPanel()
        compose.onNodeWithTag("friends-deck-event").performClick()
        compose.onAllNodesWithTag("friends-row").assertCountEquals(5) // the list behind the panel is untouched
        compose.onNodeWithTag("friends-apply").assertTextEquals("1칸 보기")
        compose.onNodeWithTag("friends-panel-x").performClick()
        compose.onNodeWithTag("friends-filter-panel").assertDoesNotExist()
        rows(5)
        openPanel()
        compose.onNodeWithTag("friends-deck-main").assertIsSelected() // the discarded draft is gone
        compose.onNodeWithTag("friends-deck-event").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(1)
    }

    @Test fun narrowPanelHasNoCloseFooterAndKeepsTheOldTopPanel() {
        show(360, 800)
        openPanel()
        compose.onNodeWithTag("friends-close").assertDoesNotExist()
        compose.onNodeWithTag("friends-result-count").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("friends-filter-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bounds.top, 1f)
        assertEquals(360 * compose.density.density, bounds.width, 2f)
    }

    @Test fun loginAmountIsTypeableClampedAndTheUnitIsADropdown() {
        show(1280, 800)
        openPanel()
        compose.onNodeWithTag("friends-login-amount").assertDoesNotExist()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        rows(3) // 3 days or more: friends B (3 d) and C (9 d)
        compose.onNodeWithTag("friends-login-amount").performScrollTo().performTextReplacement("150")
        compose.onNodeWithTag("friends-login-amount").assertTextEquals("99")
        compose.onNodeWithTag("friends-login-plus").assertIsNotEnabled()
        rows(0)
        compose.onNodeWithTag("friends-login-amount").performTextReplacement("a7b")
        compose.onNodeWithTag("friends-login-amount").assertTextEquals("7")
        rows(1) // 7 days or more: only friend C (9 d)
        compose.onNodeWithTag("friends-login-minus").performClick()
        compose.onNodeWithTag("friends-login-amount").assertTextEquals("6")
        compose.onNodeWithTag("friends-login-unit").assertTextContains("일")
        compose.onNodeWithTag("friends-login-unit").performClick()
        compose.onNodeWithTag("friends-unit-Hours").assertIsDisplayed().performClick()
        compose.onNodeWithTag("friends-login-unit").assertTextContains("시간")
        rows(3) // 6 hours or more: B and C
    }

    @Test fun levelDirectionToggleAndLevelChipsApplyAtOnceOnWideWindows() {
        show(1280, 800)
        openPanel()
        compose.onNodeWithTag("friends-level-at-least").assertIsSelected()
        compose.onNodeWithTag("friends-level-100").performScrollTo().performClick()
        rows(0) // every sample slot is Lv90
        compose.onNodeWithTag("friends-level-at-most").performScrollTo().performClick()
        compose.onNodeWithTag("friends-level-at-most").assertIsSelected()
        rows(5)
        compose.onNodeWithTag("friends-level-80").performScrollTo().performClick()
        rows(0)
    }

    @Test fun selectedServantAndCesShowAsRemovableChipsInsideThePanel() {
        show(1280, 800)
        openPanel()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("(세이버) 샘플 세이버 (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("(세이버) 샘플 세이버 (2)").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        rows(2)
        compose.onNode(inPanel(hasText("샘플 세이버") and isSelected())).assertIsDisplayed()
        compose.onNodeWithTag("friends-ce").performScrollTo().performClick()
        compose.onNodeWithText("샘플 예장 하나 (2)").performClick()
        compose.onNodeWithText("샘플 예장 둘 (2)").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        rows(2) // CEs match any of the chosen ones: friend A's Lv90 servant 2 with CE 91 and friend B's with CE 90
        compose.onNodeWithTag("friends-ce").performScrollTo().assertTextContains("예장 2개 선택")
        compose.onNode(inPanel(hasText("샘플 예장 하나") and isSelected())).performScrollTo().assertIsDisplayed()
        // Removing a CE chip inside the panel updates the filter and the list.
        compose.onNode(inPanel(hasText("샘플 예장 하나") and isSelected())).performClick()
        compose.onNode(inPanel(hasText("샘플 예장 하나") and isSelected())).assertDoesNotExist()
        rows(1)
        compose.onNode(inPanel(hasText("샘플 세이버") and isSelected())).performScrollTo().performClick()
        compose.onNode(inPanel(hasText("샘플 세이버") and isSelected())).assertDoesNotExist()
        rows(2)
    }
}
