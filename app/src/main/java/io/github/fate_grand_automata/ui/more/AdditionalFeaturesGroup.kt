package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.Preference

fun LazyListScope.additionalFeaturesGroup(prefs: PrefsCore, importOriginalData: () -> Unit, importPortraits: () -> Unit) {
    item {
        prefs.checkForUpdates.SwitchPreference(
            title = stringResource(R.string.p_check_for_updates),
            summary = stringResource(R.string.p_check_for_updates_summary),
            icon = icon(Icons.Default.SystemUpdate)
        )
    }
    item {
        Preference(
            title = stringResource(R.string.original_import_title),
            summary = stringResource(R.string.original_import_summary),
            icon = icon(R.drawable.ic_folder_edit),
            onClick = importOriginalData
        )
    }
    item {
        Preference(
            title = stringResource(R.string.portrait_import_title),
            summary = stringResource(R.string.portrait_import_summary),
            icon = icon(Icons.Default.Image),
            onClick = importPortraits
        )
    }
    item {
        prefs.supportReaderDiagnostics.SwitchPreference(
            title = stringResource(R.string.p_support_reader_diagnostics),
            summary = stringResource(R.string.p_support_reader_diagnostics_summary),
            icon = icon(R.drawable.ic_bug)
        )
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
            summary = stringResource(R.string.p_enable_quest_item_control_summary),
            icon = icon(R.drawable.ic_gift)
        )
    }
}
