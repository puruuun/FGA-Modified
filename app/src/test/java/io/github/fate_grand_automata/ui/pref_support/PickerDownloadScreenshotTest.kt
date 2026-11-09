package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
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
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class PickerDownloadScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private fun capture(dark: Boolean, importing: Boolean) {
        val f = PickerDownloadFixture()
        compose.setContent { FGATheme(darkTheme = dark) { Wide { f.Picker() } } }
        compose.onNodeWithText(f.title).performClick()
        compose.onNodeWithText("이미지 받기").assertIsDisplayed()
        if (importing) compose.onNodeWithText("이미지 받기").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).onLast().captureRoboImage(
            "build/outputs/roborazzi/picker-download/${if (importing) "import" else "servant"}_${if (dark) "dark" else "light"}.png")
    }
    @Test fun servantLight() = capture(false, false)
    @Test fun servantDark() = capture(true, false)
    @Test fun importLight() = capture(false, true)
    @Test fun importDark() = capture(true, true)
}