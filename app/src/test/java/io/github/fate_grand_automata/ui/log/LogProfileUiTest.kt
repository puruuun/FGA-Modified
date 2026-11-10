package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
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
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class LogProfileUiTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val battle = battleLogFixture("battle").copy(accountId = "a", accountName = "Profile A")
    private val other = battleLogFixture("other").copy(accountId = "b", accountName = "Profile B")
    private val legacy = battleLogFixture("legacy")
    private val outside = outsideFixture().copy(accountId = "a", accountName = "Profile A")

    @Test fun appliedProfileSurvivesRecreationAndFiltersBothKindsAndLegacy() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) {
            LogScreenContent(listOf(battle, other, legacy), {}, {}, outsideEntries = listOf(outside))
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:a").performClick()
        rule.onNodeWithText("Apply").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-card:battle").assertExists()
        rule.onNodeWithTag("log-card:outside:${outside.id}").assertExists()
        rule.onNodeWithTag("log-card:other").assertDoesNotExist()
        rule.onNodeWithTag("log-card:legacy").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:a").assertIsSelected()
        rule.onNodeWithTag("log-filter-profile:all").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithTag("log-card:legacy").assertExists()
    }

    @Test fun savedProfileSurvivesRestoreWithEmptyLogsBeforeEntriesArrive() {
        var loaded by mutableStateOf(true)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) {
            LogScreenContent(if (loaded) listOf(battle, other, legacy) else emptyList(), {}, {},
                outsideEntries = if (loaded) listOf(outside) else emptyList())
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:a").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.runOnIdle { loaded = false }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:all").assertIsSelected()
        rule.runOnIdle { loaded = true }
        rule.onNodeWithTag("log-filter-profile:a").assertIsSelected()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithTag("log-card:battle").assertExists()
        rule.onNodeWithTag("log-card:outside:${outside.id}").assertExists()
        rule.onNodeWithTag("log-card:other").assertDoesNotExist()
        rule.onNodeWithTag("log-card:legacy").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:a").assertIsSelected()
    }

    @Test fun savedDialogProfileSurvivesEmptyLogsBeforeEntriesArrive() {
        var entries by mutableStateOf(emptyList<CaptureLogItem>())
        var appliedProfile: String? = null
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) {
            LogFilterDialog(LogTimeFilter.All, null, null, null, 200, 0, 0,
                entries, outsideNames, "a", {}, { _, _, _, _, _, profile -> appliedProfile = profile })
        } }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-filter-profile:all").assertIsSelected()
        rule.runOnIdle { entries = mixedCaptureLogs(listOf(battle, other, legacy), listOf(outside)) }
        rule.onNodeWithTag("log-filter-profile:a").assertIsSelected()
        rule.onNodeWithText("Apply").performClick()
        rule.runOnIdle { org.junit.Assert.assertEquals("a", appliedProfile) }
    }
    @Test fun removingSelectedProfilesLastLogFallsBackToAll() {
        var battles by mutableStateOf(listOf(battle, other, legacy))
        rule.setContent { FGATheme(false) { LogScreenContent(battles, {}, {}) } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:a").performClick()
        rule.onNodeWithText("Apply").performClick()
        rule.runOnIdle { battles = listOf(other, legacy) }
        rule.onNodeWithTag("log-card:other").assertExists()
        rule.onNodeWithTag("log-card:legacy").assertExists()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:all").assertIsSelected()
    }

    @Test fun profileDraftSurvivesRecreationAndResourceChoicesFollowProfileAndTime() {
        val now = System.currentTimeMillis()
        val recent = outside.copy(endedAt = now, period = OutsidePeriod("a", 1)
            .add(ChangeSource.of("present/receive"), AccountDelta(items = mapOf(6510L to 1L)), 2))
        val old = recent.copy(id = "old", endedAt = 1, period = OutsidePeriod("a", 1)
            .add(ChangeSource.of("present/receive"), AccountDelta(items = mapOf(6001L to 1L)), 2))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FGATheme(false) {
            LogScreenContent(listOf(other), {}, {}, outsideNames, outsideEntries = listOf(recent, old))
        } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:b").performClick()
        rule.onNodeWithTag("log-filter-resource:2:6510").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-profile:a").performClick()
        rule.onNodeWithTag("log-filter-resource:2:6001").assertExists()
        rule.onNodeWithText("Today").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-filter-profile:a").assertIsSelected()
        rule.onNodeWithTag("log-filter-resource:2:6510").assertExists()
        rule.onNodeWithTag("log-filter-resource:2:6001").assertDoesNotExist()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithTag("log-card:outside:${recent.id}").assertExists()
        rule.onNodeWithTag("log-card:outside:${old.id}").assertDoesNotExist()
    }

    @Test fun noProfileChipAppearsOnlyWithAnIdlessLogAndFiltersAndSummarizes() {
        var battles by mutableStateOf(listOf(battle, other))
        rule.setContent { FGATheme(false) { LogScreenContent(battles, {}, {}) } }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:none").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { battles = listOf(battle, other, legacy) }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:none").assertExists().performClick()
        rule.onNodeWithTag("log-filter-profile:none").assertIsSelected()
        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithTag("log-card:legacy").assertExists()
        rule.onNodeWithTag("log-card:battle").assertDoesNotExist()
        rule.onNodeWithTag("log-card:other").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-summary").assertExists()
        rule.onNodeWithText("No profile").assertExists()
        rule.runOnIdle { battles = listOf(battle, other) }
        rule.onNodeWithTag("log-card:battle").assertExists()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
    }
}
