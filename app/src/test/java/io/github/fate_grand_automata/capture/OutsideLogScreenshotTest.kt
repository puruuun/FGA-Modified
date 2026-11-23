package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.*
import io.github.fate_grand_automata.scripts.capture.*
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

    private fun shot(name: String, entry: OutsideLogEntry) = composeRule.shoot("log-outside", name, 360, 1500, locale = "ko",
        beforeCapture = {
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("outside-source:Login:"))
            composeRule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("outside-source:Login:"))).performClick()
        }) { OutsideLogDetail(entry, {}, loginNames) }

    @Test fun loginBreakdownOneLogin360() = shot("LoginBreakdownOne", loginEntry(loginFirst))

    @Test fun loginBreakdownTwoLogins360() = shot("LoginBreakdownTwo", loginEntry(loginFirst, loginSecond))

    private val loginNames = GameNameMap(servants = mapOf(100100L to "샘플 세이버", 100300L to "샘플 룰러"))
    private val loginFirst = LoginBreakdown(at = 1_793_754_000, friend = 300, recommend = 20,
        normal = LoginTopServant(100100, 120), grand = LoginTopServant(100300, 20))
    private val loginSecond = LoginBreakdown(at = 1_793_761_200, friend = 50, follow = 10, normal = LoginTopServant(100100, 40))
    private fun loginEntry(vararg logins: LoginBreakdown): OutsideLogEntry {
        var period = OutsidePeriod("fictionalA", 1_793_750_000)
        logins.forEach { period = period.add(ChangeSource(ChangeSource.Group.Login), AccountDelta(friendPoint = it.total), it.at).withLogin(it) }
        return OutsideLogEntry("login-shot", "fictionalA", "샘플 계정", 1_793_750_000_000, logins.last().at * 1000, period)
    }
}