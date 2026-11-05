package io.github.fate_grand_automata.capture

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CaptureMainScreenshotTest(private val width: Int, private val accounts: Int, private val dark: Boolean) {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun mainWithProfileAndButton() = compose.shoot(
        folder = "issue-209-a", name = "Main${accounts}Accounts",
        widthDp = width, heightDp = if (width == 1280) 720 else 800,
        density = if (width == 1280) "xhdpi" else "hdpi", locale = "ko", dark = dark,
        beforeCapture = {
            compose.onNodeWithText("마스터 1").assertIsDisplayed()
            val capture = compose.onNodeWithText("로그 수집 중지").fetchSemanticsNode().boundsInRoot
            val service = compose.onNodeWithText("서비스 시작", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(capture.right <= service.left || capture.bottom <= service.top)
        },
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScreenContent(
                navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {},
                capture = CaptureMainState(true, true, mainProfiles(accounts), 1_762_000_000),
            )
        }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp accounts={1} dark={2}")
        fun cases() = listOf(360, 840, 1280).flatMap { width ->
            listOf(1, 3).flatMap { accounts -> listOf(false, true).map { dark -> arrayOf<Any>(width, accounts, dark) } }
        }
    }
}
internal val sampleCaptureSummary = AccountSummary(
    masterName = "마스터", level = 120, ap = 85, apMax = 142, carryOverActPoint = 0,
    stone = 312, freeStone = 200, chargeStone = 112, fragments = 45, goldenApples = 12,
    silverApples = 30, bronzeApples = 8, copperApples = 150, bronzeSaplings = 3, tickets = 7,
    manaPrisms = 9000, rarePrisms = 120, qp = 123_456_789,
    snapshotTime = 1_762_000_000, lastUpdateTime = 1_762_003_600, actRecoverAt = 1_762_017_100,
)
