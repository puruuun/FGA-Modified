package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LogRound3ScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_dark={2}") fun sizes() =
            listOf(360 to 800, 840 to 800, 1280 to 720).flatMap { (w, h) -> listOf(arrayOf<Any>(w, h, false), arrayOf<Any>(w, h, true)) }
    }
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val names by lazy { localIconNames(context) }
    private val battle = battleLogFixture("battle").copy(configName = "이벤트 주회", reasonText = "8회 주회 완료", accountName = "샘플", gains = gainsFixture())
    private val outside = outsideFixture().copy(accountName = "샘플")
    @Composable private fun WithIcons(content: @Composable () -> Unit) {
        val loader = remember { localIconLoader(context) }
        CompositionLocalProvider(LocalLogIconLoader provides loader, content = content)
    }
    private fun awaitIcons() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("log-icon-loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("log-icon-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    @Composable private fun Logs() = WithIcons {
        LogScreenContent(listOf(battle), {}, {}, names, openOutsideEntries = listOf(outside))
    }
    @Test fun topBar() = rule.shoot("log-round3", "TopBar", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithContentDescription("삭제").assertExists()
        rule.onNodeWithContentDescription("로그 필터").assertIsDisplayed()
        if (width >= 840) awaitIcons()
    }) { Logs() }

    @Test fun filterDialog() = rule.shoot("log-round3", "FilterDialog", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithText("200").assertIsSelected()
        awaitIcons()
    }) { Logs() }

    @Test fun activeFilter() = rule.shoot("log-round3", "ActiveFilter", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithTag("log-filter-button").performClick()
        rule.onNodeWithTag("log-filter-resources").performScrollToNode(hasText("무간의 톱니바퀴"))
        rule.onNode(hasText("무간의 톱니바퀴") and hasAnyAncestor(isDialog())).performClick()
        rule.onNodeWithText("적용").performClick()
        rule.onNodeWithTag("log-filter-summary").assertIsDisplayed()
        if (width >= 840) awaitIcons()
    }) { Logs() }

    @Test fun detailLocalIcons() = rule.shoot("log-round3", "DetailLocalIcons", width, height, dark, "ko", beforeCapture = { awaitIcons() }) {
        WithIcons { OutsideLogDetail(outside, {}, names) }
    }
}
