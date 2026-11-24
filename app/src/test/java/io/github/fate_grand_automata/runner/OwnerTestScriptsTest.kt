package io.github.fate_grand_automata.runner

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.di.script.ScriptComponent
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponse
import io.github.lib_automata.EntryPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h480dp")
class OwnerTestScriptsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private class Script(val name: String) : OwnerTestScript {
        override fun offeredFor(mode: ScriptModeEnum) = mode == ScriptModeEnum.SupportImageMaker
        override fun label(context: Context) = name
        override fun entryPoint(component: ScriptComponent): EntryPoint = error("not started in this test")
        override fun exitMessage(context: Context, e: Exception): String? = null
    }

    @Test fun `without the score scan flag the owner test scripts are never built`() {
        var asked = 0
        assertEquals(emptyList(), ownerTestScriptsFor(false) { asked++; error("built without the score scan flag") })
        assertEquals(0, asked)
    }

    @Test fun `with the score scan flag the owner test scripts are offered`() {
        val script = Script("scan")
        assertEquals(listOf<OwnerTestScript>(script), ownerTestScriptsFor(true) { setOf(script) })
    }

    @Test fun `the injected holder of this build has no owner test script and never reads the set`() {
        assertFalse(BuildConfig.SCORE_SCAN)
        val holder = OwnerTestScripts(Provider { error("the owner test set was read") })
        assertEquals(emptyList(), holder.scripts)
    }

    @Test fun `this build compiles none of the rust build type's scan classes`() {
        assertFalse(BuildConfig.SCORE_SCAN)
        for (name in listOf("SupportScoreScanScript", "HolderScoreScanNativeFrames", "SupportScoreScanModule")) {
            val found = runCatching { Class.forName("io.github.fate_grand_automata.rusttest.$name") }.isSuccess
            assertFalse(found, name)
        }
    }

    @Test fun `an owner test response sets no script mode`() {
        val (_, prefs) = launcherPrefs()
        for (mode in listOf(ScriptModeEnum.FP, ScriptModeEnum.SupportImageMaker)) {
            prefs.scriptMode = mode
            ScriptLauncherResponseHandler(prefs).handle(ScriptLauncherResponse.OwnerTest(Script("scan")))
            assertEquals(mode, prefs.scriptMode)
        }
    }

    private fun launcherPrefs(): Pair<PrefsCore, PreferencesImpl> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val main = context.getSharedPreferences("owner_test_launcher_prefs", Context.MODE_PRIVATE)
        main.edit().clear().commit()
        val core = PrefsCore(PrefMaker(main), context)
        return core to PreferencesImpl(core, CompletedRunsHolder()).apply { gameServer = GameServer.Kr }
    }

    @Test fun `the support screen launcher offers nothing new without owner test scripts`() {
        val (core, prefs) = launcherPrefs()
        composeRule.setContent {
            FGATheme { ScriptLauncher(ScriptModeEnum.SupportImageMaker, {}, prefs, core) }
        }
        composeRule.onAllNodesWithText("Support Image Maker").assertCountEquals(1)
        composeRule.onAllNodesWithText("Support score scan").assertCountEquals(0)
    }

    @Test fun `an offered owner test script starts from its own button`() {
        val (core, prefs) = launcherPrefs()
        val script = Script("Support score scan")
        var response: ScriptLauncherResponse? = null
        composeRule.setContent {
            FGATheme { ScriptLauncher(ScriptModeEnum.SupportImageMaker, { response = it }, prefs, core, ownerTests = listOf(script)) }
        }
        composeRule.onNodeWithText("Support score scan").performClick()
        assertTrue(response == ScriptLauncherResponse.OwnerTest(script))
    }
}
