package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.*
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

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LogSourceScreenshotTest(private val width: Int, private val locale: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x800_{1}")
        fun sizes() = listOf(360, 1280).flatMap { w -> listOf("ko", "ja").map { arrayOf<Any>(w, it) } }
    }
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val outside = outsideFixture().copy(period = OutsidePeriod("fictionalA", 1)
        .add(ChangeSource(ChangeSource.Group.Shop), AccountDelta(qp = 100), 2)
        .add(ChangeSource(ChangeSource.Group.Summon, script = true), AccountDelta(friendPoint = -200), 3)
        .add(ChangeSource(ChangeSource.Group.Summon), AccountDelta(friendPoint = -100), 4))

    @Test fun sourceDialog() {
        RuntimeEnvironment.setQualifiers(locale + "-w" + width + "dp-h800dp-notnight-mdpi")
        rule.setContent { FGATheme(false) {
            LogFilterDialog(LogTimeFilter.All, null, null, null, 200, 0, 1,
                mixedCaptureLogs(emptyList(), listOf(outside)), GameNameMap(), null, {}, { _, _, _, _, _, _, _ -> },
                source = LogSourceFilter.Script)
        } }
        rule.onNodeWithTag("log-filter-source:Script").performScrollTo().assertIsSelected()
        LogSourceFilter.entries.forEach { rule.onNodeWithTag("log-filter-source:" + it).assertExists() }
        if (locale == "ko") {
            rule.onNodeWithText("복권").assertExists()
            rule.onNodeWithText("직접 퀘스트").assertExists()
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("log-filter-dialog").captureRoboImage(ScreenshotDir + "/log-source-filter/Dialog_" + width + "x800dp_" + locale + ".png")
    }

    @Test fun scriptSourceDetail() = rule.shoot("log-source-filter", "Outside", width, 800, locale = locale, density = "mdpi", beforeCapture = {
        val scripted = hasTestTag("outside-source:Summon::script")
        rule.onNode(hasScrollAction()).performScrollToNode(scripted)
        rule.onNode(hasClickAction() and hasAnyAncestor(scripted)).performClick()
        rule.onNodeWithText(if (locale == "ko") "스크립트" else "スクリプト").assertIsDisplayed()
        val top = rule.onNode(scripted).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top < rule.onNodeWithTag("outside-source:Shop:").fetchSemanticsNode().boundsInRoot.top)
        rule.onNode(hasContentDescription(if (locale == "ko") "프렌드 포인트, −200" else "フレンドポイント, −200") and hasAnyAncestor(scripted)).assertExists()
    }) {
        Column(Modifier.fillMaxSize()) {
            OutsideLogCard(outside, false, {})
            LazyColumn(Modifier.weight(1f).testTag("source-detail")) {
                outsideLogDetailItems(outside, GameNameMap(), LogSourceFilter.Script)
            }
        }
    }
}
