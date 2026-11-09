package io.github.fate_grand_automata.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetLaunchRequestsTest {
    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun startExtraIsReadOnceAndRemovedFromTheIntent() {
        val requests = WidgetLaunchRequests()
        val intent = WidgetIntents.startService(context)
        requests.consume(intent)
        assertFalse(intent.hasExtra(WidgetIntents.EXTRA_WIDGET_START))
        assertEquals(true, requests.takeStart())
        assertFalse(requests.takeStart())
        // Delivering the same (stripped) intent again requests nothing.
        requests.consume(intent)
        assertFalse(requests.takeStart())
    }

    @Test fun aRestoredActivityDoesNotReplayTheOriginalIntent() {
        val requests = WidgetLaunchRequests()
        val start = WidgetIntents.startService(context)
        requests.consume(start, restored = true)
        assertFalse(requests.takeStart())
        assertFalse(start.hasExtra(WidgetIntents.EXTRA_WIDGET_START))
        requests.consume(WidgetIntents.openAccount(context, "7001"), restored = true)
        assertNull(requests.takeAccount())
    }

    @Test fun accountExtraIsReadOnce() {
        val requests = WidgetLaunchRequests()
        requests.consume(WidgetIntents.openAccount(context, "7001"))
        assertEquals("7001", requests.takeAccount())
        assertNull(requests.takeAccount())
    }

    @Test fun plainLaunchRequestsNothing() {
        val requests = WidgetLaunchRequests()
        requests.consume(Intent(Intent.ACTION_MAIN))
        requests.consume(null)
        assertFalse(requests.takeStart())
        assertNull(requests.takeAccount())
    }

    @Test fun widgetPendingIntentsAreDistinctForTheSystem() {
        val start = WidgetIntents.startService(context)
        val account = WidgetIntents.openAccount(context, "1")
        val other = WidgetIntents.openAccount(context, "2")
        assertFalse(start.filterEquals(account))
        assertFalse(account.filterEquals(other))
        assertFalse(start.filterEquals(WidgetIntents.openApp(context)))
    }

    @Test fun startEffectTogglesOnceAndNotAgainAfterRecomposition() {
        val requests = WidgetLaunchRequests()
        var toggles = 0
        var tick by mutableIntStateOf(0)
        requests.requestStart()
        compose.setContent {
            tick // forces recompositions
            WidgetStartEffect(requests, serviceStarted = { false }) { toggles++ }
        }
        compose.waitForIdle()
        assertEquals(1, toggles)
        tick++
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test fun aScreenRecreatedWithTheSameRequestsDoesNotToggleAgain() {
        // Rotation keeps the view model, so the recreated screen sees the same, already consumed, requests.
        val requests = WidgetLaunchRequests()
        var toggles = 0
        var generation by mutableIntStateOf(0)
        requests.requestStart()
        compose.setContent {
            androidx.compose.runtime.key(generation) {
                WidgetStartEffect(requests, serviceStarted = { false }) { toggles++ }
            }
        }
        compose.waitForIdle()
        assertEquals(1, toggles)
        generation++
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test fun aRunningServiceIsNeverToggledByAWidgetStart() {
        val requests = WidgetLaunchRequests()
        var toggles = 0
        requests.requestStart()
        compose.setContent { WidgetStartEffect(requests, serviceStarted = { true }) { toggles++ } }
        compose.waitForIdle()
        assertEquals(0, toggles)
        assertFalse(requests.startPending)
    }

    @Test fun logStartReturnsToMainFromAnExistingScreenThenRunsOnce() {
        val requests = WidgetLaunchRequests()
        var main by androidx.compose.runtime.mutableStateOf(false)
        var starts = 0
        requests.requestCaptureStart()
        compose.setContent {
            WidgetCaptureNavigationEffect(requests, onboardingRequired = false) { main = true }
            if (main) WidgetCaptureStartEffect(requests, running = false) { starts++ }
        }
        compose.waitForIdle()
        assertEquals(true, main)
        assertEquals(1, starts)
        assertFalse(requests.captureStartPending)
    }

    @Test fun logStartBeforeOnboardingIsDiscardedAndNeverRunsLater() {
        val requests = WidgetLaunchRequests()
        var onboarding by androidx.compose.runtime.mutableStateOf(true)
        var navigations = 0
        requests.requestCaptureStart()
        compose.setContent { WidgetCaptureNavigationEffect(requests, onboarding) { navigations++ } }
        compose.waitForIdle()
        assertFalse(requests.captureStartPending)
        onboarding = false
        compose.waitForIdle()
        assertEquals(0, navigations)
    }

    @Test fun aRunningCaptureIsNeverStoppedByAnActivityStartExtra() {
        val requests = WidgetLaunchRequests()
        var toggles = 0
        requests.requestCaptureStart()
        compose.setContent { WidgetCaptureStartEffect(requests, running = true) { toggles++ } }
        compose.waitForIdle()
        assertEquals(0, toggles)
        assertFalse(requests.captureStartPending)
    }
}
