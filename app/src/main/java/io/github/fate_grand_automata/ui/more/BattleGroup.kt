package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.ListPreference
import io.github.fate_grand_automata.ui.prefs.SingleSelectChipPreference
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.stringRes

fun LazyListScope.battleGroup(
    prefs: PrefsCore
) {
    item {
        prefs.gameServerRaw.ListPreference(
            title = stringResource(R.string.p_game_server),
            icon = icon(Icons.Default.Public),
            entries =
            mapOf(PrefsCore.GAME_SERVER_AUTO_DETECT to stringResource(R.string.p_game_server_auto_detect))
                .plus(
                    GameServers.values.associate {
                        it.serialize() to stringResource(it.stringRes)
                    }
                )
        )
    }

    item {
        prefs.storySkip.SwitchPreference(
            title = stringResource(R.string.p_story_skip),
            summary = stringResource(R.string.p_story_skip_summary),
            icon = icon(Icons.Default.FastForward)
        )
    }

    item {
        prefs.stopOnCEGet.SwitchPreference(
            title = stringResource(R.string.p_stop_on_ce_get),
            summary = stringResource(R.string.p_stop_on_ce_get_summary),
            icon = icon(R.drawable.ic_card)
        )
    }

    item {
        prefs.stopOnFirstClearRewards.SwitchPreference(
            title = stringResource(R.string.p_stop_on_first_clear_rewards),
            icon = icon(R.drawable.ic_gift)
        )
    }

    item {
        prefs.screenshotDrops.SwitchPreference(
            title = stringResource(R.string.p_screenshot_drops),
            summary = stringResource(R.string.p_screenshot_drops_summary),
            icon = icon(R.drawable.ic_screenshot)
        )
    }

    item {
        prefs.screenshotDropsUnmodified.SwitchPreference(
            title = stringResource(R.string.p_screenshot_drops_unmodified),
            summary = stringResource(R.string.p_screenshot_drops_unmodified_summary),
            icon = icon(R.drawable.ic_screenshot)
        )
    }
    item {
        prefs.screenshotBond.SwitchPreference(
            title = stringResource(R.string.p_screenshot_bond),
            summary = stringResource(R.string.p_screenshot_bond_summary),
            icon = icon(R.drawable.ic_screenshot)
        )
    }

    item {
        prefs.missionPopupClear.SwitchPreference(
            title = stringResource(R.string.p_mission_popup_clear),
            summary = stringResource(R.string.p_mission_popup_clear_summary),
            icon = icon(Icons.Default.TouchApp)
        )
    }
    item {
        val enabled by prefs.missionPopupClear.remember()
        var seconds by prefs.missionPopupClearSeconds.remember()
        val resources = LocalResources.current
        InlineSubStepperRow(
            title = stringResource(R.string.p_mission_popup_clear_seconds),
            value = seconds,
            onValueChange = { seconds = it },
            valueRange = 1..60,
            enabled = enabled,
            valueRepresentation = {
                resources.getString(R.string.p_mission_popup_clear_seconds_value, it)
            },
            modifier = Modifier.testTag("mission-popup-clear-seconds"),
            titleTestTag = "mission-popup-clear-seconds-title"
        )
    }

    item {
        prefs.hideSQInAPResources.SwitchPreference(
            title = stringResource(R.string.p_hide_sq_in_resource),
            summary = stringResource(R.string.p_hide_sq_in_resource_summary),
            icon = icon(R.drawable.ic_apple)
        )
    }

    item {
        prefs.boostItemSelectionMode.SingleSelectChipPreference(
            title = stringResource(R.string.p_boost_item),
            icon = icon(Icons.Default.OfflineBolt),
            entries = (-1..3).associateWith { it.boostItemString() }
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
            icon = icon(R.drawable.ic_gift)
        )
    }
}

@Composable
private fun InlineSubStepperRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    enabled: Boolean,
    valueRepresentation: (Int) -> String,
    modifier: Modifier = Modifier,
    titleTestTag: String? = null
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    // Match ListItem's container/padding/leading slot (16 + 40 + 16) plus 16 dp sub-item indent
    // without ListItem's mergeDescendants, keeping the title and stepper nodes individually addressable.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(40.dp))
                Spacer(Modifier.width(16.dp))
                Row(
                    modifier = Modifier.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = title,
                        color = titleColor,
                        modifier = Modifier
                            .then(titleTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                            .weight(1f, fill = false)
                    )
                    Stepper(
                        value = value,
                        onValueChange = onValueChange,
                        valueRange = valueRange,
                        enabled = enabled,
                        valueRepresentation = valueRepresentation
                    )
                }
            }
        }
    }
}

@Composable
private fun Int.boostItemString() = when (this) {
    -1 -> stringResource(R.string.p_boost_item_disabled)
    0 -> stringResource(R.string.p_boost_item_skip)
    else -> toString()
}
