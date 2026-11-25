package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportFilterSettings
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.capture.loadSupportFilter
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.DisplayedAscension
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.util.stringRes
import io.github.fate_grand_automata.ui.FGASwitchColors
import io.github.fate_grand_automata.ui.more.SupportListLastResultLine
import io.github.fate_grand_automata.ui.pref_support.ascensionLabel
import io.github.fate_grand_automata.ui.pref_support.rememberCaptureAvailable
import io.github.fate_grand_automata.ui.pref_support.rememberSupportFilterBuild
import io.github.fate_grand_automata.ui.pref_support.supportFilterStopReason
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedCEName
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedServantName
import io.github.fate_grand_automata.util.portraits.ServantPortraitFolder
import kotlinx.coroutines.flow.map

/**
 * Whether the launcher offers the support list filter (#695): only with the feature switch on and capture usable.
 * Otherwise the launcher has no chip and no panel and looks as it did before the filter existed.
 */
@Composable
internal fun rememberSupportFilterOffered(prefs: PrefsCore): Boolean {
    val feature by prefs.supportFilterFeature.remember()
    return rememberCaptureAvailable(prefs) && feature
}

/**
 * The launcher's right panel for the support list filter (#695): the toggle, where the conditions came from, every
 * active condition, and the two ways to fill them. Editing happens on the profile screen under More.
 */
@Composable
internal fun SupportFilterLauncherPanel(
    prefs: PrefsCore,
    /** The battle config selected in the launcher, or null when there is none to take conditions from. */
    selectedConfigId: String?,
    modifier: Modifier = Modifier,
    /** A test passes a fixed value instead of building from the files on disk. */
    build: (@Composable (SupportPrefsCore) -> SupportFilterBuild?)? = null,
) {
    val active = prefs.activeSupportFilter
    var on by prefs.supportFilterOn.remember()
    val source by prefs.supportFilterSource.remember()
    val sourceName by prefs.supportFilterSourceName.remember()
    val profileIds by prefs.supportFilterProfiles.remember()
    val changes = remember(active) { SupportFilterSettings.changes(active).map { SupportFilterSettings.of(active) } }
    val settings by changes.collectAsState(initial = SupportFilterSettings.of(active))
    val reason = if (on) supportFilterStopReason(build?.invoke(active) ?: rememberSupportFilterBuild(active)) else null
    val setOn = { value: Boolean ->
        on = value
        if (!value) SupportListLastResult.clear()
    }

    Column(
        modifier
            .testTag(SupportFilterPanelTag)
            .fillMaxHeight()
            .padding(start = 4.dp, end = 4.dp, top = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.support_list_filter),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = on,
                onCheckedChange = setOn,
                colors = FGASwitchColors(),
                modifier = Modifier.testTag(SupportFilterToggleTag),
            )
        }
        Text(
            stringResource(R.string.support_filter_from, sourceLabel(source, sourceName)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            // A very long config or profile name must not push the conditions away.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(SupportFilterSourceTag),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (reason != null) {
                Text(
                    reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(SupportFilterReasonTag),
                )
            }
            for (line in supportFilterLines(settings)) {
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SupportListLastResultLine()
        Text(
            stringResource(R.string.support_filter_refresh_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val configName = remember(selectedConfigId) { selectedConfigId?.let { prefs.forBattleConfig(it).name.get() } }
        SideBySideOrStacked(Modifier.padding(vertical = 4.dp)) {
            OutlinedButton(
                onClick = {
                    val id = selectedConfigId ?: return@OutlinedButton
                    prefs.loadSupportFilter(prefs.forBattleConfig(id).support, SupportFilterSource.Config, configName.orEmpty())
                },
                enabled = selectedConfigId != null,
                modifier = Modifier.testTag(SupportFilterTakeConfigTag),
            ) { Text(stringResource(R.string.support_filter_take_config), textAlign = TextAlign.Center) }
            LoadProfileButton(prefs, profileIds)
        }
    }
}

/**
 * The children side by side at their natural width when they all fit, otherwise one under the other at the full width,
 * so a button label never gets clipped in a narrow panel (owner, #695).
 */
@Composable
private fun SideBySideOrStacked(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val fits = natural.sum() + gap * (measurables.size - 1).coerceAtLeast(0) <= constraints.maxWidth
        val placeables = measurables.mapIndexed { i, m ->
            val width = if (fits) natural[i] else constraints.maxWidth
            m.measure(Constraints.fixedWidth(width))
        }
        val width = if (fits) natural.sum() + gap * (placeables.size - 1).coerceAtLeast(0) else constraints.maxWidth
        val height = if (fits) placeables.maxOfOrNull { it.height } ?: 0 else placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var x = 0
            var y = 0
            for (p in placeables) {
                p.placeRelative(x, y)
                if (fits) x += p.width + gap else y += p.height + gap
            }
        }
    }
}

