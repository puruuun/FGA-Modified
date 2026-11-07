package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.capture.outsideFixture
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
class LogCollectionIconScreenshotTest(private val width: Int, private val height: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}")
        fun sizes() = listOf(arrayOf(360, 800), arrayOf(1280, 720))
    }
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun mixedList() {
        val names = localIconNames(context)
        val loader = localIconLoader(context)
        val collected = battleLogFixture("collected").copy(
            configName = "이벤트 주회", accountName = "샘플", reasonText = "8회 주회 완료", gains = gainsFixture(),
        )
        val sensed = collected.copy(id = "sensed", configName = "화면 인식 주회", endedAt = collected.endedAt - 60_000, gains = null)
        val outside = outsideFixture("closed").copy(accountName = "샘플")
        val live = outside.copy(id = "live")
        rule.shoot("log-round9", "MixedList", width, height, dark = false, locale = "ko", beforeCapture = {
            rule.onAllNodesWithContentDescription("로그 수집으로 기록됨", useUnmergedTree = true).assertCountEquals(1)
            rule.onAllNodesWithTag("log-outside-icon", useUnmergedTree = true).assertCountEquals(2)
            if (width >= 840) {
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("log-icon-loaded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("log-icon-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                }
            }
        }) {
            CompositionLocalProvider(LocalLogIconLoader provides loader) {
                LogScreenContent(listOf(collected, sensed), {}, {}, names,
                    outsideEntries = listOf(outside), openOutsideEntries = listOf(live))
            }
        }
    }
}
