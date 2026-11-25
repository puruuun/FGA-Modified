package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * Whether the launcher offers the support list filter (#695): whenever capture is usable. Otherwise the launcher has no
 * chip and no panel and looks as it did before the filter existed. The former "use this feature" switch is gone (#728);
 * a stored `support_filter_feature` is ignored.
 */
@Composable
internal fun rememberSupportFilterOffered(prefs: PrefsCore): Boolean = rememberCaptureAvailable(prefs)

/**
 * The launcher's right panel for the support list filter (#695, #708): the toggle, where the conditions came from, every
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
        // The row the middle column's options use, so the toggle looks and behaves like them (owner, #708).
        QuestItemCheckbox(
            checked = on,
            enabled = true,
            text = stringResource(R.string.support_list_filter),
            modifier = Modifier.fillMaxWidth(),
            checkboxModifier = Modifier.testTag(SupportFilterToggleTag),
            onChange = setOn,
        )
        Text(
            stringResource(R.string.support_filter_from, sourceLabel(source, sourceName)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            // A very long config or profile name must not push the conditions away.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .testTag(SupportFilterSourceTag),
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
        // The section label style of the middle column ("아이템 자동 사용", "인연포인트").
        Text(
            stringResource(R.string.support_filter_take_from),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
        val configName = remember(selectedConfigId) { selectedConfigId?.let { prefs.forBattleConfig(it).name.get() } }
        val takeConfigDescription = stringResource(R.string.support_filter_take_config)
        val loadProfileDescription = stringResource(R.string.support_filter_load_profile)
        // The size of "모두 초기화", each as wide as its own label; a long label ellipsizes on one line (#719).
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val id = selectedConfigId ?: return@OutlinedButton
                    loadConfigConditions(prefs, prefs.forBattleConfig(id).support, configName.orEmpty())
                },
                enabled = selectedConfigId != null,
                contentPadding = LauncherCompactButtonPadding,
                modifier = Modifier
                    .height(LauncherCompactButtonHeight)
                    .testTag(SupportFilterTakeConfigTag)
                    .semantics { contentDescription = takeConfigDescription },
            ) { LauncherCompactButtonLabel(stringResource(R.string.support_filter_take_script), textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            LoadProfileButton(prefs, profileIds, loadProfileDescription)
        }
    }
}

/**
 * Copies a battle config's conditions into the active filter. A displayed ascension is left behind: no screen of a
 * battle config edits it any more, so a value stored there by a 1.8.2 beta must not come along unseen (#708).
 */
internal fun loadConfigConditions(prefs: PrefsCore, from: SupportPrefsCore, name: String) {
    prefs.activeSupportFilter.copyConditionsFrom(from, withDisplayedAscensions = false)
    prefs.supportFilterSourceName.set(name)
    prefs.supportFilterSource.set(SupportFilterSource.Config)
}

@Composable
private fun LoadProfileButton(prefs: PrefsCore, profileIds: Set<String>, description: String) {
    var open by remember { mutableStateOf(false) }
    val profiles = remember(profileIds) { prefs.supportFilterProfilesToShow(profileIds) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = profiles.isNotEmpty(),
            contentPadding = LauncherCompactButtonPadding,
            modifier = Modifier
                .height(LauncherCompactButtonHeight)
                .testTag(SupportFilterLoadProfileTag)
                .semantics { contentDescription = description },
        ) { LauncherCompactButtonLabel(stringResource(R.string.support_filter_take_profile), textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
