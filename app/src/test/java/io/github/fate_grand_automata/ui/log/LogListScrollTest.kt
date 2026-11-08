package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.OutsideLogEntry
import io.github.fate_grand_automata.capture.outsideFixture
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class LogListScrollTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)
    private val base = 1_793_754_000_000L
    private fun outside(n: Int, minutes: Long) = outsideFixture("o$n", base + minutes * 60_000)
    private fun battle(n: Int, minutes: Long): BattleLogEntry = battleLogFixture("b$n").copy(endedAt = base + minutes * 60_000)
    private val olderOutside = (1..12).map { outside(it, it.toLong()) }

    private class Sources {
        val battles = mutableStateOf<List<BattleLogEntry>>(emptyList())
        val saved = mutableStateOf<List<OutsideLogEntry>>(emptyList())
        val open = mutableStateOf<List<OutsideLogEntry>>(emptyList())
    }

    private fun show(s: Sources) = rule.setContent {
        FGATheme(darkTheme = false) {
            LogScreenContent(s.battles.value, {}, {}, outsideEntries = s.saved.value, openOutsideEntries = s.open.value)
        }
    }

    private fun top(tag: String) = rule.onNodeWithTag("log-card:$tag")

    @Test fun opensAtNewestWhenOutsideThenBattleArrive() {
        val s = Sources(); s.saved.value = olderOutside; show(s)
        rule.waitForIdle()
        s.battles.value = listOf(battle(1, 100)); rule.waitForIdle()
        top("b1").assertIsDisplayed()
    }

    @Test fun opensAtNewestWhenOpenEntryArrivesLast() {
        val s = Sources(); s.battles.value = listOf(battle(1, 100)); s.saved.value = olderOutside; show(s)
        rule.waitForIdle()
        s.open.value = listOf(outside(99, 200)); rule.waitForIdle()
        top("outside:o99").assertIsDisplayed()
    }

    @Test fun opensAtNewestWhenBattleEntryArrivesLast() {
        val s = Sources(); s.saved.value = olderOutside; s.open.value = emptyList(); show(s)
        rule.waitForIdle()
        s.saved.value = olderOutside + outside(50, 150); rule.waitForIdle()
        top("outside:o50").assertIsDisplayed()
        s.battles.value = listOf(battle(2, 300)); rule.waitForIdle()
        top("b2").assertIsDisplayed()
    }

    @Test fun newNewestEntryWhileAtTopStaysVisible() {
        val s = Sources(); s.saved.value = olderOutside; s.battles.value = listOf(battle(1, 100)); show(s)
        rule.waitForIdle()
        top("b1").assertIsDisplayed()
        s.battles.value = listOf(battle(2, 200), battle(1, 100)); rule.waitForIdle()
        top("b2").assertIsDisplayed()
    }

    @Test fun noJumpWhileScrolledDown() {
        val s = Sources(); s.saved.value = olderOutside; s.battles.value = listOf(battle(1, 100)); show(s)
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("log-card:outside:o1"))
        rule.waitForIdle()
        top("outside:o1").assertIsDisplayed()
        s.battles.value = listOf(battle(2, 200), battle(1, 100)); rule.waitForIdle()
        top("outside:o1").assertIsDisplayed()
        top("b2").assertDoesNotExist()
    }
}
