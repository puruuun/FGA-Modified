package io.github.fate_grand_automata.ui.more

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.EventRepeat
import io.github.fate_grand_automata.BuildConfig
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.root.SuperUser
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.LanguagePref
import io.github.fate_grand_automata.ui.prefs.ListPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember

fun LazyListScope.advancedGroup(
    prefs: PrefsCore,
    goToAddedFeatureTuning: () -> Unit,
    goToFineTune: () -> Unit,
    goToLicense: () -> Unit,
    goToWeeklySettings: () -> Unit = {},
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
        Preference(
            title = stringResource(R.string.weekly_route_settings),
            icon = icon(Icons.Default.EventRepeat),
            onClick = goToWeeklySettings
        )
    }
    item {
        LanguagePref().ListPreference(
            title = stringResource(R.string.p_app_language),
            icon = icon(Icons.Default.Language),
            entries = LanguagePref.availableLanguages()
        )
    }

    item {
        Preference(
            title = stringResource(R.string.added_tuning_title),
            icon = icon(R.drawable.ic_tune),
            onClick = goToAddedFeatureTuning
        )
    }

    item {
        Preference(
            title = stringResource(R.string.p_fine_tune),
            icon = icon(R.drawable.ic_tune),
            onClick = goToFineTune
        )
    }

    item {
        prefs.debugMode.SwitchPreference(
            title = stringResource(R.string.p_debug_mode),
            summary = stringResource(R.string.p_debug_mode_summary),
            icon = icon(R.drawable.ic_bug)
        )
    }

    item {
        val rootForScreenshots by prefs.useRootForScreenshots.remember()

        val enabled = !rootForScreenshots &&
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
        if (!enabled) {
            prefs.recordScreen.resetToDefault()
        }
        prefs.recordScreen.SwitchPreference(
            title = stringResource(R.string.p_record_screen),
            summary = stringResource(R.string.p_record_screen_summary),
            icon = icon(R.drawable.ic_video),
            enabled = enabled
        )
    }

    item {
        RootForScreenshots(prefs.useRootForScreenshots)
    }

    item {
        prefs.autoStartService.SwitchPreference(
            title = stringResource(R.string.p_auto_start_service),
            icon = icon(R.drawable.ic_launch)
        )
    }

    item {
        val context = LocalContext.current
        Preference(
            title = stringResource(R.string.p_battery_optimization_action),
            icon = icon(Icons.Default.BatterySaver),
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${BuildConfig.APPLICATION_ID}")
                            )
                        )
                    } catch (_: ActivityNotFoundException) {
                        // Some devices provide neither settings screen.
                    }
                }
            }
        )
    }

    item {
        prefs.stageCounterNew.SwitchPreference(
            title = stringResource(R.string.p_thresholded_stage_counter),
            icon = icon(R.drawable.ic_counter)
        )
    }

    item {
        Preference(
            title = stringResource(R.string.p_license),
            icon = icon(R.drawable.ic_info),
            onClick = goToLicense
        )
    }
}

private fun hasRootAccess() = try {
    SuperUser().close()
    true
} catch (e: Exception) {
    false
}

@Composable
private fun RootForScreenshots(
    pref: Pref<Boolean>
) {
    var state by pref.remember()
    var enabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    val action: (Boolean) -> Unit = {
        error = false
        enabled = false
        try {
            when {
                !it -> state = false
                hasRootAccess() -> state = true
                else -> error = true
            }
        } finally {
            enabled = true
        }
    }

    Column {
        Preference(
            title = stringResource(R.string.p_root_screenshot),
            summary = stringResource(if (error) R.string.root_failed else R.string.p_root_screenshot_summary),
            icon = icon(R.drawable.ic_key),
            enabled = enabled,
            onClick = { action(!state) },
        ) {
            Switch(
                checked = state,
                onCheckedChange = action,
                enabled = enabled
            )
        }
    }
}
