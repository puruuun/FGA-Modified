package io.github.fate_grand_automata.ui.launcher

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class LauncherQuestItemsRowTest(private val widthDp: Int, private val heightDp: Int, private val server: GameServer) {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun equalHalvesStayOnOneLineAndSaveIndependentSelections() {
        RuntimeEnvironment.setQualifiers("ko-w${widthDp}dp-h${heightDp}dp-hdpi")
        val fixture = CaptureLauncherFixture(context, server)
        val perServer = fixture.prefs.getPerServerConfigPref(server).apply {
            useStormPod = false
            useTeapot = false
        }
        val visible = mutableStateOf(true)
        val storm = context.getString(R.string.p_use_storm_pod)
        val teapot = context.getString(R.string.p_use_teapot)
        compose.shoot(
            folder = "launcher-quest-items",
            name = if (server == GameServer.Kr) "kr" else "jp",
            widthDp = widthDp,
            heightDp = heightDp,
            locale = "ko",
            beforeCapture = { compose.onNodeWithText(storm).performScrollTo() }
        ) {
            Surface(Modifier.fillMaxSize()) {
                if (visible.value) battleLauncher(fixture.prefs, prefsCore = fixture.core)
            }
        }

        val stormRow = compose.onNodeWithText(storm).assertIsDisplayed()
        val teapotRow = compose.onNodeWithText(teapot).assertIsDisplayed()
        val left = stormRow.fetchSemanticsNode().boundsInRoot
        val right = teapotRow.fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 0.1f)
        assertEquals(left.bottom, right.bottom, 0.1f)
        assertEquals(left.width, right.width, 1f)
        assertEquals(left.right, right.left, 0.1f)
        for (text in listOf(storm, teapot)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("$text must fit on one line", 1, layouts.single().lineCount)
            assertFalse("$text must not be ellipsized", layouts.single().isLineEllipsized(0))
            assertTrue(layouts.single().size.height <= left.height)
        }
        val bondTrack = context.getString(R.string.p_bond_track)
        assertEquals(
            left.width + right.width,
            compose.onNodeWithText(bondTrack).fetchSemanticsNode().boundsInRoot.width,
            1f
        )

        // Tapping either label toggles only its own checkbox; tapping the checkbox also works.
        compose.onNodeWithText(storm, useUnmergedTree = true).performTouchInput { click() }
        stormRow.onChildren().filter(isToggleable()).onFirst().assertIsOn()
        teapotRow.onChildren().filter(isToggleable()).onFirst().assertIsOff()
        teapotRow.onChildren().filter(isToggleable()).onFirst().performClick().assertIsOn()
        teapotRow.performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(2f, 2f)) }
        teapotRow.onChildren().filter(isToggleable()).onFirst().assertIsOff()
        teapotRow.performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(2f, 2f)) }
        teapotRow.onChildren().filter(isToggleable()).onFirst().assertIsOn()
        compose.onNodeWithText(storm, useUnmergedTree = true).performTouchInput { click() }
        stormRow.onChildren().filter(isToggleable()).onFirst().assertIsOff()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(perServer.useStormPod)
        assertTrue(perServer.useTeapot)
    }

    @Test
    fun disablingTheFeatureKeepsSelectionsAndHidesTheBlock() {
        RuntimeEnvironment.setQualifiers("ko-w${widthDp}dp-h${heightDp}dp-hdpi")
        val fixture = CaptureLauncherFixture(context, server)
        fixture.core.questItemControl.ignore.set(true)
        val perServer = fixture.prefs.getPerServerConfigPref(server).apply {
            useStormPod = true
            useTeapot = false
        }
        compose.setContent { battleLauncher(fixture.prefs, prefsCore = fixture.core) }
        compose.onNodeWithText(context.getString(R.string.p_quest_item_usage)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.p_use_storm_pod)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.p_use_teapot)).assertDoesNotExist()
        assertTrue(perServer.useStormPod)
        assertFalse(perServer.useTeapot)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}dp_{2}")
        fun params(): List<Array<Any>> = listOf(640 to 360, 780 to 360, 1280 to 800).flatMap { (width, height) ->
            listOf(GameServer.Kr, GameServer.Jp.Original).map { arrayOf(width, height, it) }
        }
    }
}
