package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.main.MainBuildStatusRow
import io.github.fate_grand_automata.ui.main.MainScreenDestinations
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class CaptureMainUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("main-ui", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    @Test fun installedGameShowsButtonOnFirstCompositionBeforeResume() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = CaptureVpnService.GAME_PACKAGE })
        val prefs = prefs().apply { captureTrackerEnabled.set(true) }
        val owner = StartedOwner()
        var first: CaptureMainState? = null
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FGATheme {
                    val capture = rememberCaptureMain(prefs) {}
                    if (first == null) first = capture.state
                    CaptureServiceButtons(capture.state, {}) {}
                }
            }
        }
        compose.onNodeWithText("Start log collection").assertIsDisplayed()
        assertTrue(first!!.showButton)
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
    }

    @Test fun missingGameHidesButtonOnFirstCompositionAndInstallRefreshesOnResume() {
        val prefs = prefs().apply { captureTrackerEnabled.set(true) }
        val owner = StartedOwner()
        var first: CaptureMainState? = null
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FGATheme {
                    val capture = rememberCaptureMain(prefs) {}
                    if (first == null) first = capture.state
                    CaptureServiceButtons(capture.state, {}) {}
                }
            }
        }
        compose.onNodeWithText("Start log collection").assertDoesNotExist()
        assertFalse(first!!.showButton)
        compose.runOnIdle {
            shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = CaptureVpnService.GAME_PACKAGE })
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Start log collection").assertIsDisplayed()
    }

    @Test fun stopWithServiceDefaultsOffAndRetainsEitherStoredValue() {
        val prefs = prefs()
        assertFalse(prefs.captureStopWithService.get())
        prefs.captureStopWithService.set(true)
        val store = context.getSharedPreferences("main-ui", Context.MODE_PRIVATE)
        assertTrue(PrefsCore(PrefMaker(store), context).captureStopWithService.get())
        prefs.captureStopWithService.set(false)
        assertFalse(PrefsCore(PrefMaker(store), context).captureStopWithService.get())
    }

    @Test fun oneAccountShowsOneFixedWidthCardAndOpensSettings() = checkProfiles(1)
    @Test fun threeAccountsStayInOneRowAndScrollToLastAccount() = checkProfiles(3)

    @Test fun enabledStatusInvokesTheExistingDisableAction() = checkStatus(true, false)
    @Test fun disabledStatusInvokesTheExistingSettingsAction() = checkStatus(false, false)
    @Test fun runningOverlayStillPreventsDisablingAccessibility() = checkStatus(true, true)

    private fun checkStatus(started: Boolean, overlay: Boolean) {
        var clicks = 0
        compose.setContent { FGATheme { MainBuildStatusRow(started, overlay, {}, { clicks++ }) } }
        val chip = compose.onNodeWithTag("accessibilityStatus")
        if (started && overlay) {
            chip.assertIsNotEnabled()
            assertEquals(0, clicks)
        } else {
            chip.assertIsEnabled().performClick()
            assertEquals(1, clicks)
        }
    }

    private fun checkProfiles(count: Int) {
        var destination: MainScreenDestinations? = null
        compose.setContent { FGATheme {
            MainScreenContent(
                navigate = { destination = it }, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {},
                capture = CaptureMainState(profiles = mainProfiles(count), now = 1_762_000_000),
            )
        } }
        val first = compose.onNodeWithText("마스터 1").fetchSemanticsNode().boundsInRoot
        assertEquals(264 * 1.5f, first.width, 1f)
        compose.onNodeWithText("마스터 1").performClick()
        assertEquals(MainScreenDestinations.AccountDashboard("sample-1"), destination)
        compose.onNodeWithText("Lv.", substring = true).assertDoesNotExist()
        if (count == 3) {
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).performScrollToIndex(2)
            compose.onNodeWithText("마스터 3").assertIsDisplayed()
            val last = compose.onNodeWithText("마스터 3").fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, last.top, 1f)
        } else compose.onAllNodesWithText("KR").assertCountEquals(1)
    }

    private class StartedOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }
}

internal fun mainProfiles(count: Int) = (1..count).map { index ->
    CaptureAccount("sample-$index", "마스터 $index", sampleCaptureSummary, sampleCaptureSummary.lastUpdateTime)
}
