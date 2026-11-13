package io.github.fate_grand_automata.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.ChaldeaExportAction
import io.github.fate_grand_automata.ui.rememberChaldeaExport
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.util.grouped
import java.text.DateFormat
import java.util.Date

/** Account cards stay in the capture package; settings only supplies the current list. */
internal fun LazyListScope.captureAccountCards(
    accounts: List<CaptureAccount>,
    currentId: String?,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (accounts.isEmpty()) item { CaptureSummaryCard(null) }
    items(accounts, key = { "capture-account-${it.id}" }) { account ->
        CaptureAccountCard(account, account.id == currentId, onRename, onDelete)
        Spacer(Modifier.height(12.dp))
    }
    item {
        Text(stringResource(R.string.capture_chaldea_freshness),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CaptureAccountCard(
    account: CaptureAccount,
    current: Boolean,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val export = rememberChaldeaExport(account.id)
    CaptureSummaryCard(
        account.summary,
        account.displayName,
        current,
        server = account.server,
        accountId = account.id,
    ) {
        ChaldeaExportAction(null, export)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { rename = true }) { Text(stringResource(R.string.capture_account_rename)) }
            TextButton(onClick = { delete = true }) { Text(stringResource(R.string.capture_account_delete)) }
        }
    }
    if (rename) CaptureAccountRenameDialog(account, { rename = false }) {
        onRename(account.id, it)
        rename = false
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text(stringResource(R.string.capture_account_delete)) },
        text = { Text(stringResource(R.string.capture_account_delete_message, account.displayName)) },
        confirmButton = {
            TextButton(onClick = { onDelete(account.id); delete = false }) { Text(stringResource(R.string.capture_account_delete)) }
        },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun CaptureAccountRenameDialog(account: CaptureAccount, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.displayName) }
    val dialog = FgaDialog(initialValue = true)
    dialog.build(onDismiss = onDismiss) {
        title(stringResource(R.string.capture_account_rename))
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.capture_account_name)) },
            )
            Text(stringResource(R.string.capture_account_name_hint), style = MaterialTheme.typography.bodySmall)
        }
        buttons(onSubmit = { onRename(name) }, onCancel = onDismiss)
    }
}

@Composable
internal fun CaptureSummaryCard(
    summary: AccountSummary?,
    displayName: String? = null,
    current: Boolean = false,
    server: String? = null,
    accountId: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (summary == null) {
                Text(stringResource(R.string.capture_no_snapshot))
                return@Column
            }
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            fun time(seconds: Long) = format.format(Date(seconds * 1000))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${displayName ?: summary.masterName}  Lv.${summary.level.grouped()}",
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                )
                server?.takeIf { it.isNotBlank() }?.let { code ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .semantics(mergeDescendants = true) {}
                            .testTag(if (accountId != null) "capture-account-server:$accountId" else "capture-account-server"),
                    ) {
                        Text(
                            code,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (current) Text(stringResource(R.string.capture_account_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.capture_snapshot_time, time(summary.snapshotTime)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.capture_last_update, time(summary.lastUpdateTime)),
                style = MaterialTheme.typography.bodySmall,
            )
            actions()
        }
    }
}
