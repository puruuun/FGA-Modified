package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.SwitchPreference

fun LazyListScope.additionalFeaturesGroup(prefs: PrefsCore) {
    item {
        prefs.questItemControl.enabled.SwitchPreference(
            title = stringResource(R.string.p_enable_quest_item_control),
            summary = stringResource(R.string.p_enable_quest_item_control_summary),
            icon = icon(R.drawable.ic_gift)
        )
    }
}
