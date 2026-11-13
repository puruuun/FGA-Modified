package io.github.fate_grand_automata.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.AppTheme
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppThemeObserverTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs(name: String) =
        PrefsCore(PrefMaker(context.getSharedPreferences(name, Context.MODE_PRIVATE)), context)

    @After
    fun resetNightMode() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun appThemeDefaultsToSystemAndReadsBackStoredValue() {
        val spName = "app_theme_roundtrip"
        val first = prefs(spName)
        assertEquals(AppTheme.System, first.appTheme.get())

        first.appTheme.set(AppTheme.Dark)
        assertEquals(AppTheme.Dark, prefs(spName).appTheme.get())

        first.appTheme.set(AppTheme.Light)
        assertEquals(AppTheme.Light, prefs(spName).appTheme.get())

        context.getSharedPreferences(spName, Context.MODE_PRIVATE)
            .edit()
            .putString("app_theme", "UnknownFutureValue")
            .commit()
        assertEquals(AppTheme.System, prefs(spName).appTheme.get())
    }

    @Test
    fun observerSkipsInitialValueAndAppliesChangesInOrder() = runBlocking {
        val prefs = prefs("app_theme_observer")
        val applied = mutableListOf<Int>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            observeAppThemeChanges(prefs) { applied += it }
        }
        assertEquals(emptyList(), applied)

        prefs.appTheme.set(AppTheme.Dark)
        prefs.appTheme.set(AppTheme.Light)
        prefs.appTheme.set(AppTheme.System)

        assertEquals(
            listOf(
                AppCompatDelegate.MODE_NIGHT_YES,
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            ),
            applied,
        )
        job.cancelAndJoin()
    }

    @Test
    fun startIsIdempotentAndUpdatesAppCompatNightMode() {
        val prefs = prefs("app_theme_singleton_observer")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val observer = AppThemeObserver()
        try {
            observer.start(prefs, scope)
            val firstJob = observer.job
            observer.start(prefs, scope)
            assertEquals(true, firstJob === observer.job)
            assertEquals(true, firstJob!!.isActive)

            prefs.appTheme.set(AppTheme.Dark)
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

            prefs.appTheme.set(AppTheme.Light)
            assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
        } finally {
            observer.stop()
            scope.cancel()
        }
    }
}
