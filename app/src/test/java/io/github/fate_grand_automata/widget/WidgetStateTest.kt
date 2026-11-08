package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.runner.ScriptRunnerService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The service widget observes this state inside Glance, whose unit tree stays at its first snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetStateTest {
    private val compose = createComposeRule()
    @get:Rule val rules = io.github.fate_grand_automata.ui.screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val model = ApWidgetModel("7001", "계정", 57, 142, 1800000000, 1700000000)

    @Test fun serviceStateRecomposesFromOffToOnAndBack() {
        val source = ScriptRunnerService.serviceStarted as MutableState<Boolean>
        var action: ServiceWidgetAction? = null
        try {
            source.value = false
            compose.setContent {
                val current = serviceWidgetModel(ScriptRunnerService.serviceStarted.value)
                SideEffect { action = current.action }
            }
            compose.waitForIdle()
            assertEquals(ServiceWidgetAction.Start, action)
            compose.runOnIdle { source.value = true }
            compose.waitForIdle()
            assertEquals(ServiceWidgetAction.Stop, action)
            compose.runOnIdle { source.value = false }
            compose.waitForIdle()
            assertEquals(ServiceWidgetAction.Start, action)
        } finally {
            source.value = false
        }
    }
}
