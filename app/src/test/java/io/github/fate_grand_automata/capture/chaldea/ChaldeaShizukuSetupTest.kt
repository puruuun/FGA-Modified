package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #358: the user service arguments, the always-enabled provider and the "switch off touches nothing" rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaShizukuSetupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun provider() = ComponentName(context, "rikka.shizuku.ShizukuProvider")

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    @Test fun userServiceArgsHaveTheProcessSuffixShizukuRequires() {
        val args = ChaldeaSyncClient.userServiceArgs("io.github.test", debuggable = true, versionCode = 7)
        assertEquals("chaldea_sync", field(args, "processName"))
        assertEquals(ChaldeaSyncClient.TAG, field(args, "tag"))
        assertEquals(false, field(args, "daemon"))
        assertEquals(true, field(args, "debuggable"))
        assertEquals(7, field(args, "versionCode"))
        // forAdd() throws IllegalArgumentException("process name suffix must not be null") without the suffix.
        val forAdd = args.javaClass.getDeclaredMethod("forAdd").also { it.isAccessible = true }
        forAdd.invoke(args)
    }

    @Test fun theProviderIsEnabledInTheManifest() {
        val info = context.packageManager.getProviderInfo(provider(), 0)
        assertTrue(info.enabled)
    }

    @Test fun aLeftoverDisabledStateIsResetToDefault() {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(provider(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertTrue(ShizukuProviderReset.resetToDefault(context))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(provider()))
    }

    @Test fun anAlreadyDefaultStateIsLeftAlone() {
        assertFalse(ShizukuProviderReset.resetToDefault(context))
    }

    @Test fun withTheSwitchOffInitStartsNoSchedulerAndNoShizukuState() {
        val store = context.getSharedPreferences("chaldea-shizuku-setup-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val prefs = PrefsCore(PrefMaker(store), context)
        assertFalse(prefs.chaldeaSyncEnabled.get())
        ChaldeaSyncManager.init(context, prefs)
        Thread.sleep(300)
        assertNull(ChaldeaSyncManager.hooks)
        assertEquals(ShizukuState.NotRunning, ChaldeaSyncManager.shizukuState.value)
    }
}
