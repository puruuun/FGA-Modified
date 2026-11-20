package io.github.fate_grand_automata.ui.log

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.OutsideLogEntry
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.OffsetDateTime

private fun kst(iso: String) = OffsetDateTime.parse("${iso}+09:00").toInstant().toEpochMilli()

internal val dayBattleToday: BattleLogEntry = battleLogFixture("today").copy(
    endedAt = kst("2026-11-11T10:00:00"), accountId = "A", accountName = "샘플", configName = "이벤트 주회",
    gains = RunGains(items = listOf(GainedItem(2, 6510, 3)), qp = 50_000))
internal val dayBattleOlder: BattleLogEntry = battleLogFixture("older").copy(
    endedAt = kst("2026-11-10T20:00:00"), accountId = "A", accountName = "샘플", configName = "일일 퀘스트", gains = null)
internal val dayOutside: OutsideLogEntry = OutsideLogEntry("out", "A", "샘플", kst("2026-11-11T09:00:00"), kst("2026-11-11T09:30:00"),
    OutsidePeriod("A", kst("2026-11-11T09:00:00") / 1000).add(ChangeSource.of("present/receive"), AccountDelta(qp = 1_000), kst("2026-11-11T09:10:00") / 1000))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class LogDayUiTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val back get() = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.navigate_back)

    private fun chooseView(view: String) {
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-$view").performClick()
        rule.onNodeWithText("적용").performClick()
    }

    @androidx.compose.runtime.Composable private fun Screen() = FGATheme(false) {
        LogScreenContent(listOf(dayBattleToday, dayBattleOlder), {}, {}, outsideEntries = listOf(dayOutside), accountServers = mapOf("A" to "JP"))
    }

    @Test fun switchSelectDayOpenBattleAndGoBackOnANarrowScreen() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-card:today").assertExists()
        chooseView("day")
        rule.onNodeWithTag("log-card:today").assertDoesNotExist()
        rule.onNodeWithContentDescription("삭제").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithTag("log-day-account:A").assertExists()
        rule.onNodeWithText("JP").assertExists()
        rule.onNodeWithTag("log-day-expand:A").performClick()
        rule.onNodeWithTag("log-script:today").performClick()
        rule.onNodeWithTag("log-day-detail").assertDoesNotExist()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day-detail").assertExists()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
        chooseView("record")
        rule.onNodeWithTag("log-card:today").assertExists()
    }

    @Test fun theIncludedBattleCountAndTheEmptySectionLinesShow() {
        rule.setContent { Screen() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithText("전투 1회 포함").assertExists()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").performClick()
        rule.onNodeWithTag("log-day-no-scripts").assertDoesNotExist()
        rule.onNodeWithTag("log-script:older").assertExists()
    }

    @Test fun aRowIsTwoLinesTheDateThenTheAccountAndTheSummary() {
        rule.setContent { Screen() }
        chooseView("day")
        val row = rule.onNodeWithTag("log-day:2026-11-11/a:A")
        row.assertTextContains("11월 11일", substring = true)
        row.assertTextContains("JP", substring = true)
        val line2 = hasParent(hasTestTag("log-day-line2:2026-11-11/a:A"))
        rule.onNode(line2.and(hasText("샘플")), useUnmergedTree = true).assertExists()
        rule.onNode(line2.and(hasText("전투 1", substring = true)), useUnmergedTree = true).assertExists()
        rule.onNode(line2.and(hasText("11월", substring = true)), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun scriptItemsAreNotCardsAndTappingOneOpensTheBattleDetail() {
        rule.setContent { Screen() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithTag("log-script:today").assertExists().assertHasClickAction()
        rule.onNodeWithTag("log-card:today").assertDoesNotExist()
        rule.onNodeWithTag("log-script:today").performClick()
        rule.onNodeWithTag("log-day-detail").assertDoesNotExist()
    }

    @Test fun theRecordViewStillUsesTheCards() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-card:today").assertExists()
        rule.onNodeWithTag("log-script:today").assertDoesNotExist()
    }

    @Test fun theExpandedBreakdownUsesTheDayViewLabel() {
        rule.setContent { Screen() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithText("출처별 내역 접기").assertDoesNotExist()
        rule.onNodeWithTag("log-day-expand:A").performClick()
        rule.onNodeWithText("출처별 내역 접기").assertExists()
        rule.onNodeWithText("전투별 내역 접기").assertDoesNotExist()
    }

    @Test fun theViewAndTheSelectedDaySurviveRecreation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Screen() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-10/a:A").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-day-detail").assertExists()
        rule.onNodeWithTag("log-script:older").assertExists()
    }

    @Test @Config(qualifiers = "ko-w1000dp-h700dp-hdpi") fun wideScreenShowsTheFirstDayAtOnceAndSwitchesPanes() {
        rule.setContent { Screen() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertIsSelected()
        rule.onNodeWithTag("log-day-account:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").performClick()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertIsSelected()
        rule.onNodeWithTag("log-script:older").assertExists()
        rule.onNodeWithTag("log-script:older").performClick()
        rule.onNodeWithTag("log-day-detail").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertExists()
    }

    @Test fun theDayViewGroupsOnlyWhatPassesTheActiveFilter() {
        val later = dayOutside.copy(id = "later", startedAt = kst("2026-11-12T09:00:00"), endedAt = kst("2026-11-12T09:30:00"),
            period = OutsidePeriod("A", kst("2026-11-12T09:00:00") / 1000).add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 5), kst("2026-11-12T09:10:00") / 1000))
        rule.setContent { FGATheme(false) { LogScreenContent(listOf(dayBattleToday), {}, {}, outsideEntries = listOf(later)) } }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-12/a:A").assertExists()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-day:2026-11-12/a:A").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
    }

    @Test fun theHeadingNoLongerHasViewChipsAndTheDialogChoiceTakesEffectOnlyOnApply() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-view-day").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-record").assertIsSelected()
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-view-day").assertIsSelected()
        rule.onNodeWithTag("log-card:today").assertExists()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-card:today").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
    }

    @Test fun cancelKeepsTheCurrentView() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithText("취소").performClick()
        rule.onNodeWithTag("log-card:today").assertExists()
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        chooseView("day")
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-day").assertIsSelected()
        rule.onNodeWithTag("log-view-record").performClick()
        rule.onNodeWithText("취소").performClick()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
    }

    @Test fun resetKeepsTheViewAndStaysDisabledWhenOnlyTheViewChanged() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-filter-reset").assertIsNotEnabled()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        rule.onNodeWithTag("log-filter-reset").assertIsEnabled().performClick()
        rule.onNodeWithTag("log-view-day").assertIsSelected()
        rule.onNodeWithTag("log-filter-reset").assertIsNotEnabled()
    }

    @Test fun theSummaryShowsByDayOnlyWhileTheDayViewIsOn() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
        chooseView("day")
        rule.onNode(hasText("날짜별") and hasAnyAncestor(hasTestTag("log-filter-summary"))).assertExists()
        rule.onNodeWithTag("log-filter-active").assertDoesNotExist()
        chooseView("record")
        rule.onNodeWithTag("log-filter-summary").assertDoesNotExist()
    }

    @Test fun theDayViewHasNoDeleteModeAndTheFilterButtonIsHiddenWhileDeleting() {
        rule.setContent { Screen() }
        rule.onNodeWithContentDescription("삭제").performClick()
        rule.onNodeWithText("선택 삭제").assertExists()
        rule.onNodeWithTag("log-filter-button").assertDoesNotExist()
        rule.onNodeWithText("취소").performClick()
        chooseView("day")
        rule.onNodeWithText("선택 삭제").assertDoesNotExist()
        rule.onNodeWithContentDescription("삭제").assertDoesNotExist()
    }

    private val second = dayBattleToday.copy(id = "b", accountId = "B", accountName = "둘째", configName = "다른 주회",
        gains = RunGains(items = listOf(GainedItem(2, 6510, 9)), qp = 70_000))
    private val noProfile = dayBattleToday.copy(id = "n", accountId = null, accountName = null, configName = "무계정 주회", gains = null)

    @androidx.compose.runtime.Composable private fun TwoAccounts() = FGATheme(false) {
        LogScreenContent(listOf(dayBattleToday, second, dayBattleOlder), {}, {}, outsideEntries = listOf(dayOutside),
            accountServers = mapOf("A" to "JP", "B" to "KR"))
    }

    @Test fun twoAccountsOnADayGiveTwoRowsAndEachDetailShowsOnlyItsOwn() {
        rule.setContent { TwoAccounts() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/a:B").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithTag("log-day-account:A").assertExists()
        rule.onNodeWithTag("log-day-account:B").assertDoesNotExist()
        rule.onNodeWithTag("log-script:today").assertExists()
        rule.onNodeWithTag("log-script:b").assertDoesNotExist()
        rule.onNodeWithText("JP").assertExists()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day:2026-11-11/a:B").performClick()
        rule.onNodeWithTag("log-day-account:B").assertExists()
        rule.onNodeWithTag("log-day-account:A").assertDoesNotExist()
        rule.onNodeWithTag("log-script:b").assertExists()
        rule.onNodeWithTag("log-script:today").assertDoesNotExist()
        rule.onNodeWithText("KR").assertExists()
    }

    @Test fun theProfileFilterKeepsOnlyThatAccountsRowsAcrossDays() {
        rule.setContent { TwoAccounts() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertExists()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:B").performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-day:2026-11-11/a:B").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-profile:A").performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-10/a:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/a:B").assertDoesNotExist()
    }

    @Test fun recordsWithoutAnAccountGetTheirOwnNoProfileRowLast() {
        rule.setContent { FGATheme(false) { LogScreenContent(listOf(dayBattleToday, noProfile), {}, {}) } }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/none").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11/none").assertTextContains("프로필 없음", substring = true)
        rule.onNodeWithTag("log-day:2026-11-11/none").performClick()
        rule.onNodeWithTag("log-script:n").assertExists()
        rule.onNodeWithTag("log-script:today").assertDoesNotExist()
    }

    @Test fun theSelectedRowOfASecondAccountSurvivesRecreation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { TwoAccounts() }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:B").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-day-account:B").assertExists()
        rule.onNodeWithTag("log-script:b").assertExists()
    }

    @Test @Config(qualifiers = "ko-w1000dp-h700dp-hdpi") fun aFilterChangeThatRemovesTheSelectedRowSelectsTheFirstOne() {
        val later = dayOutside.copy(id = "later", startedAt = kst("2026-11-12T09:00:00"), endedAt = kst("2026-11-12T09:30:00"),
            period = OutsidePeriod("A", kst("2026-11-12T09:00:00") / 1000).add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 5), kst("2026-11-12T09:10:00") / 1000))
        rule.setContent { FGATheme(false) { LogScreenContent(listOf(dayBattleToday), {}, {}, outsideEntries = listOf(later)) } }
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-12/a:A").performClick()
        rule.onNodeWithTag("log-day:2026-11-12/a:A").assertIsSelected()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-day:2026-11-12/a:A").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11/a:A").assertIsSelected()
        rule.onNodeWithTag("log-script:today").assertExists()
    }

    @Test fun anEmptyDayViewShowsTheNormalEmptyText() {
        rule.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}) } }
        chooseView("day")
        rule.onNodeWithText("전투 기록이 없습니다", substring = true).assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LogDayScreenshotTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    @androidx.compose.runtime.Composable private fun Screen() =
        LogScreenContent(listOf(dayBattleToday, dayBattleOlder), {}, {}, outsideEntries = listOf(dayOutside), accountServers = mapOf("A" to "JP"))

    private fun chooseView(view: String) {
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-$view").performClick()
        rule.onNodeWithText("적용").performClick()
    }

    @Test fun narrowDay() = rule.shoot("log-day", "NarrowDayDetail", 360, 800, false, "ko", beforeCapture = {
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:A").performClick()
        rule.onNodeWithTag("log-day-expand:A").performClick()
    }) { Screen() }

    @Test fun wideDay() = rule.shoot("log-day", "WideDayView", 1280, 720, false, "ko", beforeCapture = {
        chooseView("day")
        rule.onNodeWithTag("log-day-expand:A").performClick()
    }) { Screen() }

    @Test fun narrowTwoAccounts() = rule.shoot("log-day", "NarrowTwoAccounts", 360, 800, false, "ko", beforeCapture = {
        chooseView("day")
        rule.onNodeWithTag("log-day:2026-11-11/a:B").performClick()
    }) {
        LogScreenContent(listOf(dayBattleToday, dayBattleToday.copy(id = "b", accountId = "B", accountName = "둘째", configName = "다른 주회")),
            {}, {}, outsideEntries = listOf(dayOutside), accountServers = mapOf("A" to "JP", "B" to "KR"))
    }

    @Test fun narrowRowList() = rule.shoot("log-day", "NarrowRowList", 360, 800, false, "ko", beforeCapture = {
        chooseView("day")
    }) {
        LogScreenContent(listOf(dayBattleToday, dayBattleToday.copy(id = "b", accountId = "B", accountName = "둘째", configName = "다른 주회"), dayBattleOlder),
            {}, {}, outsideEntries = listOf(dayOutside), accountServers = mapOf("A" to "JP", "B" to "KR"))
    }

    @Test fun filterDialogWithView() = rule.shoot("log-day", "FilterDialogView", 360, 800, false, "ko", beforeCapture = {
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-view-day").performClick()
    }) { Screen() }
}
