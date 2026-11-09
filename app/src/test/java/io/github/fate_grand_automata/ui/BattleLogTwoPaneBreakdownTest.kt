package io.github.fate_grand_automata.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.ui.log.LogScreenContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two-pane layout reuses the detail composable when another entry is selected (issue #280 review). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w1000dp-h800dp-hdpi")
class BattleLogTwoPaneBreakdownTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun summary(id: String, name: String, endedAt: Long) = battleLogFixture(id)
        .copy(configName = name, endedAt = endedAt, gains = RunGains(storedBattleCount = 1, lastQuestId = 0))

    @Test
    fun selectingAnotherEntryNeverShowsThePreviousEntrysBattles() {
        val a = summary("a", "Alpha farm", 2)
        val b = summary("b", "Beta farm", 1)
        val quests = mapOf("a" to 111L, "b" to 222L)
        composeRule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(listOf(a, b), {}, {}, loadBattles = { id -> listOf(BattleGains(time = 1, questId = quests[id])) })
        } }
        // A is selected by default.
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_breakdown)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Quest 111", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("Beta farm")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Quest 111", substring = true).assertCountEquals(0)
        // Expanding B shows B's battles.
        composeRule.onNodeWithText(context.getString(R.string.capture_gains_breakdown)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Quest 222", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("Quest 111", substring = true).assertCountEquals(0)
    }
}
