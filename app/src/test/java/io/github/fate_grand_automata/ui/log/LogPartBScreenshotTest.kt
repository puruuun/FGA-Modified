package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
class LogPartBScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
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
    private fun awaitIcons(requireLoaded: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("log-icon-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        if (requireLoaded) rule.onAllNodesWithTag("log-icon-loaded", useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    @Test fun listSelected() = rule.shoot("log-part-b", "ListSelected", width, height, dark, "ko") {
        WithIcons {
            if (width >= 840) LogScreenContent(listOf(battle), {}, {}, names, openOutsideEntries = listOf(outside))
            else Column(Modifier.fillMaxSize()) {
                LogList(mixedCaptureLogs(listOf(battle), emptyList(), listOf(outside)), "outside:${outside.id}", {},
                    LogDeleteSelection(), {}, {}, {}, {}, true, "", LogTimeFilter.All, {}, null, {}, emptySet(), names)
            }
        }
    }
    @Test fun deleteMode() = rule.shoot("log-part-b", "DeleteMode", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithText("삭제").performClick()
        rule.onNodeWithTag("log-card:outside:${outside.id}").performClick()
        rule.onNodeWithTag("log-card:battle").performClick()
        awaitIcons()
    }) { WithIcons { LogScreenContent(listOf(battle), {}, {}, names, openOutsideEntries = listOf(outside)) } }

    @Test fun activeFilter() = rule.shoot("log-part-b", "ActiveFilter", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithText("획득 자원: 모든 자원").performClick()
        rule.onNode(hasText("무간의 톱니바퀴") and hasAnyAncestor(isDialog())).performClick()
        awaitIcons()
    }) { WithIcons { LogScreenContent(listOf(battle), {}, {}, names, openOutsideEntries = listOf(outside)) } }

    @Test fun outsideDetailIcons() = rule.shoot("log-part-b", "OutsideDetailIcons", width, height, dark, "ko", beforeCapture = { awaitIcons(true) }) {
        WithIcons { OutsideLogDetail(outside, {}, names) }
    }
    @Test fun sourceDetailIcons() = rule.shoot("log-part-b", "SourceDetailIcons", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithText("선물함").performScrollTo().performClick()
        rule.onNodeWithTag("outside-source:GiftBox:").performScrollTo()
        awaitIcons(true)
    }) { WithIcons { OutsideLogDetail(outside, {}, names) } }

    @Test fun battleDetailIcons() = rule.shoot("log-part-b", "BattleDetailIcons", width, height, dark, "ko", beforeCapture = {
        rule.onNodeWithText("획득량 (로그 수집)").performScrollTo()
        awaitIcons(true)
    }) { WithIcons { BattleLogDetail(battle, {}, names) } }
}
