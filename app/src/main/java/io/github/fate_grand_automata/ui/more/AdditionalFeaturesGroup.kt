package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.SwitchPreference

fun LazyListScope.additionalFeaturesGroup(prefs: PrefsCore) {
    item {
        prefs.ignoreQuestItemUsage.SwitchPreference(
            title = stringResource(R.string.p_ignore_quest_item_usage),
            summary = stringResource(R.string.p_ignore_quest_item_usage_summary),
            icon = icon(R.drawable.ic_gift)
        )
    }
}
