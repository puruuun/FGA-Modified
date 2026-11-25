package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.LauncherPanel
import io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag
import io.github.fate_grand_automata.ui.launcher.LauncherSettingsListTag
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The support filter panel beside the limits, so the import buttons and "모두 초기화" are in one image (#719). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SupportFilterPanelScreenshotTest(private val locale: String, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_dark{1}")
        fun cases() = listOf(arrayOf<Any>("ko", false), arrayOf<Any>("ko", true), arrayOf<Any>("ja", false))
    }

    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun panelWithImportButtons() {
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        compose.shoot("ui718", "SupportFilterPanel", 840, 900, dark = dark, locale = locale, beforeCapture = {
            compose.onNodeWithTag(LauncherSettingsListTag).performScrollToNode(hasTestTag(LauncherResetAllTag))
        }) {
            ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core, initialPanel = LauncherPanel.SupportFilter)
        }
    }

    @Test fun fpLauncherWithTheFeatureOn() {
        org.junit.Assume.assumeTrue(locale == "ko" && !dark)
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterPanelOpen.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        compose.shoot("ui718", "FpLauncher", 600, 500, locale = locale) {
            ScriptLauncher(ScriptModeEnum.FP, {}, fixture.prefs, fixture.core)
        }
    }
}
