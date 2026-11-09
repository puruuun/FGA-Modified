package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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

    private fun waitFor(count: AtomicInteger, expected: Int) {
        val end = System.currentTimeMillis() + 3000
        while (count.get() < expected && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun changingThemeOrOpacityRequestsAWidgetUpdate() = runBlocking {
        val prefs = prefs("widget_updates")
        val updates = AtomicInteger()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            observeWidgetSettingChanges(prefs) { updates.incrementAndGet() }
        }
        Thread.sleep(300)
        // Opening the screen reads the stored values but is not a change.
        assertEquals(0, updates.get())

        prefs.widgetTheme.set(WidgetTheme.Light)
        waitFor(updates, 1)
        assertEquals(1, updates.get())

        prefs.widgetBackgroundTransparency.set(40)
        waitFor(updates, 2)
        assertEquals(2, updates.get())
        prefs.widgetShowLogButton.set(true)
        waitFor(updates, 3)
        assertEquals(3, updates.get())
        prefs.captureTrackerEnabled.set(true)
        waitFor(updates, 4)
        assertEquals(4, updates.get())
        job.cancelAndJoin()
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
