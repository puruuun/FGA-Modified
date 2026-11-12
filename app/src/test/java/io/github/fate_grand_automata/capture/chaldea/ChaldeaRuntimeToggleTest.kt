package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Issue #358 fix 2: a switch change made while the app runs must reach the manager without a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaRuntimeToggleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: android.content.SharedPreferences

    @Before fun setup() {
        ChaldeaSyncManager.resetForTest()
        store = context.getSharedPreferences("chaldea-toggle-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
    }

    @After fun cleanup() = ChaldeaSyncManager.resetForTest()

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test fun turningTheSwitchOnAtRuntimeStartsTheSync() {
        val appPrefs = PrefsCore(PrefMaker(store), context)
        ChaldeaSyncManager.init(context, appPrefs)
        Thread.sleep(200)
        repeat(5) { System.gc(); System.runFinalization(); Thread.sleep(50) }
        assertNull(ChaldeaSyncManager.hooks)
        // The UI writes through its own PrefsCore over the same file.
        val uiPrefs = PrefsCore(PrefMaker(store), context)
        uiPrefs.chaldeaSyncEnabled.set(true)
        assert(waitFor { ChaldeaSyncManager.hooks != null }) { "apply(true) never ran" }
        assertNotNull(ChaldeaSyncManager.hooks)
    }

    /** R8 removes write-only fields; without @Keep the settings collector is garbage collected in the beta build. */
    @Test fun theSettingsCollectorFieldsHaveAKeepRule() {
        val rules = java.io.File("proguard-rules.pro").readText()
        for (name in listOf("settingsScope", "settingsJob")) {
            assert(rules.substringAfter("ChaldeaSyncManager {", "").contains("$name;")) { "$name needs a keep rule" }
            ChaldeaSyncManager.javaClass.getDeclaredField(name)
        }
    }

    @Test fun turningTheSwitchOffAtRuntimeStopsTheSync() {
        val appPrefs = PrefsCore(PrefMaker(store), context)
        appPrefs.chaldeaSyncEnabled.set(true)
        ChaldeaSyncManager.init(context, appPrefs)
        assert(waitFor { ChaldeaSyncManager.hooks != null }) { "start with the switch on never ran" }
        appPrefs.chaldeaSyncEnabled.set(false)
        assert(waitFor { !ChaldeaSyncManager.isOn }) { "apply(false) never ran" }
    }
}
