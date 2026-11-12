package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.RootShellOutput
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccessMode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaBackupReceiver
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaPatch
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncSession
import io.github.fate_grand_automata.scripts.capture.chaldea.ProbeKind
import io.github.fate_grand_automata.scripts.capture.chaldea.RootState
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaRootManagerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: PrefsCore

    private class FakeSession : ChaldeaSyncSession {
        override suspend fun state() = ChaldeaStateResult(ChaldeaRunState.NotRunning, null)
        override suspend fun forceStop() = ChaldeaStateResult(ChaldeaRunState.NotRunning, null)
        override suspend fun prepare(sink: OutputStream) =
            ChaldeaSyncSession.PrepareOutcome(ChaldeaSyncResult(ChaldeaSyncCode.NotFound), null, false)
        override suspend fun commit(
            patch: ChaldeaPatch,
            expectedSha256: String,
            allowCreate: Boolean,
            expectedPackage: String,
            expectedBundleSha256: String,
        ) = ChaldeaSyncResult(ChaldeaSyncCode.Ok)
        override suspend fun restore(bundle: File, sha256: String, packageName: String) =
            ChaldeaSyncResult(ChaldeaSyncCode.Ok)
        override fun close() {}
    }

    @Before
    fun setup() {
        ChaldeaSyncManager.resetForTest()
        val store = context.getSharedPreferences("chaldea-root-manager-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context)
    }

    @After
    fun cleanup() = ChaldeaSyncManager.resetForTest()

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun initWithSyncOn(rootGrantedBefore: Boolean = false) {
        prefs.chaldeaSyncRootGranted.set(rootGrantedBefore)
        prefs.chaldeaSyncEnabled.set(true)
        ChaldeaSyncManager.init(context, prefs)
        assertTrue(waitFor { ChaldeaSyncManager.isOn }, "apply(true) never ran")
    }

    // ---- F1: cancelled syncNow and throwing probeRunner ----

    @Test
    fun cancellingSyncNowDuringSlowProbeFinishesTicketAndAllowsNextProbe() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        ChaldeaSyncManager.probeRunner = {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                RootState.Unavailable
            } else {
                RootState.Granted
            }
        }
        initWithSyncOn()

        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = callerScope.launch { ChaldeaSyncManager.syncNow(null) }
        assertTrue(started.await(5, TimeUnit.SECONDS), "slow probe never started")
        assertTrue(ChaldeaSyncManager.isProbeBusy)

        job.cancelAndJoin()
        release.countDown()

        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy }, "cancelled syncNow left policy busy")
        assertEquals(RootState.Unavailable, ChaldeaSyncManager.access.value.root)

        // A new probe can start right after.
        ChaldeaSyncManager.syncNow(null)
        assertEquals(2, calls.get())
        assertFalse(ChaldeaSyncManager.isProbeBusy)
        assertEquals(RootState.Granted, ChaldeaSyncManager.access.value.root)
    }

    @Test
    fun throwingProbeRunnerInSyncNowLeavesPolicyNotBusy() = runBlocking {
        val calls = AtomicInteger(0)
        ChaldeaSyncManager.probeRunner = {
            if (calls.incrementAndGet() == 1) error("synthetic probe error")
            RootState.Granted
        }
        initWithSyncOn()

        ChaldeaSyncManager.syncNow(null)
        assertFalse(ChaldeaSyncManager.isProbeBusy)
        assertEquals(RootState.Unavailable, ChaldeaSyncManager.access.value.root)

        ChaldeaSyncManager.syncNow(null)
        assertEquals(2, calls.get())
        assertFalse(ChaldeaSyncManager.isProbeBusy)
        assertEquals(RootState.Granted, ChaldeaSyncManager.access.value.root)
    }

    @Test
    fun throwingProbeRunnerInStartProbeFinishesTicketAsUnavailableAndClearsBusy() = runBlocking {
        val ran = CountDownLatch(1)
        val calls = AtomicInteger(0)
        ChaldeaSyncManager.probeRunner = {
            if (calls.incrementAndGet() == 1) {
                ran.countDown()
                error("synthetic startProbe error")
            }
            RootState.Granted
        }
        initWithSyncOn(rootGrantedBefore = false)
        prefs.chaldeaSyncRootGranted.set(true)

        ChaldeaSyncManager.onUserTurnedOn()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy && ChaldeaSyncManager.access.value.root == RootState.Unavailable })
        assertFalse(prefs.chaldeaSyncRootGranted.get(), "interactive failure clears stored flag")

        ChaldeaSyncManager.syncNow(null)
        assertEquals(2, calls.get())
        assertFalse(ChaldeaSyncManager.isProbeBusy)
        assertEquals(RootState.Granted, ChaldeaSyncManager.access.value.root)
    }

    @Test
    fun throwingProbeRunnerInSilentStartProbeLeavesPolicyNotBusy() = runBlocking {
        val ran = CountDownLatch(1)
        val calls = AtomicInteger(0)
        ChaldeaSyncManager.probeRunner = {
            if (calls.incrementAndGet() == 1) {
                ran.countDown()
                error("synthetic silent probe error")
            }
            RootState.Granted
        }

        initWithSyncOn(rootGrantedBefore = true)
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy && ChaldeaSyncManager.access.value.root == RootState.Unavailable })
        assertTrue(prefs.chaldeaSyncRootGranted.get(), "silent failure keeps stored flag")
    }

    // ---- F3: ProbeKind and timeouts ----

    @Test
    fun managerPassesInteractiveForSwitchOnAndSyncNowAndSilentForStartAndReprobe() = runBlocking {
        val kinds = CopyOnWriteArrayList<ProbeKind>()
        val probeResults = ArrayDeque(
            listOf(
                RootState.Unavailable, // 1. start (Silent)
                RootState.Unavailable, // 2. switch-on (Interactive)
                RootState.Granted,     // 3. sync-now (Interactive)
                RootState.Granted,     // 4. re-probe after bind failure (Silent)
            ),
        )
        ChaldeaSyncManager.probeRunner = { kind ->
            kinds += kind
            probeResults.removeFirst()
        }
        ChaldeaSyncManager.rootOpener = { null }

        // 1. Process start with switch already on and stored root flag true -> Silent
        initWithSyncOn(rootGrantedBefore = true)
        assertTrue(waitFor { kinds.size == 1 && !ChaldeaSyncManager.isProbeBusy })

        // 2. Switch-on path -> Interactive
        ChaldeaSyncManager.onUserTurnedOn()
        assertTrue(waitFor { kinds.size == 2 && !ChaldeaSyncManager.isProbeBusy })

        // 3. Sync-now path -> Interactive (grants root)
        ChaldeaSyncManager.syncNow(null)
        assertEquals(RootState.Granted, ChaldeaSyncManager.access.value.root)

        // 4. Root bind failure then next scheduler round -> Silent re-probe
        assertNull(ChaldeaSyncManager.env().openSession())
        assertEquals(RootState.Unchecked, ChaldeaSyncManager.access.value.root)
        ChaldeaSyncManager.env().access()
        assertTrue(waitFor { kinds.size == 4 && !ChaldeaSyncManager.isProbeBusy })

        assertEquals(
            listOf(ProbeKind.Silent, ProbeKind.Interactive, ProbeKind.Interactive, ProbeKind.Silent),
            kinds.toList(),
        )
    }

    @Test
    fun defaultProbeRunnerUsesThirtySecondsForInteractiveAndFiveForSilent() = runBlocking {
        val timeouts = CopyOnWriteArrayList<Long>()
        ChaldeaSyncManager.probeShell = { cmd, _, timeoutSeconds ->
            assertEquals(listOf("su", "-c", "id"), cmd)
            timeouts += timeoutSeconds
            RootShellOutput(1, emptyList())
        }

        // Silent at start
        initWithSyncOn(rootGrantedBefore = true)
        assertTrue(waitFor { timeouts.size == 1 && !ChaldeaSyncManager.isProbeBusy })

        // Interactive via syncNow
        ChaldeaSyncManager.syncNow(null)
        assertEquals(listOf(5L, 30L), timeouts.toList())
    }

    // ---- F7 & F2: mode choice in env().openSession() and root bind failure/success handling ----

    @Test
    fun openSessionChoosesRootOverShizukuWhenRootIsGranted() = runBlocking {
        val rootSession = FakeSession()
        val shizukuSession = FakeSession()
        var rootCalls = 0
        var shizukuCalls = 0
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.Ready }
        ChaldeaSyncManager.probeRunner = { RootState.Granted }
        ChaldeaSyncManager.rootOpener = { rootCalls++; rootSession }
        ChaldeaSyncManager.shizukuOpener = { shizukuCalls++; shizukuSession }

        initWithSyncOn()
        ChaldeaSyncManager.syncNow(null)
        assertEquals(ChaldeaAccessMode.Root, ChaldeaSyncManager.access.value.mode)

        val opened = ChaldeaSyncManager.env().openSession()
        assertSame(rootSession, opened)
        assertEquals(1, rootCalls)
        assertEquals(0, shizukuCalls)
    }

    @Test
    fun openSessionChoosesShizukuWhenRootIsNotGranted() = runBlocking {
        val rootSession = FakeSession()
        val shizukuSession = FakeSession()
        var rootCalls = 0
        var shizukuCalls = 0
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.Ready }
        ChaldeaSyncManager.probeRunner = { RootState.Unavailable }
        ChaldeaSyncManager.rootOpener = { rootCalls++; rootSession }
        ChaldeaSyncManager.shizukuOpener = { shizukuCalls++; shizukuSession }

        initWithSyncOn()
        ChaldeaSyncManager.syncNow(null)
        assertEquals(ChaldeaAccessMode.Shizuku, ChaldeaSyncManager.access.value.mode)

        val opened = ChaldeaSyncManager.env().openSession()
        assertSame(shizukuSession, opened)
        assertEquals(0, rootCalls)
        assertEquals(1, shizukuCalls)
    }

    @Test
    fun openSessionReturnsNullWhenNeitherRootNorShizukuIsReady() = runBlocking {
        var rootCalls = 0
        var shizukuCalls = 0
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.NotRunning }
        ChaldeaSyncManager.probeRunner = { RootState.Unavailable }
        ChaldeaSyncManager.rootOpener = { rootCalls++; FakeSession() }
        ChaldeaSyncManager.shizukuOpener = { shizukuCalls++; FakeSession() }

        initWithSyncOn()
        ChaldeaSyncManager.syncNow(null)
        assertNull(ChaldeaSyncManager.access.value.mode)

        assertNull(ChaldeaSyncManager.env().openSession())
        assertEquals(0, rootCalls)
        assertEquals(0, shizukuCalls)
    }

    @Test
    fun rootBindFailureCallsOnRootBindFailedAndPublishesAccessAndSuccessResetsCap() = runBlocking {
        val rootSession = FakeSession()
        var failBind = true
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.Ready }
        ChaldeaSyncManager.probeRunner = { RootState.Granted }
        ChaldeaSyncManager.rootOpener = { if (failBind) null else rootSession }

        initWithSyncOn()
        ChaldeaSyncManager.syncNow(null)
        assertEquals(RootState.Granted, ChaldeaSyncManager.access.value.root)
        assertEquals(ChaldeaAccessMode.Root, ChaldeaSyncManager.access.value.mode)

        // 1st bind failure -> RootState.Unchecked published, falls back to Shizuku mode immediately.
        assertNull(ChaldeaSyncManager.env().openSession())
        assertEquals(RootState.Unchecked, ChaldeaSyncManager.access.value.root)
        assertEquals(ChaldeaAccessMode.Shizuku, ChaldeaSyncManager.access.value.mode)

        // Next round triggers a silent re-probe, restoring RootState.Granted.
        ChaldeaSyncManager.env().access()
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy && ChaldeaSyncManager.access.value.root == RootState.Granted })

        // A successful root session resets the consecutive failure counter.
        failBind = false
        assertSame(rootSession, ChaldeaSyncManager.env().openSession())

        // So the next bind failure is failure #1 again (Unchecked, not capped at Unavailable).
        failBind = true
        assertNull(ChaldeaSyncManager.env().openSession())
        assertEquals(RootState.Unchecked, ChaldeaSyncManager.access.value.root)

        // Re-probe grants again, then a 2nd consecutive failure caps root at Unavailable.
        ChaldeaSyncManager.env().access()
        assertTrue(waitFor { !ChaldeaSyncManager.isProbeBusy && ChaldeaSyncManager.access.value.root == RootState.Granted })
        assertNull(ChaldeaSyncManager.env().openSession())
        assertEquals(RootState.Unavailable, ChaldeaSyncManager.access.value.root)
        assertEquals(ChaldeaAccessMode.Shizuku, ChaldeaSyncManager.access.value.mode)
    }
}
