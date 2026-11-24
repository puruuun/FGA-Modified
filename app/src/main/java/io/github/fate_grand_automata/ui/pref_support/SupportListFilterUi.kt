package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportFilterSettings
import io.github.fate_grand_automata.capture.SupportIdLookup
import io.github.fate_grand_automata.capture.buildSupportFilter
import io.github.fate_grand_automata.capture.captureFeaturesAvailable
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.DisplayedAscension
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * The support list filter part of a support conditions screen (#685, #695): a battle config's screen and a profile's
 * screen show the same items. [summary] says what the "Filtered" labels mean; [extra] goes under it.
 */
class SupportListFilterUi(
    val summary: Int,
    /** What the notices read; a test passes a fixed value instead of building from the files on disk. */
    val build: (@Composable (SupportPrefsCore) -> SupportFilterBuild?)? = null,
    val extra: @Composable () -> Unit = {},
    /** False for a profile: it belongs to no script, so the script-only items are hidden. */
    val script: Boolean = true,
)

/** The build of [config]'s settings, rebuilt whenever one of them changes. Null until the first build is done. */
@Composable
internal fun rememberSupportFilterBuild(config: SupportPrefsCore): SupportFilterBuild? {
    val context = LocalContext.current.applicationContext
    val build by produceState<SupportFilterBuild?>(null, config) {
        SupportFilterSettings.changes(config).collectLatest {
            value = withContext(Dispatchers.IO) {
                runCatching { buildSupportFilter(SupportFilterSettings.of(config), SupportIdLookup.load(context)) }
                    .getOrDefault(SupportFilterBuild.Off)
            }
        }
    }
    return build
}

@Composable
internal fun rememberCaptureAvailable(prefs: PrefsCore): Boolean {
    val enabled by prefs.captureTrackerEnabled.asFlow().collectAsState(initial = prefs.captureTrackerEnabled.get())
    return captureFeaturesAvailable(enabled, android.os.Build.VERSION.SDK_INT)
}

/** What the labels mean and, when these conditions would not run as the active filter, why. No switch: see #695. */
@Composable
internal fun SupportListFilterHeader(config: SupportPrefsCore, ui: SupportListFilterUi, captureAvailable: Boolean) {
    val build = ui.build?.invoke(config) ?: rememberSupportFilterBuild(config)
    Column {
        Preference(
            title = stringResource(R.string.support_list_filter),
            summary = stringResource(ui.summary),
            modifier = Modifier.testTag(SupportListFilterHeaderTag),
        )
        val notice = if (!captureAvailable) stringResource(R.string.support_list_filter_capture_off)
        else supportFilterStopReason(build)
        if (notice != null) {
            Text(
                notice,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(SupportListFilterNoticeTag),
            )
        }
        ui.extra()
    }
}

/** Why a filter that is switched on does not run, or null when nothing stops it. */
@Composable
internal fun supportFilterStopReason(build: SupportFilterBuild?): String? = when (build) {
    is SupportFilterBuild.Unmapped -> stringResource(R.string.support_list_filter_unmapped, (build.servants + build.ces).joinToString())
    is SupportFilterBuild.NoClassList -> stringResource(R.string.support_list_filter_no_class_list)
    is SupportFilterBuild.UnknownClass -> stringResource(R.string.support_list_filter_unknown_class, build.servants.joinToString())
    else -> null
}

/** Under a condition: whether the filter applies it, or only the script's screen reading does. */
@Composable
internal fun FilterScopeLabel(applied: Boolean) {
    Text(
        stringResource(if (applied) R.string.support_list_filter_applied else R.string.support_list_filter_screen_only),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
internal fun ascensionLabel(token: String): String = when (token) {
    "s1" -> stringResource(R.string.support_list_filter_stage_1)
    "s2" -> stringResource(R.string.support_list_filter_stage_2)
    "s3" -> stringResource(R.string.support_list_filter_stage_3)
    "s4" -> stringResource(R.string.support_list_filter_stage_final)
    else -> stringResource(R.string.support_list_filter_costume, token.drop(1).toIntOrNull() ?: 0)
}

/** "재림 모습": per chosen servant, the looks the filter lets through. */
@Composable
internal fun DisplayedAscensionPreference(config: SupportPrefsCore, servantNames: Map<String, String>) {
    val servants by config.preferredServants.remember()
    var stored by config.displayedAscensions.remember()
    var open by remember { mutableStateOf(false) }
    val set = stored.mapNotNull(DisplayedAscension::parse).filter { it.second in servants }
    val count = set.map { it.second }.distinct().size
    Preference(
        title = stringResource(R.string.support_list_filter_ascension),
        summary = if (count == 0) stringResource(R.string.support_list_filter_ascension_any)
        else stringResource(R.string.support_list_filter_ascension_set, count),
        onClick = { open = true },
        modifier = Modifier.testTag(DisplayedAscensionTag),
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) } },
        title = { Text(stringResource(R.string.support_list_filter_ascension)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.support_list_filter_ascension_hint), style = MaterialTheme.typography.bodySmall)
                for (servant in servants.sorted()) {
                    Text(servantNames[servant] ?: servant, style = MaterialTheme.typography.titleSmall)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (token in DisplayedAscension.STAGES + DisplayedAscension.COSTUMES) {
                            val entry = DisplayedAscension.entry(token, servant)
                            val selected = entry in stored
                            FilterChip(
                                selected = selected,
                                onClick = { stored = if (selected) stored - entry else stored + entry },
                                label = { Text(ascensionLabel(token)) },
                                modifier = Modifier.testTag("$DisplayedAscensionChipTag$token:$servant"),
                            )
                        }
                    }
                }
            }
        },
    )
}

internal const val SupportListFilterHeaderTag = "supportListFilter:header"
internal const val SupportListFilterNoticeTag = "supportListFilter:notice"
internal const val DisplayedAscensionTag = "supportListFilter:ascension"
internal const val DisplayedAscensionChipTag = "supportListFilter:ascension:"
