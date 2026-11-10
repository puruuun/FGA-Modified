package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.prefs.core.PrefsCore
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Calls [onChanged] after a widget appearance or log-button availability preference changes, never
 * for the initial read. The application observes these even while another settings screen is open.
 */
suspend fun observeWidgetSettingChanges(prefs: PrefsCore, onChanged: () -> Unit) {
    merge(
        prefs.widgetTheme.asFlow().drop(1),
        prefs.widgetBackgroundTransparency.asFlow().drop(1),
        prefs.widgetShowLogButton.asFlow().drop(1),
        prefs.widgetDisplayStyle.asFlow().drop(1),
        prefs.widgetShowServiceButton.asFlow().drop(1),
        prefs.widgetShowServer.asFlow().drop(1),
        prefs.captureTrackerEnabled.asFlow().drop(1),
    ).collect { onChanged() }
}

/**
 * Keeps one process-long observer of the widget settings. The collector is held through a static job in a
 * static scope: SharedPreferences keeps its listeners weakly, so an unreferenced collector would be
 * garbage-collected and stop redrawing the widgets.
 */
object WidgetSettingsObserver {
    @Volatile
    internal var job: kotlinx.coroutines.Job? = null
        private set

    @Synchronized
    fun start(
        prefs: PrefsCore,
        scope: kotlinx.coroutines.CoroutineScope = WidgetRefresh.scope,
        onChanged: () -> Unit,
    ) {
        if (job?.isActive == true) return
        job = scope.launch { observeWidgetSettingChanges(prefs, onChanged) }
    }

    @Synchronized
    internal fun stop() {
        job?.cancel()
        job = null
    }
}
