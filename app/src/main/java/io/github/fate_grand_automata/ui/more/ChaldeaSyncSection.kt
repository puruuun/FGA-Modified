package io.github.fate_grand_automata.ui.more

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccess
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccessMode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaServer
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncNowOutcome
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStatus
import io.github.fate_grand_automata.scripts.capture.chaldea.RootState
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import kotlinx.coroutines.launch

/** Everything the "Chaldea auto sync" section shows. */
data class ChaldeaSyncUi(
    val enabled: Boolean = false,
    /** Log collection (tracker switch and consent) is on, so a sync can run. */
    val loggingOn: Boolean = false,
    val closeMinutes: Int = 30,
    /** Root and Shizuku together; root wins when both are available. */
    val access: ChaldeaAccess = ChaldeaAccess(RootState.Unchecked, ShizukuState.NotRunning),
    /** What the scheduler is doing now: waiting, working, or a problem that is not about one account. */
    val status: ChaldeaSyncStatus = ChaldeaSyncStatus.Idle,
    val scriptRunning: Boolean = false,
    /** The last result of each server, kept apart so that one success does not hide the other's failure. */
    val results: Map<ChaldeaServer, ChaldeaSyncStatus> = emptyMap(),
)

internal val chaldeaCloseChoices = listOf(10, 30, 60, 0)

/**
 * The status lines. Access problems come first because nothing else can happen until they are fixed. Then one line
 * says which access is used (root, or Shizuku). Otherwise
 * there is one line per server that has a result (a single line, without the server name, when only one has), then
 * what the scheduler is doing now when that is not the idle state.
 */
@Composable
fun chaldeaStatusLines(ui: ChaldeaSyncUi): List<String> {
    if (!ui.enabled) return listOf(stringResource(R.string.chaldea_sync_status_off))
    if (!ui.loggingOn) return listOf(stringResource(R.string.chaldea_sync_needs_logging))
    val lines = ArrayList<String>()
    when (ui.access.mode) {
        ChaldeaAccessMode.Root -> lines += stringResource(R.string.chaldea_sync_access_root)
        ChaldeaAccessMode.Shizuku -> lines += stringResource(R.string.chaldea_sync_access_shizuku)
        null -> {
            val shizukuLine = when (ui.access.shizuku) {
                ShizukuState.NotInstalled -> R.string.chaldea_sync_shizuku_not_installed
                ShizukuState.NotRunning -> R.string.chaldea_sync_shizuku_not_running
                ShizukuState.NeedsPermission -> R.string.chaldea_sync_shizuku_needs_permission
                ShizukuState.Denied -> R.string.chaldea_sync_shizuku_denied
                ShizukuState.Outdated -> R.string.chaldea_sync_shizuku_outdated
                ShizukuState.Ready -> R.string.chaldea_sync_shizuku_not_running
            }
            return listOf(stringResource(shizukuLine), stringResource(R.string.chaldea_sync_root_hint))
        }
    }
    val servers = ui.results.keys.sortedBy { it.ordinal }
    for (server in servers) {
        val text = chaldeaStatusText(ui.results.getValue(server))
        lines += if (servers.size > 1) "${server.label}: $text" else text
    }
    if (ui.status != ChaldeaSyncStatus.Idle || lines.isEmpty()) lines += chaldeaStatusText(ui.status)
    return lines
}

