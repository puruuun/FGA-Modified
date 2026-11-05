package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponseBuilder
import io.github.fate_grand_automata.ui.launcher.battleLauncher
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w840dp-h800dp-hdpi")
class CaptureLauncherUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun stop() { CaptureTracker.setRunning(false) }

    @Test fun runningHidesOnlyGainOptionEvenWhenRuntimeServerIsEn() = checkRule(true, GameServer.En.Original)
    @Test fun stoppedShowsGainOptionEvenWhenRuntimeServerIsKr() = checkRule(false, GameServer.Kr)

    private fun checkRule(running: Boolean, server: GameServer) {
        CaptureTracker.setRunning(running)
        val fixture = CaptureLauncherFixture(context, server)
        val redraw = mutableStateOf(0)
        lateinit var response: ScriptLauncherResponseBuilder
        compose.setContent { FGATheme {
            Surface(Modifier.fillMaxSize()) {
                Text("${redraw.value}")
                response = battleLauncher(fixture.prefs, prefsCore = fixture.core)
            }
        } }
        val stopText = context.getString(R.string.p_bond_stop)
        compose.onNodeWithText(stopText).performScrollTo().assertIsDisplayed()
        val track = compose.onNodeWithText(context.getString(R.string.p_bond_track))
        if (running) track.assertDoesNotExist() else track.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.p_bond_ignore_characters)).assertIsDisplayed()
        // Running is snapshotted when this window opens, even after a later recomposition.
        compose.runOnIdle { CaptureTracker.setRunning(!running); redraw.value++ }
        if (running) track.assertDoesNotExist() else track.assertIsDisplayed()
        compose.runOnIdle { response.build() }
        assertTrue(fixture.bond.track.get())
        assertTrue(fixture.bond.stopOnMax.get())
        assertEquals(setOf(2), fixture.bond.ignoredCharacters.get())
    }
}
