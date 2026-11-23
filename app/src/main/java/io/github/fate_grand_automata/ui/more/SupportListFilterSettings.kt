package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.FriendFilter
import io.github.fate_grand_automata.scripts.capture.FriendFormation
import io.github.fate_grand_automata.scripts.capture.FriendList
import io.github.fate_grand_automata.ui.FGASwitchColors
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the pickers offer: the servants and CEs of the current account's friend roster. */
private class SupportFilterOptions(
    val servantCounts: Map<Long, Int> = emptyMap(),
    val ceCounts: Map<Long, Int> = emptyMap(),
    val names: GameNameMap = GameNameMap(),
)

/** Experiment (#636): the switch and the two pickers of the support list filter. */
fun LazyListScope.supportListFilterItems(prefs: PrefsCore) {
    item { PreferenceGroupHeader(stringResource(R.string.capture_support_filter_header)) }
    item { SupportListFilterSettings(prefs) }
}

/** The last follower/list result of this app process, counts only; nothing is shown before the first list. */
@Composable
internal fun SupportListLastResultLine(modifier: Modifier = Modifier) {
    val last by SupportListLastResult.last.collectAsStateWithLifecycle()
    val text = when (val result = last) {
        null -> return
        is SupportListLastResult.Result.Filtered -> result.counts.let {
            stringResource(R.string.capture_support_filter_last, it.followersBefore, it.followersAfter, it.slotsBefore, it.slotsAfter)
        }
        SupportListLastResult.Result.Unchanged -> stringResource(R.string.capture_support_filter_last_unchanged)
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SupportListFilterSettings(prefs: PrefsCore) {
    var enabled by prefs.captureSupportFilterEnabled.remember()
    var servants by remember { mutableStateOf(prefs.captureSupportFilterServants.get().mapNotNull { it.toLongOrNull() }.toSet()) }
    var ces by remember { mutableStateOf(prefs.captureSupportFilterCes.get().mapNotNull { it.toLongOrNull() }.toSet()) }
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var options by remember { mutableStateOf(SupportFilterOptions()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentId by CaptureTracker.currentId.collectAsStateWithLifecycle()
    LaunchedEffect(currentId) {
        options = withContext(Dispatchers.IO) {
            val roster = currentId?.let { CaptureTracker.loadRoster(it) } ?: return@withContext SupportFilterOptions()
            val rows = FriendFormation.entries.flatMap { FriendList.rows(roster, it) }
            val slots = roster.friends.values.flatMap { friend -> friend.slots + friend.grandSlots.map { it.supportSlot() } }
            val names = GameNames(File(context.filesDir, "capture/names")).friends(
                slots.map { it.svtId }.toSet(),
                slots.flatMap { listOfNotNull(it.ceId, it.ce2Id) }.filter { it != 0L }.toSet(),
            )
            SupportFilterOptions(FriendList.servantCounts(rows), FriendList.ceCounts(rows), names)
        }
    }

    Column {
        Preference(
            title = stringResource(R.string.capture_support_filter),
            summary = stringResource(R.string.capture_support_filter_summary),
            onClick = { enabled = !enabled },
        ) { Switch(checked = enabled, onCheckedChange = { enabled = it }, colors = FGASwitchColors()) }
        SupportListLastResultLine(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = false }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.capture_support_filter_servants, servants.size))
            }
            OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.capture_support_filter_ces, ces.size))
            }
        }
    }

    picker?.let { ce ->
        Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                FriendPicker(
                    ce = ce,
                    counts = if (ce) options.ceCounts else options.servantCounts,
                    names = options.names,
                    filter = FriendFilter(servantIds = servants, ceIds = ces),
                    onDismiss = { picker = null },
                ) { id ->
                    val selected = if (ce) ces else servants
                    val updated = when {
                        id == null -> emptySet()
                        id in selected -> selected - id
                        else -> selected + id
                    }
                    if (ce) {
                        ces = updated
                        prefs.captureSupportFilterCes.set(updated.map { it.toString() }.toSet())
                    } else {
                        servants = updated
                        prefs.captureSupportFilterServants.set(updated.map { it.toString() }.toSet())
                    }
                }
            }
        }
    }
}