@Composable
private fun LoadProfileButton(prefs: PrefsCore, profileIds: Set<String>) {
    var open by remember { mutableStateOf(false) }
    val profiles = remember(profileIds) { prefs.supportFilterProfilesToShow(profileIds) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = profiles.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SupportFilterLoadProfileTag),
        ) { Text(stringResource(R.string.support_filter_load_profile), textAlign = TextAlign.Center) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (profile in profiles) {
                val name = profile.name.get()
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        prefs.loadSupportFilter(profile.support, SupportFilterSource.Profile, name)
                        open = false
                    },
                    modifier = Modifier.testTag("$SupportFilterProfileItemTag${profile.id}"),
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: SupportFilterSource, name: String): String = when (source) {
    SupportFilterSource.None -> stringResource(R.string.support_filter_from_none)
    SupportFilterSource.Config -> stringResource(R.string.support_filter_from_config, name)
    SupportFilterSource.Profile -> stringResource(R.string.support_filter_from_profile, name)
}

/**
 * Every condition the filter applies, one line each and only when set, on the same terms as the criteria builder:
 * final ascension, skills, appends and the grand conditions count only with a preferred servant.
 */
@Composable
internal fun supportFilterLines(settings: SupportFilterSettings): List<String> {
    val context = LocalContext.current
    val lines = mutableListOf<String>()
    val hasServants = settings.servants.isNotEmpty()
    fun maxed(levels: List<Boolean>) = levels.joinToString("/") { if (it) "10" else "x" }
    if (hasServants) {
        val looks = settings.displayedAscensions.mapNotNull(DisplayedAscension::parse).groupBy({ it.second }, { it.first })
        val order = DisplayedAscension.STAGES + DisplayedAscension.COSTUMES
        val servants = settings.servants.sorted().map { servant ->
            val name = getLocalizedServantName(context, servant)
            val chosen = looks[servant].orEmpty().sortedBy(order::indexOf)
            if (chosen.isEmpty()) name else "$name (${chosen.map { ascensionLabel(it) }.joinToString(", ")})"
        }
        lines += stringResource(R.string.support_filter_line_servants, servants.joinToString(", "))
    }
    if (settings.ces.isNotEmpty()) {
        val ces = settings.ces.sorted().joinToString(", ") { getLocalizedCEName(context, ServantPortraitFolder.displayName(it)) }
        lines += stringResource(R.string.support_filter_line_ces, ces) +
            if (settings.mlb) " · " + stringResource(R.string.support_list_filter_part_mlb) else ""
    }
    if (hasServants) {
        if (settings.maxAscended) lines += stringResource(R.string.support_list_filter_part_final)
        if (settings.skills.any { it }) lines += stringResource(R.string.support_list_filter_part_skills, maxed(settings.skills))
        if (settings.appendCheck && settings.appends.any { it }) {
            lines += stringResource(R.string.support_list_filter_part_appends, maxed(settings.appends))
        }
    }
    if (settings.npCheck && settings.minimumNp > 1) lines += stringResource(R.string.support_list_filter_part_np, settings.minimumNp)
    if (settings.score62) lines += stringResource(R.string.support_list_filter_part_score)
    lines += grandParts(settings)
    if (settings.friendsOnly) lines += stringResource(R.string.support_list_filter_part_friends)
    if (lines.isEmpty()) lines += stringResource(R.string.support_list_filter_none)
    return lines
}

