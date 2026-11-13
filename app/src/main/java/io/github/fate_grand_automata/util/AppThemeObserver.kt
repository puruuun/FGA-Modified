package io.github.fate_grand_automata.util

import androidx.appcompat.app.AppCompatDelegate
import io.github.fate_grand_automata.prefs.core.AppTheme
import io.github.fate_grand_automata.prefs.core.PrefsCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Maps [AppTheme] to the corresponding [AppCompatDelegate] night mode constant. */
fun AppTheme.nightMode(): Int = when (this) {
    AppTheme.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    AppTheme.Light -> AppCompatDelegate.MODE_NIGHT_NO
    AppTheme.Dark -> AppCompatDelegate.MODE_NIGHT_YES
}

/**
 * Observes [PrefsCore.appTheme] changes (skipping the initial read) and applies the night mode.
 * AppCompat's `setDefaultNightMode` ignores calls that repeat the current mode.
 */
suspend fun observeAppThemeChanges(
    prefs: PrefsCore,
    applyMode: (Int) -> Unit = AppCompatDelegate::setDefaultNightMode,
) {
    prefs.appTheme.asFlow().drop(1).collect { theme ->
        applyMode(theme.nightMode())
    }
}

/**
 * Keeps a main-thread collector of [PrefsCore.appTheme] alive for the lifetime of the
 * application so changes take effect immediately across open AppCompat activities.
 */
class AppThemeObserver {
    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    internal var job: Job? = null
        private set

    @Synchronized
    fun start(
        prefs: PrefsCore,
        scope: CoroutineScope = defaultScope,
        applyMode: (Int) -> Unit = AppCompatDelegate::setDefaultNightMode,
    ) {
        if (job?.isActive == true) return
        job = scope.launch { observeAppThemeChanges(prefs, applyMode) }
    }

    @Synchronized
    internal fun stop() {
        job?.cancel()
        job = null
    }
}
