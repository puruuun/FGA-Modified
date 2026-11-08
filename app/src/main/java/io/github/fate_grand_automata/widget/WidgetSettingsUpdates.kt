package io.github.fate_grand_automata.widget

import io.github.fate_grand_automata.prefs.core.PrefsCore
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge

/**
 * Calls [onChanged] after the user changes the widget theme or opacity, never for the value read when
 * collection starts. Collecting never returns; cancel it with the screen.
 */
suspend fun observeWidgetSettingChanges(prefs: PrefsCore, onChanged: () -> Unit) {
    merge(
        prefs.widgetTheme.asFlow().drop(1),
        prefs.widgetBackgroundTransparency.asFlow().drop(1),
    ).collect { onChanged() }
}
