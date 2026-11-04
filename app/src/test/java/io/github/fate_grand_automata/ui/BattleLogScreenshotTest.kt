package io.github.fate_grand_automata.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.ui.log.BattleLogDetail
import io.github.fate_grand_automata.ui.log.LogScreenContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BattleLogScreenshotTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    @Test
    fun list360() = composeRule.shoot("battle-log", "list", 360, 800, locale = "ko") {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            LogScreenContent(listOf(
                battleLogFixture("one", extras = true).copy(configName = "이벤트 주회", reasonText = "8회 주회 완료"),
                battleLogFixture("two").copy(configName = "요일 퀘스트", reasonText = "사용자가 중지함", endedAt = 1_793_746_800_000)
            ), {}, {})
        }
    }

    @Test
    fun detail360() = composeRule.shoot("battle-log", "detail", 360, 1000, locale = "ko") {
        BattleLogDetail(battleLogFixture(extras = true).copy(configName = "이벤트 주회", reasonText = "8회 주회 완료"), {})
    }

    @Test
    fun twoPane1600() = composeRule.shoot("battle-log", "two-pane", 1600, 1000, locale = "ko") {
        LogScreenContent(listOf(
            battleLogFixture("one", extras = true).copy(configName = "이벤트 주회", reasonText = "8회 주회 완료"),
            battleLogFixture("two").copy(configName = "요일 퀘스트", reasonText = "사용자가 중지함", endedAt = 1_793_746_800_000),
            battleLogFixture("three").copy(configName = "프리퀘스트", reasonText = "8번 반복함", endedAt = 1_793_740_000_000)
        ), {}, {})
    }
}
