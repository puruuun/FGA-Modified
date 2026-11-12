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
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncNowOutcome
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStatus
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
    val shizuku: ShizukuState = ShizukuState.NotRunning,
    val status: ChaldeaSyncStatus = ChaldeaSyncStatus.Idle,
    val scriptRunning: Boolean = false,
)

internal val chaldeaCloseChoices = listOf(10, 30, 60, 0)

/** The one status line. Shizuku problems come first because nothing else can happen until they are fixed. */
@Composable
fun chaldeaStatusText(ui: ChaldeaSyncUi): String {
    if (!ui.enabled) return stringResource(R.string.chaldea_sync_status_off)
    if (!ui.loggingOn) return stringResource(R.string.chaldea_sync_needs_logging)
    when (ui.shizuku) {
        ShizukuState.NotInstalled -> return stringResource(R.string.chaldea_sync_shizuku_not_installed)
        ShizukuState.NotRunning -> return stringResource(R.string.chaldea_sync_shizuku_not_running)
        ShizukuState.NeedsPermission -> return stringResource(R.string.chaldea_sync_shizuku_needs_permission)
        ShizukuState.Denied -> return stringResource(R.string.chaldea_sync_shizuku_denied)
        ShizukuState.Outdated -> return stringResource(R.string.chaldea_sync_shizuku_outdated)
        ShizukuState.Ready -> {}
    }
    return when (val s = ui.status) {
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
                    s.items, s.mysticCodes, s.boards,
                ),
            )
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
        ChaldeaSyncStatus.BackupFailed -> stringResource(R.string.chaldea_sync_status_backup_failed)
        ChaldeaSyncStatus.AccountNotIdentified -> stringResource(R.string.chaldea_sync_status_not_identified)
        ChaldeaSyncStatus.FailedRestored -> stringResource(R.string.chaldea_sync_status_restored)
        is ChaldeaSyncStatus.RestoreFailed -> stringResource(R.string.chaldea_sync_status_restore_failed, s.backupPath)
        is ChaldeaSyncStatus.Failed -> stringResource(R.string.chaldea_sync_status_failed, s.code.name)
    }
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
        Text(chaldeaStatusText(ui), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { run(null) },
            enabled = ui.enabled && ui.loggingOn && ui.shizuku == ShizukuState.Ready && !ui.scriptRunning && !busy,
        ) { Text(stringResource(R.string.chaldea_sync_now)) }
    }
}
