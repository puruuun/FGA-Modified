package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.capture.RootShell
import io.github.fate_grand_automata.capture.RootShellOutput
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStatus
import io.github.fate_grand_automata.scripts.capture.chaldea.RootState
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hardening follow-ups of #357 (permission wish, half-set-up switch) and #364 (probe cancelled on switch-off). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaSyncHardeningTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: PrefsCore

    @Before
    fun setup() {
        ChaldeaSyncManager.resetForTest()
        ChaldeaSyncManager.settingsDispatcher = Dispatchers.Unconfined
        ChaldeaSyncManager.probeRunner = { RootState.Unavailable }
        ChaldeaSyncManager.gameNamesFactory = { GameNames(File(it.filesDir, "test_names"), download = { error("offline") }) }
        val store = context.getSharedPreferences("chaldea-hardening-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context)
    }

    @After
    fun cleanup() = ChaldeaSyncManager.resetForTest()

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.yield()
        }
        return false
    }

    // ---- #357.2 ----

    @Test
    fun applyOffClearsThePermissionWishEvenWhenTheSwitchWasNeverOn() {
        ChaldeaSyncManager.init(context, prefs)
        ChaldeaSyncManager.onUserTurnedOn()
        assertFalse(ChaldeaSyncManager.isOn)

        ChaldeaSyncManager.apply(false)

        assertFalse(ChaldeaSyncManager.permissionRequester.shouldRequest(ShizukuState.NeedsPermission))
    }

    @Test
    fun turningOffBeforeTheSyncWasAppliedClearsThePermissionWish() {
        ChaldeaSyncManager.init(context, prefs)
        ChaldeaSyncManager.onUserTurnedOn()

        ChaldeaSyncManager.onUserTurnedOff()

        assertFalse(ChaldeaSyncManager.permissionRequester.shouldRequest(ShizukuState.NeedsPermission))
    }

    // ---- #357.3 ----

    @Test
    fun aFailureWhileSwitchingOnLeavesTheSyncOffWithAStatusAndALaterToggleWorks() {
        ChaldeaSyncManager.accountSourceFactory = { error("synthetic setup failure") }
        prefs.chaldeaSyncEnabled.set(true)
        ChaldeaSyncManager.init(context, prefs)
        assertTrue(waitFor { ChaldeaSyncManager.applyCount >= 1 }, "apply(true) never ran")

        assertFalse(ChaldeaSyncManager.isOn)
        assertNull(ChaldeaSyncManager.hooks)
        assertEquals(ChaldeaSyncStatus.Failed(ChaldeaSyncCode.ServiceError), ChaldeaSyncManager.status.value)

        ChaldeaSyncManager.accountSourceFactory = null
        prefs.chaldeaSyncEnabled.set(false)
        assertTrue(waitFor { ChaldeaSyncManager.applyCount >= 2 }, "apply(false) never ran")
        prefs.chaldeaSyncEnabled.set(true)
        assertTrue(waitFor { ChaldeaSyncManager.isOn }, "the later toggle did not switch the sync on")
        assertNotNull(ChaldeaSyncManager.hooks)
    }

    // ---- #364.1 ----

    @Test
    fun switchingOffWhileTheInteractiveProbeWaitsDestroysTheWaitingProcess() {
        val started = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        // Like ProcessRootShell: blocks interruptibly until its timeout and destroys the process on every way out.
        ChaldeaSyncManager.probeShell = RootShell { _, _, _ ->
            started.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30))
                RootShellOutput(-1, emptyList(), true)
            } finally {
                destroyed.countDown()
            }
        }
        // The default runner is the one that uses probeShell.
        ChaldeaSyncManager.probeRunner = { kind ->
            val out = ChaldeaSyncManager.probeShell.run(listOf("su", "-c", "id"), "", ChaldeaSyncManager.probeTimeoutSeconds(kind))
            if (out.timedOut) RootState.Unavailable else RootState.Granted
        }
        prefs.chaldeaSyncEnabled.set(true)
        ChaldeaSyncManager.init(context, prefs)
        assertTrue(waitFor { ChaldeaSyncManager.isOn })
        ChaldeaSyncManager.onUserTurnedOn()
        assertTrue(started.await(5, TimeUnit.SECONDS), "the probe never started")

        prefs.chaldeaSyncEnabled.set(false)

        assertTrue(destroyed.await(5, TimeUnit.SECONDS), "the waiting probe process was left running")
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy })
    }

    @Test
    fun turningOffBeforeTheSyncWasAppliedCancelsTheWaitingInteractiveProbe() {
        val started = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        ChaldeaSyncManager.probeShell = RootShell { _, _, _ ->
            started.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30))
                RootShellOutput(-1, emptyList(), true)
            } finally {
                destroyed.countDown()
            }
        }
        ChaldeaSyncManager.probeRunner = { kind ->
            val out = ChaldeaSyncManager.probeShell.run(listOf("su", "-c", "id"), "", ChaldeaSyncManager.probeTimeoutSeconds(kind))
            if (out.timedOut) RootState.Unavailable else RootState.Granted
        }
        ChaldeaSyncManager.init(context, prefs)
        ChaldeaSyncManager.onUserTurnedOn()
        assertTrue(started.await(5, TimeUnit.SECONDS), "the probe never started")
        assertFalse(ChaldeaSyncManager.isOn)

        ChaldeaSyncManager.onUserTurnedOff()

        assertTrue(destroyed.await(5, TimeUnit.SECONDS), "the waiting probe process was left running")
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy })
    }
}
