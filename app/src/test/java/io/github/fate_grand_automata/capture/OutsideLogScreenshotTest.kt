package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OutsideLogScreenshotTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    private val battle = battleLogFixture("battle").copy(configName = "이벤트 주회", reasonText = "8회 주회 완료", accountName = "샘플 계정")

    @Test fun mixedList360() = composeRule.shoot("log-outside", "MixedList", 360, 800, locale = "ko") {
        LogScreenContent(listOf(battle), {}, {}, outsideNames, outsideEntries = listOf(outsideFixture()))
    }

    @Test fun detailTotalsAndExpandedSource360() = composeRule.shoot("log-outside", "DetailExpanded", 360, 1800, locale = "ko",
        beforeCapture = {
            composeRule.onNodeWithText("선물함").performScrollTo().performClick()
        }) { OutsideLogDetail(outsideFixture(), {}, outsideNames) }

    @Test fun twoPane840() = composeRule.shoot("log-outside", "TwoPane", 840, 1000, locale = "ko") {
        LogScreenContent(listOf(battle), {}, {}, outsideNames, outsideEntries = listOf(outsideFixture()))
    }
}
