package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.LoginBreakdown
import io.github.fate_grand_automata.scripts.capture.LoginTopServant
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h900dp-hdpi")
class OutsideLoginBreakdownUiTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    private val loginSource = ChangeSource(ChangeSource.Group.Login)
    private val names = GameNameMap(servants = mapOf(100100L to "Fictional Saber", 100300L to "Fictional Ruler"))

    private val first = LoginBreakdown(
        at = 1_793_754_000, friend = 300, follow = 0, recommend = 20,
        normal = LoginTopServant(100100, 120), event = null, grand = LoginTopServant(100300, 20),
    )
    private val second = LoginBreakdown(at = 1_793_757_600, friend = 50, normal = LoginTopServant(999999, 50))

    private fun entry(vararg logins: LoginBreakdown): OutsideLogEntry {
        var period = OutsidePeriod("fictionalA", 1_793_750_000)
        logins.forEach { period = period.add(loginSource, AccountDelta(friendPoint = it.total), it.at).withLogin(it) }
        return OutsideLogEntry("login-entry", "fictionalA", "Sample", 1_793_750_000_000, logins.last().at * 1000, period)
    }

    private fun expand() {
        val source = hasTestTag("outside-source:Login:")
        composeRule.onNode(hasScrollAction()).performScrollToNode(source)
        composeRule.onNode(hasClickAction() and hasAnyAncestor(source)).performClick()
    }

    private fun show(entry: OutsideLogEntry) {
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(entry, {}, names) } }
        expand()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("login-breakdowns"))
    }

    @Test fun expandedLoginShowsTheThreeAmountsAndTheThreeTopRows() {
        show(entry(first))
        composeRule.onNodeWithText("Received").assertIsDisplayed()
        composeRule.onNode(hasTestTag("login-0-friend")).assertIsDisplayed().assertTextEquals("Friend", "+300")
        composeRule.onNode(hasTestTag("login-0-follow")).assertTextEquals("Follow", "0")
        composeRule.onNode(hasTestTag("login-0-recommend")).assertTextEquals("Recommended support", "+20")
        composeRule.onNodeWithText("Top earning servants").assertIsDisplayed()
        composeRule.onNode(hasTestTag("login-0-top-normal")).assertTextContains("Fictional Saber")
        composeRule.onNode(hasTestTag("login-0-top-normal")).assertTextContains("+120")
        composeRule.onNode(hasTestTag("login-0-top-event")).assertTextContains("None")
        composeRule.onNode(hasTestTag("login-0-top-grand")).assertTextContains("Fictional Ruler")
        composeRule.onNode(hasTestTag("login-0-top-grand")).assertTextContains("+20")
        // One login needs no time heading.
        composeRule.onNodeWithTag("login-time:0").assertDoesNotExist()
    }

    @Test fun anUnresolvedServantFallsBackToItsId() {
        show(entry(second))
        composeRule.onNode(hasTestTag("login-0-top-normal")).assertTextContains("#999999")
    }

    @Test fun twoLoginsShowTwoBlocksWithTheirTimesOldestFirst() {
        show(entry(first, second))
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.ENGLISH)
        composeRule.onNodeWithTag("login-block:0").assertExists()
        composeRule.onNodeWithTag("login-block:1").assertExists()
        composeRule.onNodeWithTag("login-time:0").assertTextEquals(time.format(Date(first.at * 1000)))
        composeRule.onNodeWithTag("login-time:1").assertTextEquals(time.format(Date(second.at * 1000)))
        composeRule.onNode(hasTestTag("login-1-friend")).assertTextEquals("Friend", "+50")
    }

    @Test fun anOldRecordWithoutLoginsShowsOnlyTheTile() {
        val period = OutsidePeriod("fictionalA", 10).add(loginSource, AccountDelta(friendPoint = 370), 11)
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(OutsideLogEntry("old", "fictionalA", "Sample", 10_000, 11_000, period), {}, names) } }
        expand()
        composeRule.onNodeWithTag("login-breakdowns").assertDoesNotExist()
        composeRule.onNodeWithText("Received").assertDoesNotExist()
    }

    @Test fun otherSourcesNeverShowABreakdown() {
        val period = entry(first).period.add(ChangeSource.of("present/receive"), AccountDelta(friendPoint = 5), 1_793_754_100)
        composeRule.setContent { FGATheme(false) { OutsideLogDetail(entry(first).copy(period = period), {}, names) } }
        val gift = hasTestTag("outside-source:GiftBox:")
        composeRule.onNode(hasScrollAction()).performScrollToNode(gift)
        composeRule.onNode(hasClickAction() and hasAnyAncestor(gift)).performClick()
        composeRule.onNodeWithTag("login-breakdowns").assertDoesNotExist()
    }
}