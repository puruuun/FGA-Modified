package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.more.GainsTrackerContent
import io.github.fate_grand_automata.ui.more.GainsTrackerUiState
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class JpRootSettingsScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun screenshot(width: Int) {
        val settings = JpRootSettings(FakeJpCaInstaller(), false) {}
        runBlocking { settings.setEnabled(true) }
        compose.shoot("capture-jp", "Installed", width, 1200, locale = "ko") {
            GainsTrackerContent(GainsTrackerUiState(enabled = true, certExists = true, certInstalled = true),
                {}, {}, {}, {}, {}, {}, {}, jpState = settings.state.value, jpInstalled = true)
        }
    }
    @Test fun phone() = screenshot(360)
    @Test fun tablet() = screenshot(1280)
}
