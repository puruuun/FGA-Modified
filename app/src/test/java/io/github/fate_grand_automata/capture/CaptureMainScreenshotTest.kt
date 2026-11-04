package io.github.fate_grand_automata.capture

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
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
class CaptureMainScreenshotTest(private val width: Int, private val running: Boolean, private val dark: Boolean) {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun mainWithProfileAndButton() = compose.shoot(
        folder = "log-ui", name = if (running) "MainCollecting" else "MainOff",
        widthDp = width, heightDp = 800, locale = "ko", dark = dark,
        beforeCapture = {
            compose.onNodeWithText("마스터  Lv.120").assertIsDisplayed()
            val capture = compose.onNodeWithText(if (running) "로그 수집 중지" else "로그 수집 시작").fetchSemanticsNode().boundsInRoot
            val service = compose.onNodeWithText("서비스 시작", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            // The tracker is either to the left or above; never obscures the service.
            assertTrue(capture.right <= service.left || capture.bottom <= service.top)
            if (width == 280) assertTrue(capture.bottom <= service.top)
        },
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScreenContent(
                navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {},
                capture = CaptureMainState(true, running, sampleCaptureSummary, 1_762_000_000),
            )
        }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp running={1} dark={2}")
        fun cases() = listOf(280, 360, 840).flatMap { width ->
            listOf(false, true).flatMap { running -> listOf(false, true).map { dark -> arrayOf<Any>(width, running, dark) } }
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
