package io.github.fate_grand_automata.capture

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.FriendManagerContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w1280dp-h800dp-hdpi")
class FriendSlotFilterUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun show(width: Int = 1280) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-notnight-hdpi")
        compose.setContent { FGATheme { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
    }

    private fun rows(count: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().size == count }
    }

    private fun slot(type: String, number: Int, state: String) {
        compose.onNodeWithTag("friends-$type-$number").performScrollTo().performClick()
        compose.onNodeWithTag("friends-$type-$number-$state").assertIsDisplayed().performClick()
    }

    private fun panelChip(text: String) = compose.onNode(hasText(text) and isSelected() and hasAnyAncestor(hasTestTag("friends-filter-panel")))

    @Test fun servantPickerStaysOpenTogglesAndChipsRemoveOneServant() {
        show()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-1").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        rows(4)
        compose.onNodeWithTag("friends-pick-1").performClick()
        rows(2)
        compose.onNodeWithTag("friends-pick-1").performClick()
        compose.onNodeWithTag("friends-picker-done").assertTextEquals("완료").performClick()
        panelChip("샘플 캐스터").performScrollTo().performClick()
        panelChip("샘플 캐스터").assertDoesNotExist()
        panelChip("샘플 세이버").assertExists()
        rows(2)
        compose.onNodeWithTag("friends-servant").performScrollTo().performClick()
        compose.onNodeWithText("모든 서번트").performClick()
        rows(5)
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        compose.onNodeWithTag("friends-close").performClick()
        compose.onNode(hasText("샘플 세이버") and isSelected()).performClick()
        rows(5)
    }

    @Test fun everySlotMenuSetsAllTenAndNotTenAndChipRemovalKeepsOtherSlots() {
        show()
        for ((type, count) in listOf("skill" to 3, "append" to 5)) {
            for (number in 1..count) {
                slot(type, number, "Ten")
                compose.onNodeWithTag("friends-$type-$number").assertContentDescriptionEquals("${if (type == "skill") "스킬" else "어펜드"}$number 10")
                slot(type, number, "NotTen")
                slot(type, number, "All")
            }
        }
        slot("skill", 1, "Ten")
        slot("append", 2, "NotTen")
        rows(5) // includes locked appends
        panelChip("스킬1 10").performScrollTo().performClick()
        panelChip("어펜드2 10 아님").assertExists()
        compose.onNodeWithTag("friends-close").performClick()
        compose.onNode(hasText("어펜드2 10 아님") and isSelected()).performClick()
        compose.onNode(hasText("어펜드2 10 아님") and isSelected()).assertDoesNotExist()
        rows(5)
    }

    @Test fun wideFiltersApplyImmediatelyAndResetClearsServantsAndSlots() {
        show()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        slot("skill", 3, "Ten")
        rows(0)
        slot("skill", 3, "NotTen")
        rows(2)
        slot("append", 2, "Ten")
        rows(0)
        compose.onNodeWithTag("friends-reset").performClick()
        rows(5)
        compose.onNodeWithTag("friends-skill-3").performScrollTo().assertContentDescriptionEquals("스킬3 전체")
        compose.onNodeWithTag("friends-append-2").assertContentDescriptionEquals("어펜드2 전체")
        panelChip("샘플 세이버").assertDoesNotExist()
    }

    @Test fun narrowDraftWaitsForApplyAndResetClearsTheDraft() {
        show(360)
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-1").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        slot("skill", 3, "Ten")
        rows(5)
        compose.onNodeWithTag("friends-apply").assertTextEquals("0칸 보기").performClick()
        rows(0)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithText("초기화").performClick()
        rows(0)
        compose.onNodeWithTag("friends-apply").assertTextEquals("5칸 보기").performClick()
        rows(5)
    }

    @Test fun savedMultiServantAndSlotFiltersRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FGATheme { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-1").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        slot("skill", 1, "Ten")
        slot("append", 5, "NotTen")
        restoration.emulateSavedInstanceStateRestore()
        rows(4)
        panelChip("샘플 캐스터").assertExists()
        panelChip("샘플 세이버").assertExists()
        panelChip("스킬1 10").assertExists()
        panelChip("어펜드5 10 아님").assertExists()
    }

    @Test fun eventAndGrandKeepSlotConditionsWhenFormationChanges() {
        compose.setContent { FGATheme { FriendManagerContent(grandTestRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        slot("skill", 3, "Ten")
        slot("append", 5, "NotTen")
        rows(0)
        compose.onNodeWithTag("friends-deck-event").performScrollTo().performClick()
        rows(0)
        compose.onNodeWithTag("friends-deck-grand").performClick()
        rows(3) // Grand skills are 10/10/10 and append 5 is locked.
        panelChip("스킬3 10").assertExists()
        panelChip("어펜드5 10 아님").assertExists()
        slot("append", 5, "Ten")
        rows(0)
    }

    @Test fun japaneseSlotStatesAndMultiPickerUseLocalizedLabels() {
        RuntimeEnvironment.setQualifiers("ja-w1280dp-h800dp-notnight-hdpi")
        compose.setContent { FGATheme { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-1").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").assertTextEquals("完了").performClick()
        slot("append", 2, "NotTen")
        panelChip("アペンド2 10以外").assertExists()
        rows(4)
    }

    @Test fun oldSingleServantSavedStateRestoresAsOneSelectedServant() {
        restoreLegacyServant(2L)
        rows(2)
        panelChip("샘플 세이버").assertExists()
        compose.onNodeWithTag("friends-servant").performScrollTo().performClick()
        compose.onNodeWithTag("friends-pick-1").performClick()
        rows(4)
    }

    @Test fun oldUnselectedServantSavedStateRestoresAllServants() {
        restoreLegacyServant(null)
        rows(5)
        compose.onNodeWithTag("friends-servant").performScrollTo().assertTextEquals("모든 서번트")
    }

    private fun restoreLegacyServant(oldId: Long?) {
        var visible by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (visible) FGATheme { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) }
            }
        }
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-pick-2").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        var replaced = false
        val saved = registry.performSave().mapValues { (_, values) -> values.map { value ->
            if (value is MutableState<*> && value.value == arrayListOf(2L)) {
                replaced = true
                mutableStateOf(oldId) // shape written by the old single-id rememberSaveable
            } else value
        } }
        assertTrue("replaced the selected-servant saved value", replaced)
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; visible = true }
    }
}
