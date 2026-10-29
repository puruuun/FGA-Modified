package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.root.SuperUser
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember

fun LazyListScope.advancedGroup(
    prefs: PrefsCore,
    goToFineTune: () -> Unit,
    goToLicense: () -> Unit
) {
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
