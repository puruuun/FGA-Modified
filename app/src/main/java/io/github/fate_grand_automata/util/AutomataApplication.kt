package io.github.fate_grand_automata.util

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.capture.deleteLegacyEventCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import java.io.File

@HiltAndroidApp
class AutomataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        initLogging()

        OpenCVLoader.initLocal()

        io.github.fate_grand_automata.widget.WidgetRefresh.requestUpdate(this)

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
