package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.OwnerTestScript
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.LauncherPanel
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.lib_automata.EntryPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launcher shown for the detected SupportImageMaker mode, with the bottom row's three buttons at both widths. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SupportFilterLauncherModesScreenshotTest(private val width: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "w{0}")
        fun cases() = listOf(arrayOf<Any>(600), arrayOf<Any>(840))
    }

    private val compose = androidx.compose.ui.test.junit4.v2.createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private class ScanButton : OwnerTestScript {
        override fun offeredFor(mode: ScriptModeEnum) = mode == ScriptModeEnum.SupportImageMaker
        override fun label(context: Context) = "서포트 점수 스캔"
        override fun entryPoint(component: ScriptComponent): EntryPoint = error("not started in this test")
        override fun exitMessage(context: Context, e: Exception): String? = null
    }

    private fun shootImageMaker(name: String, panel: LauncherPanel) {
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.captureTrackerEnabled.set(true)
        compose.shoot("ui728", name, width, 480, locale = "ko") {
            ScriptLauncher(
                ScriptModeEnum.SupportImageMaker, {}, fixture.prefs, fixture.core,
                ownerTests = listOf(ScanButton()), initialPanel = panel
            )
        }
    }

    @Test fun imageMakerWithTheChip() = shootImageMaker("ImageMakerChip", LauncherPanel.None)

    @Test fun imageMakerWithThePanelOpen() = shootImageMaker("ImageMakerPanel", LauncherPanel.SupportFilter)
}
