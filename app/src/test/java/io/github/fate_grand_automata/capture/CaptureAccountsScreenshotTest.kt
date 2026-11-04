package io.github.fate_grand_automata.capture

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDialog
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.battlelog.gainsNamesFixture
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.log.BattleLogDetail
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.more.GainsTrackerContent
import io.github.fate_grand_automata.ui.more.GainsTrackerUiState
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Review images only, generated from fictional data; no device or game interaction. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CaptureAccountsScreenshotTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val account = captureAccountFixture()
    private val entry = battleLogFixture().copy(
        configName = "이벤트 주회", reasonText = "8회 주회 완료", gains = gainsFixture(),
        accountId = account.id, accountName = account.displayName,
    )

    @Test fun settingsWithTwoAccounts360() = composeRule.shoot("log-accounts", "SettingsTwoAccounts", 360, 1900, locale = "ko") {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            GainsTrackerContent(
                GainsTrackerUiState(enabled = true, certExists = true, certInstalled = true, running = true,
                    accounts = listOf(account, captureAccountFixture("7002", "두 번째 계정")), currentId = account.id),
                {}, {}, {}, {}, {}, {}, {},
            )
        }
    }

    @Test fun renameDialog360() {
        org.robolectric.RuntimeEnvironment.setQualifiers("ko-w360dp-h640dp-hdpi")
        composeRule.setContent { FGATheme(darkTheme = false) { CaptureAccountRenameDialog(account, {}, {}) } }
        composeRule.waitForIdle()
        composeRule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/log-accounts/Rename_360x640dp_ko.png")
    }

    @Test fun namedLogCard360() = composeRule.shoot("log-accounts", "LogCard", 360, 800, locale = "ko") {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            LogScreenContent(listOf(entry), {}, {}, gainsNamesFixture)
        }
    }

    @Test fun namedLogDetail360() = composeRule.shoot("log-accounts", "LogDetail", 360, 1500, locale = "ko") {
        BattleLogDetail(entry, {}, gainsNamesFixture)
    }
}