@Composable
private fun chaldeaStatusText(status: ChaldeaSyncStatus): String = when (val s = status) {
    ChaldeaSyncStatus.Idle -> stringResource(R.string.chaldea_sync_status_idle)
    ChaldeaSyncStatus.Working -> stringResource(R.string.chaldea_sync_status_working)
    is ChaldeaSyncStatus.Synced -> buildString {
        append(
            stringResource(
                R.string.chaldea_sync_status_synced,
                DateUtils.formatDateTime(
                    androidx.compose.ui.platform.LocalContext.current, s.timeMs,
                    DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
                ),
                s.items, s.mysticCodes, s.boards, s.coins, s.craftEssences, s.commandCodes,
            ),
        )
        if (s.unmapped > 0) append(' ').append(stringResource(R.string.chaldea_sync_status_unmapped, s.unmapped))
        if (s.created) append(' ').append(stringResource(R.string.chaldea_sync_status_created))
        if (s.regionMismatch) append(' ').append(stringResource(R.string.chaldea_sync_status_region))
        if (s.bothPackages && s.packageName != null) append(' ').append(stringResource(R.string.chaldea_sync_status_package, s.packageName!!))
    }
    ChaldeaSyncStatus.WaitingScript -> stringResource(R.string.chaldea_sync_status_waiting_script)
    ChaldeaSyncStatus.WaitingVisible -> stringResource(R.string.chaldea_sync_status_waiting_visible)
    is ChaldeaSyncStatus.WaitingBackground ->
        if (s.closeAfter > 0) stringResource(R.string.chaldea_sync_status_waiting_background, s.minutes, s.closeAfter)
        else stringResource(R.string.chaldea_sync_status_waiting_background_never, s.minutes)
    is ChaldeaSyncStatus.ShizukuUnavailable -> stringResource(R.string.chaldea_sync_shizuku_not_running)
    ChaldeaSyncStatus.CannotBind -> stringResource(R.string.chaldea_sync_status_cannot_bind)
    ChaldeaSyncStatus.StateUnknown -> stringResource(R.string.chaldea_sync_status_state_unknown)
    ChaldeaSyncStatus.ChaldeaStartedDuring -> stringResource(R.string.chaldea_sync_status_started_during)
    ChaldeaSyncStatus.FileNotFound -> stringResource(R.string.chaldea_sync_status_not_found)
    ChaldeaSyncStatus.NoAccess -> stringResource(R.string.chaldea_sync_status_no_access)
    ChaldeaSyncStatus.UnknownVersion -> stringResource(R.string.chaldea_sync_status_unknown_version)
    ChaldeaSyncStatus.FileUnreadable -> stringResource(R.string.chaldea_sync_status_unreadable)
    ChaldeaSyncStatus.OwnerChanged -> stringResource(R.string.chaldea_sync_status_owner_changed)
    ChaldeaSyncStatus.BackupFailed -> stringResource(R.string.chaldea_sync_status_backup_failed)
    ChaldeaSyncStatus.AccountNotIdentified -> stringResource(R.string.chaldea_sync_status_not_identified)
    ChaldeaSyncStatus.FailedRestored -> stringResource(R.string.chaldea_sync_status_restored)
    is ChaldeaSyncStatus.RestoreFailed -> stringResource(R.string.chaldea_sync_status_restore_failed, s.backupPath)
    is ChaldeaSyncStatus.Failed -> stringResource(R.string.chaldea_sync_status_failed, s.code.name)
    is ChaldeaSyncStatus.RecoveryBlocked -> stringResource(R.string.chaldea_sync_status_recovery_blocked, s.code.name)
}

/** The section below the log collection settings. */
fun LazyListScope.chaldeaSyncItems(
    ui: ChaldeaSyncUi,
    onEnabled: (Boolean) -> Unit,
    onCloseMinutes: (Int) -> Unit,
    syncNow: suspend (ChaldeaRunState?) -> ChaldeaSyncNowOutcome,
) {
    item { PreferenceGroupHeader(stringResource(R.string.chaldea_sync_header)) }
    item {
        Preference(
            title = stringResource(R.string.chaldea_sync_enable),
            summary = stringResource(R.string.chaldea_sync_enable_summary),
            onClick = { onEnabled(!ui.enabled) },
        ) { Switch(checked = ui.enabled, onCheckedChange = onEnabled) }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chaldea_sync_close_title), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (minutes in chaldeaCloseChoices) {
                    FilterChip(
                        selected = ui.closeMinutes == minutes,
                        onClick = { onCloseMinutes(minutes) },
                        label = {
                            Text(
                                if (minutes == 0) stringResource(R.string.chaldea_sync_close_never)
                                else stringResource(R.string.chaldea_sync_close_after, minutes),
                            )
                        },
                    )
                }
            }
        }
    }
    item { ChaldeaSyncNow(ui, syncNow) }
}

@Composable
private fun ChaldeaSyncNow(ui: ChaldeaSyncUi, syncNow: suspend (ChaldeaRunState?) -> ChaldeaSyncNowOutcome) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ChaldeaRunState?>(null) }

    fun run(confirmed: ChaldeaRunState?) {
        busy = true
        scope.launch {
            try {
                val outcome = syncNow(confirmed)
                // After the dialog the state may have changed (for example to on screen): ask again for that state.
                ask = (outcome as? ChaldeaSyncNowOutcome.NeedsConfirm)?.state
            } finally {
                busy = false
            }
        }
    }

    ask?.let { state ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.chaldea_sync_now)) },
            text = {
                Text(
                    stringResource(
                        if (state == ChaldeaRunState.Visible) R.string.chaldea_sync_confirm_visible
                        else R.string.chaldea_sync_confirm_background,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { ask = null; run(state) }) { Text(stringResource(R.string.chaldea_sync_close_and_sync)) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in chaldeaStatusLines(ui)) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { run(null) },
            enabled = ui.enabled && ui.loggingOn && (ui.access.ready || ui.access.root != RootState.Granted) && !ui.scriptRunning && !busy,
        ) { Text(stringResource(R.string.chaldea_sync_now)) }
    }
}
