package io.github.fate_grand_automata.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetRefreshTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val originalService = WidgetRefresh.updateService
    private val originalAp = WidgetRefresh.updateAp
    private val originalLauncher = ApWidgetRefresh.launcher

    @After fun restore() {
        WidgetRefresh.updateService = originalService
        WidgetRefresh.updateAp = originalAp
        ApWidgetRefresh.launcher = originalLauncher
    }

    private fun place(id: Int, receiver: Class<*>) {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, receiver)
        })
    }

    private fun broadcast(action: String) {
        place(1, ServiceWidgetReceiver::class.java)
        place(2, ApWidgetReceiver::class.java)
        val updated = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(2)
        WidgetRefresh.updateService = { updated += "service"; done.countDown() }
        WidgetRefresh.updateAp = { updated += "ap"; done.countDown() }
        val receiver = WidgetRestoreReceiver()
        receiver.onReceive(context, Intent(action))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(setOf("service", "ap"), updated.toSet())
    }

    @Test fun bootRestoresBothPlacedWidgets() = broadcast(Intent.ACTION_BOOT_COMPLETED)
    @Test fun packageReplacementRestoresBothPlacedWidgets() = broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

    @Test fun everyUpdateOfThePlacedWidgetsBumpsTheSettingsRevision() = runBlocking {
        val before = WidgetRefresh.revision.value
        WidgetRefresh.updatePlaced(context)
        // Other tests in the same JVM may refresh too, so only require that it moved forward.
        assertTrue(WidgetRefresh.revision.value > before)
    }

    @Test fun noPlacedWidgetsDoesNoRendering() = runBlocking {
        var calls = 0
        WidgetRefresh.updateService = { calls++ }
        WidgetRefresh.updateAp = { calls++ }
        WidgetRefresh.updatePlaced(context)
        assertEquals(0, calls)
    }

    @Test fun onlyThePlacedKindIsRendered() = runBlocking {
        place(2, ApWidgetReceiver::class.java)
        var service = 0
        var ap = 0
        WidgetRefresh.updateService = { service++ }
        WidgetRefresh.updateAp = { ap++ }
        WidgetRefresh.updatePlaced(context)
        assertEquals(0, service)
        assertEquals(1, ap)
    }

    @Test fun failureInOneWidgetStillRefreshesTheOther() = runBlocking {
        place(1, ServiceWidgetReceiver::class.java)
        place(2, ApWidgetReceiver::class.java)
        var refreshed = false
        WidgetRefresh.updateService = { error("render failure") }
        WidgetRefresh.updateAp = { refreshed = true }
        WidgetRefresh.updatePlaced(context)
        assertTrue(refreshed)
    }

    @Test fun unrelatedBroadcastDoesNotGoAsync() {
        val receiver = WidgetRestoreReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertFalse(shadowOf(receiver).wentAsync())
    }

    @Test fun everyCaptureRunningTransitionRefreshesBothPlacedWidgets() {
        place(1, ServiceWidgetReceiver::class.java)
        place(2, ApWidgetReceiver::class.java)
        val done = CountDownLatch(4)
        val service = java.util.concurrent.atomic.AtomicInteger()
        val ap = java.util.concurrent.atomic.AtomicInteger()
        WidgetRefresh.updateService = { service.incrementAndGet(); done.countDown() }
        WidgetRefresh.updateAp = { ap.incrementAndGet(); done.countDown() }
        io.github.fate_grand_automata.capture.CaptureTracker.initSummaries(context)
        io.github.fate_grand_automata.capture.CaptureTracker.setRunning(true)
        io.github.fate_grand_automata.capture.CaptureTracker.setRunning(false)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(2, service.get())
        assertEquals(2, ap.get())
    }

    @Test fun saveHookRefreshesAnyAccountWhenAnApWidgetIsPlaced() {
        place(2, ApWidgetReceiver::class.java)
        val done = CountDownLatch(3)
        ApWidgetRefresh.launcher = { done.countDown() }
        ApWidgetRefresh.onAccountSaved(context, "7001")
        ApWidgetRefresh.onAccountSaved(context, "7002")
        ApWidgetRefresh.onAccountSaved(context, null)
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
}
