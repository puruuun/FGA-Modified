package io.github.fate_grand_automata.ui.log

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.capture.outsideFixture
import io.github.fate_grand_automata.scripts.capture.RunGains
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
class LogCollectionIconTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    @Test fun mixedListDistinguishesCollectedSensedOutsideAndInProgressCards() {
        rule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(
                listOf(
                    battleLogFixture("collected").copy(gains = gainsFixture()),
                    battleLogFixture("empty-gains").copy(gains = RunGains()),
                    battleLogFixture("sensed"),
                ), {}, {},
                outsideEntries = listOf(outsideFixture("closed")),
                openOutsideEntries = listOf(outsideFixture("live")),
            )
        } }
        val key = hasContentDescription("Recorded with log collection")
        val arrows = hasTestTag("log-outside-icon")
        for (id in listOf("collected", "empty-gains")) {
            iconInCard(id, key).assertIsDisplayed()
            iconInCard(id, arrows).assertDoesNotExist()
        }
        iconInCard("sensed", key or arrows).assertDoesNotExist()
        for (id in listOf("outside:closed", "outside:live")) {
            iconInCard(id, arrows).assertIsDisplayed()
            iconInCard(id, key).assertDoesNotExist()
        }
        rule.onAllNodes(key, useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodes(arrows, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    @Config(qualifiers = "ko-w360dp-h800dp-hdpi")
    fun collectedIconHasKoreanDescription() = assertDescription("로그 수집으로 기록됨")

    @Test
    @Config(qualifiers = "ja-w360dp-h800dp-hdpi")
    fun collectedIconHasJapaneseDescription() = assertDescription("ログ収集で記録済み")

    private fun assertDescription(description: String) {
        rule.setContent { FGATheme(darkTheme = false) {
            LogScreenContent(listOf(battleLogFixture().copy(gains = gainsFixture())), {}, {})
        } }
        rule.onNodeWithContentDescription(description, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun iconInCard(id: String, icon: SemanticsMatcher) = rule.onNode(
        icon and hasAnyAncestor(hasTestTag("log-card:$id")), useUnmergedTree = true,
    )
}
