package io.github.fate_grand_automata.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.battlelog.FileBattleLogStorage
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.OutsideLogRepository
import io.github.fate_grand_automata.capture.outsideFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #304: when the account info + recent logs widget is redrawn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetLogsRefreshTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val originalUpdater = ApWidgetRefresh.logsUpdater
    private val originalDebounce = ApWidgetRefresh.logsDebounceMs
    private val calls = AtomicInteger()

    @Before fun fast() {
        ApWidgetRefresh.logsDebounceMs = 40
        ApWidgetRefresh.logsUpdater = { calls.incrementAndGet() }
    }

    @After fun restore() {
        ApWidgetRefresh.logsUpdater = originalUpdater
        ApWidgetRefresh.logsDebounceMs = originalDebounce
        OutsideLogRepository.changeListener = null
    }

    private fun placeLogsWidget() {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(61, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ApWidgetLogsReceiver::class.java)
        })
    }

    private fun waitFor(count: Int) {
        val end = System.currentTimeMillis() + 3_000
        while (calls.get() < count && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun aBurstOfChangesIsOneRedraw() {
        placeLogsWidget()
        repeat(5) { ApWidgetRefresh.onLogsChanged(context) }
        waitFor(1)
        Thread.sleep(200)
        assertEquals(1, calls.get())
        // Once the burst is done a later change redraws again.
        ApWidgetRefresh.onLogsChanged(context)
        waitFor(2)
        assertEquals(2, calls.get())
    }

    @Test fun withoutALogsWidgetNothingIsRedrawn() {
        ApWidgetRefresh.onLogsChanged(context)
        Thread.sleep(250)
        assertEquals(0, calls.get())
    }

    @Test fun aFailingRedrawNeverEscapes() {
        placeLogsWidget()
        ApWidgetRefresh.logsUpdater = { calls.incrementAndGet(); throw IllegalStateException("boom") }
        ApWidgetRefresh.onLogsChanged(context)
        waitFor(1)
        assertEquals(1, calls.get())
        // The pending flag was released: the next change still works.
        ApWidgetRefresh.onLogsChanged(context)
        waitFor(2)
        assertEquals(2, calls.get())
    }

    @Test fun storingOrDeletingABattleLogTriggersARedraw() = runBlocking {
        val dir = Files.createTempDirectory("battle-log-widget").toFile()
        val changed = AtomicInteger()
        val repository = BattleLogRepository(FileBattleLogStorage(dir), { 100 }, { changed.incrementAndGet() })
        repository.add(battleLogFixture("a"))
        assertEquals(1, changed.get())
        repository.delete("a")
        assertEquals(2, changed.get())
        dir.deleteRecursively()
        Unit
    }

    @Test fun aFailingWidgetNotificationNeverBreaksTheBattleLog() = runBlocking {
        val dir = Files.createTempDirectory("battle-log-widget-fail").toFile()
        val repository = BattleLogRepository(FileBattleLogStorage(dir), { 100 }, { throw IllegalStateException("widget") })
        repository.add(battleLogFixture("a"))
        // The entry is stored even though the widget hook threw.
        assertTrue(FileBattleLogStorage(dir).readIndex().orEmpty().any { it.id == "a" })
        dir.deleteRecursively()
        Unit
    }

    @Test fun writingAndDeletingAnOutsideLogTriggersARedraw() {
        val dir = Files.createTempDirectory("outside-widget").toFile()
        OutsideLogRepository.init(dir)
        val changed = CountDownLatch(2)
        OutsideLogRepository.changeListener = { changed.countDown() }
        OutsideLogRepository.add(outsideFixture("w1"))
        OutsideLogRepository.delete("w1")
        assertTrue(changed.await(2, TimeUnit.SECONDS))
        assertFalse(OutsideLogRepository.loadedEntries().orEmpty().any { it.id == "w1" })
        OutsideLogRepository.clear()
        dir.deleteRecursively()
    }

    @Test fun anOpenPeriodChangeTriggersARedrawButTheSameValueDoesNot() {
        placeLogsWidget()
        val open = MutableStateFlow<List<*>>(emptyList<Any>())
        ApWidgetRefresh.watchOpenPeriods(context, open)
        Thread.sleep(100)
        open.value = listOf(outsideFixture("live"))
        waitFor(1)
        assertEquals(1, calls.get())
        Thread.sleep(100)
        // An equal value is not an emission of a StateFlow, so no redraw.
        open.value = listOf(outsideFixture("live"))
        Thread.sleep(250)
        assertEquals(1, calls.get())
        open.value = listOf(outsideFixture("live", end = 1_793_754_999_000))
        waitFor(2)
        assertEquals(2, calls.get())
    }
}
