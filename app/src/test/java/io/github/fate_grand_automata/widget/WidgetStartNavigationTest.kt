package io.github.fate_grand_automata.widget

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.main.MainActivity
import io.github.fate_grand_automata.ui.main.Route
import io.github.fate_grand_automata.ui.main.WidgetNavigationEffects
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetStartNavigationTest(private val initialRoute: Route) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun routes() = listOf(Route.AccountDashboard("7001"), Route.Logs, Route.MoreOptions).map { arrayOf(it) }
    }

    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val requests = WidgetLaunchRequests()
    private val backStack = mutableStateListOf<NavKey>(Route.Home, initialRoute)
    private var running by mutableStateOf(false)
    private var onboarding by mutableStateOf(false)
    private var starts = 0

    private fun show() {
        compose.setContent {
            WidgetNavigationEffects(requests, onboarding, backStack) { running }
            // The production start effect belongs only to the Home destination.
            if (backStack.lastOrNull() == Route.Home) {
                WidgetStartEffect(requests, serviceStarted = { running }) { starts++ }
            }
        }
        compose.waitForIdle()
    }

    private fun assertStartedOnce() {
        assertEquals(listOf(Route.Home), backStack.toList())
        assertEquals(1, starts)
        assertFalse(requests.startPending)
        compose.runOnIdle { running = true }
        compose.waitForIdle()
        assertEquals(1, starts)
    }

    @Test fun coldLaunchReturnsHomeAndStartsOnce() {
        // Same consumption as MainActivity.onCreate, before the navigation graph is composed.
        requests.consume(WidgetIntents.startService(context))
        show()
        assertStartedOnce()
    }

    @Test fun warmLaunchReturnsHomeAndStartsOnce() {
        show()
        assertEquals(initialRoute, backStack.last())
        // singleTop delivers a fresh intent to the existing activity's onNewIntent.
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        compose.runOnIdle { requests.consume(WidgetIntents.startService(context)) }
        compose.waitForIdle()
        assertStartedOnce()
        compose.runOnIdle {
            backStack.add(initialRoute)
            running = false
        }
        compose.waitForIdle()
        assertEquals(initialRoute, backStack.last())
        assertEquals(1, starts)
    }

    @Test fun runningServiceDropsColdAndWarmRequestsWithoutNavigation() {
        running = true
        requests.consume(WidgetIntents.startService(context))
        show()
        assertFalse(requests.startPending)
        compose.runOnIdle { requests.consume(WidgetIntents.startService(context)) }
        compose.waitForIdle()
        assertFalse(requests.startPending)
        assertEquals(initialRoute, backStack.last())
        compose.runOnIdle { running = false }
        compose.waitForIdle()
        assertEquals(0, starts)
        assertEquals(initialRoute, backStack.last())
    }

    @Test fun onboardingDropsColdAndWarmRequestsAndDoesNotReplayAfterCompletion() {
        onboarding = true
        backStack.clear()
        backStack.add(Route.Onboarding)
        requests.consume(WidgetIntents.startService(context))
        show()
        assertFalse(requests.startPending)
        compose.runOnIdle { requests.consume(WidgetIntents.startService(context)) }
        compose.waitForIdle()
        assertFalse(requests.startPending)
        assertEquals(listOf(Route.Onboarding), backStack.toList())
        compose.runOnIdle {
            onboarding = false
            backStack.clear()
            backStack.add(Route.Home)
        }
        compose.waitForIdle()
        assertEquals(0, starts)
    }

    @Test fun restoredIntentDoesNotNavigateOrStartAgain() {
        requests.consume(WidgetIntents.startService(context), restored = true)
        show()
        assertEquals(initialRoute, backStack.last())
        assertEquals(0, starts)
        assertFalse(requests.startPending)
    }
}