/**
 * The grand conditions (#704) on the criteria builder's terms: grand servant and +16 only with a preferred servant, the
 * bond CE effect and both CE slots only with it and a preferred CE.
 */
@Composable
private fun grandParts(settings: SupportFilterSettings): List<String> {
    if (!settings.grandServant || settings.servants.isEmpty()) return emptyList()
    val parts = mutableListOf(stringResource(R.string.p_battle_config_support_grand_servant))
    if (settings.grandScore16) parts += stringResource(R.string.support_list_filter_part_grand_score)
    if (settings.ces.isNotEmpty()) {
        if (settings.bondCeEffect != BondCEEffectEnum.Ignore) {
            parts += stringResource(R.string.support_list_filter_part_bond_ce, stringResource(settings.bondCeEffect.stringRes))
        }
        if (settings.bothCeSlots) parts += stringResource(R.string.support_list_filter_part_both_ces)
    }
    return parts
}

/** One line of the conditions, for a profile in the profile list. */
@Composable
internal fun supportFilterSummary(settings: SupportFilterSettings): String {
    val parts = mutableListOf<String>()
    val hasServants = settings.servants.isNotEmpty()
    fun maxed(levels: List<Boolean>) = levels.joinToString("/") { if (it) "10" else "x" }
    if (hasServants) parts += stringResource(R.string.support_list_filter_part_servants, settings.servants.size)
    if (settings.ces.isNotEmpty()) {
        parts += stringResource(R.string.support_list_filter_part_ces, settings.ces.size)
        if (settings.mlb) parts += stringResource(R.string.support_list_filter_part_mlb)
    }
    if (hasServants) {
        if (settings.maxAscended) parts += stringResource(R.string.support_list_filter_part_final)
        if (settings.displayedAscensions.mapNotNull(DisplayedAscension::parse).any { it.second in settings.servants }) {
            parts += stringResource(R.string.support_list_filter_part_ascension)
        }
        if (settings.skills.any { it }) parts += stringResource(R.string.support_list_filter_part_skills, maxed(settings.skills))
        if (settings.appendCheck && settings.appends.any { it }) {
            parts += stringResource(R.string.support_list_filter_part_appends, maxed(settings.appends))
        }
    }
    if (settings.npCheck && settings.minimumNp > 1) parts += stringResource(R.string.support_list_filter_part_np, settings.minimumNp)
    if (settings.score62) parts += stringResource(R.string.support_list_filter_part_score)
    parts += grandParts(settings)
    if (settings.friendsOnly) parts += stringResource(R.string.support_list_filter_part_friends)
    return if (parts.isEmpty()) stringResource(R.string.support_list_filter_none) else parts.joinToString(" · ")
}

internal const val SupportFilterChipTag = "launcher:supportFilter:chip"
internal const val SupportFilterPanelTag = "launcher:supportFilter:panel"
internal const val SupportFilterToggleTag = "launcher:supportFilter:toggle"
internal const val SupportFilterSourceTag = "launcher:supportFilter:source"
internal const val SupportFilterReasonTag = "launcher:supportFilter:reason"
internal const val SupportFilterTakeConfigTag = "launcher:supportFilter:takeConfig"
internal const val SupportFilterLoadProfileTag = "launcher:supportFilter:loadProfile"
internal const val SupportFilterProfileItemTag = "launcher:supportFilter:profile:"
