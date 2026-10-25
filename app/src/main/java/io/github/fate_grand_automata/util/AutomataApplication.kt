package io.github.fate_grand_automata.util

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.fate_grand_automata.BuildConfig
import org.opencv.android.OpenCVLoader
import timber.log.Timber

@HiltAndroidApp
class AutomataApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        initLogging()

        OpenCVLoader.initLocal()
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
    if (tag == BOND_TAG) beta && priority >= Log.INFO else debug || priority > Log.INFO ||
        (priority >= Log.INFO && tag in setOf("FGA.SupportScore", "FGA.DirectCards", FP_SELL_TAG, SKILL_LEVEL_TAG, WEEKLY_ROUTE_TAG))
