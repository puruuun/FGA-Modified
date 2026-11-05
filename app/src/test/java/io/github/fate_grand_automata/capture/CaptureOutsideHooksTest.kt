package io.github.fate_grand_automata.capture

import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

/** Exercises real tracker/service hooks; no VPN, devices, networking or game operations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CaptureOutsideHooksTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun feed(path: String, body: String) = CaptureTracker.onGameResponse(path, body.toByteArray(), null)
    private fun fruit(num: Long) = feed("item/recover", outsideFruit(num = num).toString())

    @Before fun setup() {
        CaptureTracker.init(context)
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
        OutsideLogRepository.clear()
        feed("login/top", outsideLogin().toString())
    }
    @After fun clean() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
        OutsideLogRepository.clear()
    }

    @Test fun liveFlowAppearsUpdatesAndClosesWithSameIdThroughTracker() {
        CaptureTracker.setRunning(true)
        fruit(10)
        assertTrue(CaptureTracker.openOutsideEntries.value.isEmpty())
        fruit(9)
        val first = CaptureTracker.openOutsideEntries.value.single()
        assertEquals(-1L, first.period.total.items[100])
        fruit(7)
        val updated = CaptureTracker.openOutsideEntries.value.single()
        assertEquals(first.id, updated.id)
        assertEquals(-3L, updated.period.total.items[100])
        assertTrue(updated.endedAt >= first.endedAt)
        CaptureTracker.setRunning(false)
        assertTrue(CaptureTracker.openOutsideEntries.value.isEmpty())
        assertEquals(first.id, OutsideLogRepository.entries.value.single().id)
    }

    @Test fun deletingLiveRecordDropsPeriodAndNextChangeStartsFresh() {
        CaptureTracker.setRunning(true)
        fruit(9)
        val id = CaptureTracker.openOutsideEntries.value.single().id
        CaptureTracker.deleteOutsideEntry(id)
        assertTrue(CaptureTracker.openOutsideEntries.value.isEmpty())
        fruit(8)
        val next = CaptureTracker.openOutsideEntries.value.single()
        assertNotEquals(id, next.id)
        assertEquals(-1L, next.period.total.items[100])
        CaptureTracker.deleteOutsideEntry(id) // A stale dialog cannot delete the new period.
        assertEquals(next.id, CaptureTracker.openOutsideEntries.value.single().id)
        CaptureTracker.clearOutsideEntries()
        CaptureTracker.setRunning(false)
        assertTrue(OutsideLogRepository.entries.value.isEmpty())
    }

    @Test fun allCollectionStopReasonsCloseThroughActualResponseHook() {
        CaptureStopReason.entries.forEachIndexed { i, reason ->
            CaptureTracker.setRunning(true)
            fruit(9L - i)
            CaptureTracker.setRunning(false, reason)
            assertEquals(i + 1, OutsideLogRepository.entries.value.size)
            assertFalse(CaptureTracker.running.value)
        }
    }

    @Test fun serviceStopHookClosesWhileCollectionRemainsEnabled() {
        val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("outside-hooks", 0)), context)
            .apply { captureStopWithService.set(false) }
        CaptureTracker.setRunning(true)
        fruit(9)
        CaptureServiceLifecycle.onStop(context, prefs)
        assertTrue(CaptureTracker.running.value)
        assertEquals(1, OutsideLogRepository.entries.value.size)
        fruit(8)
        CaptureTracker.setRunning(false)
        assertEquals(2, OutsideLogRepository.entries.value.size)
    }

    @Test fun runAndAccountDeleteHooksKeepOutsideHistorySeparate() {
        CaptureTracker.setRunning(true)
        fruit(9)
        CaptureTracker.startRun()
        assertEquals(1, OutsideLogRepository.entries.value.size)
        fruit(8)
        CaptureTracker.endRunCollection()
        CaptureTracker.finishRun()
        fruit(7)
        CaptureTracker.delete("fictionalA")
        CaptureTracker.setRunning(false)
        assertEquals(1, OutsideLogRepository.entries.value.size)
        feed("login/top", outsideLogin().toString())
        CaptureTracker.setRunning(true)
        fruit(6)
        CaptureTracker.delete()
        CaptureTracker.setRunning(false)
        assertEquals(1, OutsideLogRepository.entries.value.size)
    }
}
