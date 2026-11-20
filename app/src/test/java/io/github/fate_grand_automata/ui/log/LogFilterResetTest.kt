package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
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
class LogFilterResetTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    private fun out(id: String, item: Long) = outsideFixture(id).copy(period = OutsidePeriod("fictionalA", 1)
        .add(ChangeSource.of("present/receive"), AccountDelta(items = mapOf(item to 1L)), 2))
    private val gear = out("gear", 6510)
    private val gem = out("gem", 6001)
    private val fruit = out("fruit", 7000)
    private val entries = listOf(gear, gem, fruit)
    private fun card(id: String) = rule.onNodeWithTag("log-card:outside:$id")
    private fun chip(key: String) = rule.onNodeWithTag("log-filter-resource:$key")
    private val names = GameNameMap(items = mapOf(6510L to "무간의 톱니바퀴", 6001L to "검의 휘석", 7000L to "황금색 과일"))
    private fun pick(key: String) { chip(key).performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
    private fun openFilter() = rule.onNodeWithTag("log-filter-button").performClick()
    private fun showScreen() = rule.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}, names, outsideEntries = entries) } }
    private fun applySelection(vararg keys: String) {
        openFilter()
        keys.forEach { pick(it) }
        rule.onNodeWithText("Apply").performClick()
    }

    private val dialogKeys = (1L..4L).map { "${LogResource.SVT}:$it" }.toSet()
    private val dialogNames = GameNameMap(svtData = (1L..4L).associateWith { AtlasBasic(it, "Servant $it", "normal") })

    @Test fun resetRestoresEveryDraftAndKeepsRetention() {
        var applied: List<Any?>? = null
        rule.setContent { FGATheme(false) {
            LogFilterDialog(LogTimeFilter.Today, setOf("${LogResource.SVT}:1"), null, null, 200, 0, 0, resourceLogFixture(dialogKeys), dialogNames, null, {},
                { time, resources, start, end, retention, profile, source -> applied = listOf(time, resources, start, end, retention, profile, source) },
                source = LogSourceFilter.GiftBox)
        } }
        rule.onNodeWithText("100").performScrollTo().performClick()
        rule.onNodeWithTag("log-filter-search").performTextInput("serv")
        rule.onNodeWithTag("log-filter-reset").performClick()
        rule.onNodeWithTag("log-filter-time:All").assertIsSelected()
        rule.onNodeWithTag("log-filter-source:all").assertIsSelected()
        rule.onNodeWithTag("log-filter-resource:all").assertIsSelected()
        assertEquals("", rule.onNodeWithTag("log-filter-search").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        rule.onNodeWithText("Apply").performClick()
        rule.runOnIdle { assertEquals(listOf(LogTimeFilter.All, emptySet<String>(), null, null, 100, null, null), applied) }
    }

    @Test fun resetIsDisabledAtDefaultsAndEnabledOnceADraftChanges() {
        rule.setContent { FGATheme(false) {
            LogFilterDialog(LogTimeFilter.All, emptySet(), null, null, 200, 0, 0, resourceLogFixture(dialogKeys), dialogNames, null, {}, { _, _, _, _, _, _, _ -> })
        } }
        rule.onNodeWithTag("log-filter-reset").assertIsNotEnabled()
        rule.onNodeWithTag("log-filter-time:Today").performScrollTo().performClick()
        rule.onNodeWithTag("log-filter-reset").assertIsEnabled().performClick()
        rule.onNodeWithTag("log-filter-reset").assertIsNotEnabled()
        rule.onNodeWithTag("log-filter-search").performTextInput("x")
        rule.onNodeWithTag("log-filter-reset").assertIsEnabled()
    }

    @Test fun appliedFilterChangesOnlyOnApplyAndHasNoClearButton() {
        showScreen()
        applySelection("2:6510")
        rule.onNodeWithTag("log-filter-summary").assertExists()
        rule.onNodeWithText("Clear").assertDoesNotExist()
        card("gem").assertDoesNotExist()
        openFilter()
        rule.onNodeWithTag("log-filter-reset").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("log-filter-summary").assertExists()
        card("gem").assertDoesNotExist()
        openFilter()
        rule.onNodeWithTag("log-filter-reset").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        card("gem").assertExists()
    }

    @Test fun twoResourcesMatchWithOrAndAppearInTheSummary() {
        showScreen()
        applySelection("2:6510", "2:6001")
        card("gear").assertExists()
        card("gem").assertExists()
        card("fruit").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-summary").assertIsDisplayed()
        rule.onNodeWithText("무간의 톱니바퀴 · 검의 휘석").assertIsDisplayed()
    }

    @Test fun chipsToggleAndAllClearsTheSelection() {
        showScreen()
        openFilter()
        rule.onNodeWithTag("log-filter-resource:all").assertIsSelected()
        pick("2:6510")
        pick("2:6001")
        chip("2:6510").assertIsSelected()
        chip("2:6001").assertIsSelected()
        rule.onNodeWithTag("log-filter-resource:all").assertIsNotSelected()
        pick("2:6510")
        chip("2:6510").assertIsNotSelected()
        chip("2:6001").assertIsSelected()
        rule.onNodeWithTag("log-filter-resource:all").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        chip("2:6001").assertIsNotSelected()
        rule.onNodeWithTag("log-filter-resource:all").assertIsSelected()
    }

    @Test fun selectedChipsStayVisibleWhileSearching() {
        showScreen()
        openFilter()
        pick("2:6510")
        pick("2:6001")
        rule.onNodeWithTag("log-filter-search").performTextInput("황금")
        chip("2:7000").assertExists()
        chip("2:6510").assertExists()
        chip("2:6001").assertExists()
        rule.onNodeWithTag("log-filter-search").performTextClearance()
        rule.onNodeWithTag("log-filter-search").performTextInput("검의")
        chip("2:7000").assertDoesNotExist()
        chip("2:6510").assertExists()
    }

    @Test fun resourceSetSurvivesRecreationInTheDraftAndTheAppliedFilter() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}, names, outsideEntries = entries) } }
        openFilter()
        pick("2:6510")
        pick("2:7000")
        restoration.emulateSavedInstanceStateRestore()
        chip("2:6510").assertIsSelected()
        chip("2:7000").assertIsSelected()
        chip("2:6001").assertIsNotSelected()
        rule.onNodeWithText("Apply").performClick()
        restoration.emulateSavedInstanceStateRestore()
        card("gem").assertDoesNotExist()
        card("gear").assertExists()
        card("fruit").assertExists()
    }

    @Test fun resourceMissingFromTheAvailableListIsIgnored() {
        var current by mutableStateOf(entries)
        rule.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}, names, outsideEntries = current) } }
        applySelection("2:6001")
        rule.onNodeWithTag("log-filter-summary").assertExists()
        card("gear").assertDoesNotExist()
        rule.runOnIdle { current = listOf(gear, fruit) }
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        card("gear").assertExists()
        card("fruit").assertExists()
    }

    @Test fun summaryNamesFirstTwoAndCountsTheRestBeyondThree() {
        val many = (1L..4L).map { out("n$it", 6500L + it) }
        val names = GameNameMap(items = (1L..4L).associate { 6500L + it to "Item $it" })
        rule.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}, names, outsideEntries = many) } }
        applySelection("2:6501", "2:6502", "2:6503", "2:6504")
        rule.onNodeWithText("Item 1 · Item 2 and 2 more").assertIsDisplayed()
    }
}
