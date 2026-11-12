package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CaptureAutoStartTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrefsCore

    @Before
    fun setup() {
        val store = context.getSharedPreferences("auto-start-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context)
        CaptureTracker.delete()
        CaptureTracker.setRunning(false)
        CaptureTracker.starting = false
        CaptureAutoStart.onStartStepHook = null
    }

    @After
    fun cleanup() {
        CaptureTracker.delete()
        CaptureTracker.setRunning(false)
        CaptureTracker.starting = false
        CaptureAutoStart.onStartStepHook = null
    }

    @Test
    fun stepFailureDoesNotThrowAndLeavesStartingFalse() {
        CaptureTracker.starting = true
        CaptureAutoStart.onStartStepHook = {
            throw OutOfMemoryError("forced synthetic OOM in start step")
        }

        // Must not throw
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = true)

        assertFalse(CaptureTracker.starting)
    }

    @Test
    fun runtimeExceptionInStepDoesNotThrowAndLeavesStartingFalse() {
        CaptureTracker.starting = true
        CaptureAutoStart.onStartStepHook = {
            throw IllegalStateException("forced synthetic error in start step")
        }

        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = true)

        assertFalse(CaptureTracker.starting)
    }
}
