package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportFilterSettings
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.capture.DisplayedAscension
import io.github.fate_grand_automata.ui.FGASwitchColors
import io.github.fate_grand_automata.ui.prefs.remember
import kotlinx.coroutines.flow.map

/**
 * The play button menu's line for the manual support list filter (#685): its switch and what it filters by.
 * Editing happens under More > 서포트 목록 필터.
 */
@Composable
internal fun ManualSupportFilterRow(manual: SupportPrefsCore, modifier: Modifier = Modifier) {
    var enabled by manual.supportListFilter.remember()
    val changes = remember(manual) { SupportFilterSettings.changes(manual).map { SupportFilterSettings.of(manual) } }
    val settings by changes.collectAsState(initial = SupportFilterSettings.of(manual))
    val setEnabled = { on: Boolean ->
        enabled = on
        if (!on) SupportListLastResult.clear()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { setEnabled(!enabled) }
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(ManualSupportFilterRowTag),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.support_list_filter_manual_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                supportFilterSummary(settings),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = enabled, onCheckedChange = setEnabled, colors = FGASwitchColors())
    }
}

/** One line of the conditions the filter applies, on the same terms as the criteria builder. */
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
    if (settings.friendsOnly) parts += stringResource(R.string.support_list_filter_part_friends)
    return if (parts.isEmpty()) stringResource(R.string.support_list_filter_none) else parts.joinToString(" · ")
}

internal const val ManualSupportFilterRowTag = "launcher:supportListFilter"
