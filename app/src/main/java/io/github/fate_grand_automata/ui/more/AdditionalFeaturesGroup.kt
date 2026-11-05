package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.SwitchPreference

fun LazyListScope.additionalFeaturesGroup(
    prefs: PrefsCore,
    preview: GameAreaPreviewManager
) {
    item {
        prefs.checkForUpdates.SwitchPreference(
            title = stringResource(R.string.p_check_for_updates),
            summary = stringResource(R.string.p_check_for_updates_summary),
            icon = icon(Icons.Default.SystemUpdate)
        )
    }
    if (BuildConfig.BUILD_TYPE == "beta") {
        item {
            prefs.supportReaderDiagnostics.SwitchPreference(
                title = stringResource(R.string.p_support_reader_diagnostics),
                summary = stringResource(R.string.p_support_reader_diagnostics_summary),
                icon = icon(R.drawable.ic_bug)
            )
        }
    }

    item {
        prefs.weeklyRouteFeatureEnabled.SwitchPreference(
            title = stringResource(R.string.p_weekly_route_feature_enabled),
            icon = icon(R.drawable.ic_gift)
        )
    }
    item {
        prefs.bondTrackingFeatureEnabled.SwitchPreference(
            title = stringResource(R.string.p_bond_tracking_feature_enabled),
            icon = icon(R.drawable.ic_gift)
        )
    }
    item {
        prefs.questItemControl.enabled.SwitchPreference(
            title = stringResource(R.string.p_enable_quest_item_control),
            icon = icon(R.drawable.ic_gift)
        )
    }
    item {
        GameAreaPreference(prefs, preview)
    }
}
