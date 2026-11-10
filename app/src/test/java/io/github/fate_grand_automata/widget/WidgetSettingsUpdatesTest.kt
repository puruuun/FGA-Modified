package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetSettingsUpdatesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs(name: String) = PrefsCore(PrefMaker(context.getSharedPreferences(name, Context.MODE_PRIVATE)), context)

    @Test fun changingThemeOrOpacityRequestsAWidgetUpdate() = runBlocking {
        val prefs = prefs("widget_updates")
        val updates = AtomicInteger()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            observeWidgetSettingChanges(prefs) { updates.incrementAndGet() }
        }
        // Opening the screen reads the stored values but is not a change.
        assertEquals(0, updates.get())

        prefs.widgetTheme.set(WidgetTheme.Light)
        assertEquals(1, updates.get())

        prefs.widgetBackgroundTransparency.set(40)
        assertEquals(2, updates.get())
        prefs.widgetShowLogButton.set(true)
        assertEquals(3, updates.get())
        prefs.captureTrackerEnabled.set(true)
        assertEquals(4, updates.get())
        job.cancelAndJoin()
    }

    @Test fun changingTheDisplayStyleOrTheNewButtonsRequestsAWidgetUpdate() = runBlocking {
        val prefs = prefs("widget_updates_style")
        val updates = AtomicInteger()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            observeWidgetSettingChanges(prefs) { updates.incrementAndGet() }
        }
        assertEquals(0, updates.get())
        prefs.widgetDisplayStyle.set(io.github.fate_grand_automata.prefs.core.WidgetDisplayStyle.AllAccounts)
        assertEquals(1, updates.get())
        prefs.widgetShowServiceButton.set(false)
        assertEquals(2, updates.get())
        prefs.widgetShowServer.set(true)
        assertEquals(3, updates.get())
        job.cancelAndJoin()
    }

    @Test fun startIsIdempotentAndTheRetainedJobStaysActive() {
        val prefs = prefs("widget_observer")
        val updates = AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        try {
            WidgetSettingsObserver.stop()
            WidgetSettingsObserver.start(prefs, scope) { updates.incrementAndGet() }
            val first = WidgetSettingsObserver.job
            WidgetSettingsObserver.start(prefs, scope) { updates.incrementAndGet() }
            assertEquals(true, first === WidgetSettingsObserver.job)
            assertEquals(true, first!!.isActive)
            System.gc()
            assertEquals(0, updates.get())
            prefs.widgetShowLogButton.set(true)
            assertEquals(1, updates.get())
            assertEquals(true, WidgetSettingsObserver.job!!.isActive)
        } finally {
            WidgetSettingsObserver.stop()
            scope.cancel()
        }
    }

    @Test fun settingsStoredByThePrefsAreWhatTheWidgetsRead() {
        val sp = context.getSharedPreferences("widget_roundtrip", Context.MODE_PRIVATE)
        val prefs = PrefsCore(PrefMaker(sp), context)
        assertEquals(WidgetSettings(WidgetTheme.Dark, 0), readWidgetSettings(sp))
        prefs.widgetTheme.set(WidgetTheme.Light)
        prefs.widgetBackgroundTransparency.set(60)
        assertEquals(WidgetSettings(WidgetTheme.Light, 60), readWidgetSettings(sp))
        prefs.widgetShowLogButton.set(true)
        assertEquals(WidgetSettings(WidgetTheme.Light, 60, true), readWidgetSettings(sp))
    }
}
