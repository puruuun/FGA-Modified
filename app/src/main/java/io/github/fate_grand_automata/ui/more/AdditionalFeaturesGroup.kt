package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import io.github.fate_grand_automata.prefs.core.PrefsCore

fun LazyListScope.additionalFeaturesGroup(
    prefs: PrefsCore,
    preview: GameAreaPreviewManager
) {
    item {
        GameAreaPreference(prefs, preview)
    }
}
