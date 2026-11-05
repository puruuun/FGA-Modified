package io.github.fate_grand_automata.ui

import androidx.compose.runtime.CompositionLocalProvider
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.battlelog.gainsNamesFixture
import io.github.fate_grand_automata.ui.log.BattleLogDetail
import io.github.fate_grand_automata.ui.log.LogScreenContent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GainsLogScreenshotTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)

    @Test fun detail360() = composeRule.shoot("gains-log", "detail", 360, 1500, locale = "ko") {
        BattleLogDetail(battleLogFixture().copy(configName = "이벤트 주회", reasonText = "8회 주회 완료", gains = gainsFixture()),
            {}, gainsNamesFixture)
    }

    @Test fun missingBattles360() = composeRule.shoot("gains-log", "missing-battles", 360, 1500, locale = "ko") {
        BattleLogDetail(battleLogFixture().copy(configName = "이벤트 주회", reasonText = "8회 주회 완료", gains = gainsFixture(true)),
            {}, gainsNamesFixture)
    }

    @Test fun trackedCard360() = composeRule.shoot("gains-log", "tracked-card", 360, 800, locale = "ko") {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            LogScreenContent(listOf(
                battleLogFixture("tracked").copy(configName = "이벤트 주회", reasonText = "8회 주회 완료", gains = gainsFixture()),
                battleLogFixture("old").copy(configName = "이전 기록", reasonText = "8회 주회 완료", endedAt = 1_793_746_800_000),
            ), {}, {}, gainsNamesFixture)
        }
    }
}
