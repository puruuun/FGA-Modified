package io.github.fate_grand_automata.util

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.capture.deleteLegacyEventCache
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.widget.WidgetRefresh
import io.github.fate_grand_automata.widget.WidgetSettingsObserver
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import java.io.File

@HiltAndroidApp
class AutomataApplication : Application() {
    @Inject lateinit var prefsCore: PrefsCore
    internal val appThemeObserver = AppThemeObserver()

    override fun onCreate() {
        super.onCreate()

        initLogging()

        AppCompatDelegate.setDefaultNightMode(prefsCore.appTheme.get().nightMode())
        appThemeObserver.start(prefsCore)

        OpenCVLoader.initLocal()

        WidgetRefresh.requestUpdate(this)
        // Reaches the sync hooks (no Context there) and starts the sync only when its switch is on.
        io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncManager.init(this, prefsCore)
        WidgetSettingsObserver.start(prefsCore) { WidgetRefresh.requestUpdate(this@AutomataApplication) }

        CoroutineScope(Dispatchers.IO).launch { deleteLegacyEventCache(File(filesDir, "capture/names")) }
    }

    private fun initLogging() {
        Timber.plant(FgaTree())
    }

    private class FgaTree : Timber.DebugTree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            return isFgaLoggable(tag, priority, BuildConfig.DEBUG, BuildConfig.BUILD_TYPE in setOf("beta", "deviceTest"))
        }
    }
}

/** Same production policy, with an explicit debug flag so non-debug logging is unit-testable. */
internal fun isFgaLoggable(tag: String?, priority: Int, debug: Boolean, beta: Boolean = false): Boolean =
    if (tag in setOf(BOND_TAG, SUPPORT_CE_TAG, FACE_CARDS_TAG)) beta && priority >= Log.INFO else debug || priority > Log.INFO ||
        (priority >= Log.INFO && tag in setOf("FGA.SupportScore", "FGA.DirectCards", FP_SELL_TAG, SKILL_LEVEL_TAG, WEEKLY_ROUTE_TAG))
