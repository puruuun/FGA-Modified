package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.ScreenshotDir
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LogRefineScreenshotTest(private val width: Int, private val height: Int, private val locale: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_{2}")
        fun sizes() = listOf(360 to 800, 1280 to 720).flatMap { (w, h) ->
            listOf("ko", "ja", "en").map { arrayOf<Any>(w, h, it) }
        }
    }

    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val start = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
    private val outside = outsideFixture().copy(accountName = "샘플 A", period = OutsidePeriod("fictionalA", 1)
        .add(ChangeSource.of("present/receive"), AccountDelta(items = mapOf(6510L to 1L)), 2)
        .add(ChangeSource.of("mission/receive"), AccountDelta(qp = 100), 3))
    private val entries = mixedCaptureLogs(listOf(
        battleLogFixture("b").copy(accountId = "b", accountName = "샘플 B"),
        battleLogFixture("c").copy(accountId = "c", accountName = null),
        battleLogFixture("d"),
    ), listOf(outside))

    private fun showFilters(time: LogTimeFilter = LogTimeFilter.All) {
        RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-notnight-hdpi")
        rule.setContent { FGATheme(false) {
            LogFilterDialog(time, null, start, end, 200, 2, 1, entries, outsideNames, null,
                {}, { _, _, _, _, _, _ -> })
        } }
    }

    private fun capture(tag: String, name: String) {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag(tag).captureRoboImage("$ScreenshotDir/log-screen-refine/${name}_${width}x${height}dp_$locale.png")
    }

    @Test fun dateRangeHeadlineStaysOnOneLineWithoutTruncation() {
        showFilters(LogTimeFilter.Custom)
        rule.onNodeWithTag("log-filter-time:Custom").performClick()
        capture("log-date-range-dialog", "DateRange")
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("log-date-range-headline").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse("Headline ${layouts.single().layoutInput.text}", layouts.single().isLineEllipsized(0))
        val dialogWidth = rule.onNodeWithTag("log-date-range-dialog").fetchSemanticsNode().boundsInRoot.width / rule.density.density
        assertEquals(minOf(600, maxOf(360, width - 32), width).toFloat(), dialogWidth, 2f)
        val pickerWidth = rule.onNodeWithTag("log-date-range-picker").fetchSemanticsNode().boundsInRoot.width / rule.density.density
        assertEquals(dialogWidth, pickerWidth, 2f)
        val weekdays = when (locale) {
            "ko" -> listOf("일", "월", "화", "수", "목", "금", "토")
            "ja" -> listOf("日", "月", "火", "水", "木", "金", "土")
            else -> listOf("S", "M", "T", "W", "T", "F", "S")
        }
        val centers = weekdays.toSet().flatMap { day ->
            rule.onAllNodes(hasText(day) and hasAnyAncestor(hasTestTag("log-date-range-picker")), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot.center.x }
        }.sorted()
        assertEquals(7, centers.size)
        val gaps = centers.zipWithNext { left, right -> right - left }
        assertTrue("Weekday column gaps $gaps", gaps.max() - gaps.min() <= 2f)
    }

    @Test fun profileChipsAndRetentionChoices() {
        showFilters()
        rule.onNodeWithTag("log-filter-profile:all").assertIsSelected()
        rule.onNodeWithTag("log-filter-profile:fictionalA").assertExists()
        rule.onNodeWithTag("log-filter-profile:b").assertExists()
        rule.onNodeWithText("#c").assertExists()
        rule.onNodeWithText("500").assertDoesNotExist()
        capture("log-filter-dialog", "ProfileFilter")
    }

    @Test fun noProfileChipIsShownAfterProfileChips() {
        showFilters()
        rule.onNodeWithTag("log-filter-profile:none").assertExists()
        capture("log-filter-dialog", "NoProfileFilter")
    }

    @Test fun outsideSourceLinePreservesOrderAndInProgressPrefix() = rule.shoot(
        "log-screen-refine", "OutsideSources", width, height, locale = locale, beforeCapture = {
            val sources = when (locale) {
                "ko" -> "선물함 · 미션"
                "ja" -> "プレゼントボックス · ミッション"
                else -> "Gift box · Missions"
            }
            val prefix = when (locale) { "ko" -> "진행 중"; "ja" -> "進行中"; else -> "In progress" }
            rule.onNodeWithText(sources).assertExists()
            rule.onNodeWithText("$prefix · $sources").assertExists()
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("$prefix · $sources", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
        },
    ) {
        Column(Modifier.testTag("outside-cards")) {
            OutsideLogCard(outside, false, {})
            OutsideLogCard(outside.copy(id = "open"), false, {}, inProgress = true)
        }
    }
}
