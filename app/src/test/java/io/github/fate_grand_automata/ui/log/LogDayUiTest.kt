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

    @androidx.compose.runtime.Composable private fun Screen() = FGATheme(false) {
        LogScreenContent(listOf(dayBattleToday, dayBattleOlder), {}, {}, outsideEntries = listOf(dayOutside), accountServers = mapOf("A" to "JP"))
    }

    @Test fun switchSelectDayOpenBattleAndGoBackOnANarrowScreen() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-card:today").assertExists()
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-card:today").assertDoesNotExist()
        rule.onNodeWithContentDescription("삭제").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11").assertExists()
        rule.onNodeWithTag("log-day:2026-11-10").assertExists()
        rule.onNodeWithTag("log-day:2026-11-11").performClick()
        rule.onNodeWithTag("log-day-account:A").assertExists()
        rule.onNodeWithText("JP").assertExists()
        rule.onNodeWithTag("log-day-expand:A").performClick()
        rule.onNodeWithTag("log-card:today").performClick()
        rule.onNodeWithTag("log-day-detail").assertDoesNotExist()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day-detail").assertExists()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day:2026-11-11").assertExists()
        rule.onNodeWithTag("log-view-record").performClick()
        rule.onNodeWithTag("log-card:today").assertExists()
    }

    @Test fun theIncludedBattleCountAndTheEmptySectionLinesShow() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day:2026-11-11").performClick()
        rule.onNodeWithText("전투 1회 포함").assertExists()
        rule.onNodeWithContentDescription(back).performClick()
        rule.onNodeWithTag("log-day:2026-11-10").performClick()
        rule.onNodeWithTag("log-day-no-scripts").assertDoesNotExist()
        rule.onNodeWithTag("log-card:older").assertExists()
    }

    @Test fun theViewAndTheSelectedDaySurviveRecreation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Screen() }
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day:2026-11-10").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("log-day-detail").assertExists()
        rule.onNodeWithTag("log-card:older").assertExists()
    }

    @Test @Config(qualifiers = "ko-w1000dp-h700dp-hdpi") fun wideScreenShowsTheFirstDayAtOnceAndSwitchesPanes() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day:2026-11-11").assertIsSelected()
        rule.onNodeWithTag("log-day-account:A").assertExists()
        rule.onNodeWithTag("log-day:2026-11-10").performClick()
        rule.onNodeWithTag("log-day:2026-11-10").assertIsSelected()
        rule.onNodeWithTag("log-card:older").assertExists()
        rule.onNodeWithTag("log-card:older").performClick()
        rule.onNodeWithTag("log-day-detail").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-10").assertExists()
    }

    @Test fun theDayViewGroupsOnlyWhatPassesTheActiveFilter() {
        val later = dayOutside.copy(id = "later", startedAt = kst("2026-11-12T09:00:00"), endedAt = kst("2026-11-12T09:30:00"),
            period = OutsidePeriod("A", kst("2026-11-12T09:00:00") / 1000).add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 5), kst("2026-11-12T09:10:00") / 1000))
        rule.setContent { FGATheme(false) { LogScreenContent(listOf(dayBattleToday), {}, {}, outsideEntries = listOf(later)) } }
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day:2026-11-12").assertExists()
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-day:2026-11-12").assertDoesNotExist()
        rule.onNodeWithTag("log-day:2026-11-11").assertExists()
    }

    @Test fun anEmptyDayViewShowsTheNormalEmptyText() {
        rule.setContent { FGATheme(false) { LogScreenContent(emptyList(), {}, {}) } }
        rule.onNodeWithTag("log-view-day").performClick()
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

    @Test fun narrowDay() = rule.shoot("log-day", "NarrowDayDetail", 360, 800, false, "ko", beforeCapture = {
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day:2026-11-11").performClick()
        rule.onNodeWithTag("log-day-expand:A").performClick()
    }) { Screen() }

    @Test fun wideDay() = rule.shoot("log-day", "WideDayView", 1280, 720, false, "ko", beforeCapture = {
        rule.onNodeWithTag("log-view-day").performClick()
        rule.onNodeWithTag("log-day-expand:A").performClick()
    }) { Screen() }
}
