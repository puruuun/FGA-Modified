package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.prefs.core.PrefsCore
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge

/**
 * Calls [onChanged] after a widget appearance or log-button availability preference changes, never
 * for the initial read. The application observes these even while another settings screen is open.
 */
suspend fun observeWidgetSettingChanges(prefs: PrefsCore, onChanged: () -> Unit) {
    merge(
        prefs.widgetTheme.asFlow().drop(1),
        prefs.widgetBackgroundTransparency.asFlow().drop(1),
        prefs.widgetShowLogButton.asFlow().drop(1),
        prefs.captureTrackerEnabled.asFlow().drop(1),
    ).collect { onChanged() }
}
