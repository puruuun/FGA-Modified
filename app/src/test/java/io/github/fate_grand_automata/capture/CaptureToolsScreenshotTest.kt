package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.main.MainBuildStatusRow
import io.github.fate_grand_automata.ui.more.AdditionalToolsContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class CaptureToolsScreenshotTest(private val screen: String, private val dark: Boolean) {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun screen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = if (screen == "RunWindow") CaptureLauncherFixture(context, io.github.fate_grand_automata.scripts.enums.GameServer.Kr) else null
        if (fixture != null) CaptureTracker.setRunning(true)
        val width = if (screen.startsWith("Header280")) 280 else if (screen == "RunWindow") 840 else 360
        val enabled = screen.endsWith("Enabled")
        try {
            compose.shoot(
                folder = "issue-209-a", name = screen, widthDp = width,
                heightDp = if (screen.startsWith("Header")) 120 else 800, locale = "ko", dark = dark,
                beforeCapture = {
                    if (screen.startsWith("Header")) {
                        val badge = compose.onNodeWithTag("buildBadge").fetchSemanticsNode().boundsInRoot
                        val chip = compose.onNodeWithTag("accessibilityStatus").fetchSemanticsNode().boundsInRoot
                        assertTrue(badge.right <= chip.left || badge.bottom <= chip.top)
                        assertEquals((width - 16) * 1.5f, chip.right, 1f)
                        compose.onNodeWithTag("accessibilityStatus").assertIsDisplayed()
                    }
                    if (fixture != null) {
                        compose.onNodeWithText(context.getString(R.string.p_bond_stop)).performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText(context.getString(R.string.p_bond_track)).assertDoesNotExist()
                    }
                },
            ) {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        screen.startsWith("Header") -> MainBuildStatusRow(enabled, false, {}, {})
                        screen == "Tools" -> CompositionLocalProvider(LocalNavigateBack provides {}) {
                            AdditionalToolsContent(mainProfiles(3), {}, {}, {})
                        }
                        else -> ScriptLauncher(ScriptModeEnum.Battle, {}, fixture!!.prefs, fixture.core)
                    }
                }
            }
        } finally { if (fixture != null) CaptureTracker.setRunning(false) }
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun cases() = listOf("Header280Enabled", "Header280Disabled", "Header360Enabled", "Header360Disabled", "Tools", "RunWindow")
            .flatMap { screen -> listOf(false, true).map { dark -> arrayOf<Any>(screen, dark) } }
    }
}
