package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.util.portraits.PortraitImportError

@Composable
fun ServantPortraitImportDialog(
    state: PortraitImportState,
    destinationName: String?,
    onNumber: (String) -> Unit,
    onStart: () -> Unit,
    onPickDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(stringResource(R.string.portrait_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.portrait_import_description))
                OutlinedTextField(
                    value = state.number,
                    onValueChange = onNumber,
                    label = { Text(stringResource(R.string.portrait_import_number)) },
                    placeholder = { Text("90") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (destinationName == null) {
                    OutlinedButton(onClick = onPickDirectory, enabled = !state.busy) {
                        Text(stringResource(R.string.p_choose_folder_action))
                    }
                } else {
                    Text(stringResource(R.string.portrait_import_destination, destinationName), style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    val progress = state.progress
                    Text(if (progress == null) stringResource(R.string.portrait_import_loading)
                    else stringResource(R.string.portrait_import_progress, progress.name, progress.completed, progress.total))
                }
                state.report?.let { report ->
                    Text(stringResource(R.string.portrait_import_result, report.name, report.saved, report.failed))
                    if (report.saved > 0) {
                        Text(stringResource(R.string.portrait_import_use, report.folder))
                    }
                    if (report.failed > 0) {
                        Text(stringResource(R.string.portrait_import_partial), color = MaterialTheme.colorScheme.error)
                    }
                }
                state.error?.let { error ->
                    Text(stringResource(when (error) {
                        PortraitImportError.Number -> R.string.portrait_import_error_number
                        PortraitImportError.NotFound -> R.string.portrait_import_error_not_found
                        PortraitImportError.Network -> R.string.portrait_import_error_network
                        PortraitImportError.Metadata -> R.string.portrait_import_error_metadata
                        PortraitImportError.Image -> R.string.portrait_import_error_image
                        PortraitImportError.Storage -> R.string.portrait_import_error_storage
                    }), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = state.canStart && destinationName != null, onClick = onStart) {
                Text(stringResource(if (state.report == null) R.string.portrait_import_start else R.string.portrait_import_retry))
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = onDismiss) { Text(stringResource(R.string.portrait_import_close)) }
        }
    )
}
