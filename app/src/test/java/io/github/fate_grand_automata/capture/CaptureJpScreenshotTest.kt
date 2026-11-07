package io.github.fate_grand_automata.capture

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.main.MainScreenContent
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
class CaptureJpScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val accounts = listOf(captureAccountFixture("7001", "합성 한국 계정"),
        captureAccountFixture("7002", "합성 일본 계정").copy(server = "JP"))

    private fun main(width: Int, scroll: Boolean = false) = compose.shoot(
        folder = "issue-243", name = if (scroll) "MainKrJpScrolled" else "MainKrJp",
        widthDp = width, heightDp = if (width == 1280) 720 else 800, locale = "ko",
        beforeCapture = {
            if (scroll) compose.onNode(SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).performScrollToIndex(1)
            compose.onNodeWithText(if (scroll) "JP" else "KR").assertIsDisplayed()
            if (width == 1280) compose.onNodeWithText("JP").assertIsDisplayed()
        },
    ) {
        Surface(Modifier.fillMaxSize()) {
            MainScreenContent(navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {},
                capture = CaptureMainState(true, true, accounts, 1_762_003_600))
        }
    }
    @Test fun phone() = main(360)
    @Test fun phoneScrolledToJp() = main(360, true)
    @Test fun tablet() = main(1280)
}
