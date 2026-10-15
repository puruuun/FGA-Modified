package io.github.fate_grand_automata.ui.more

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.util.migration.ImportCounts
import io.github.fate_grand_automata.util.migration.ImportPart
import io.github.fate_grand_automata.util.migration.ImportProblem
import io.github.fate_grand_automata.util.migration.ImportSelection

@Composable
fun OriginalFgaImportDialog(
    state: OriginalImportState,
    destinationName: String?,
    onDismiss: () -> Unit,
    onSelection: (ImportSelection) -> Unit,
    onPickSource: () -> Unit,
    onOverwrite: (Boolean) -> Unit,
    onImport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(stringResource(R.string.original_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val report = state.report
                if (report != null) {
                    if (state.selection.scripts) ImportCountSummary(ImportPart.Scripts, report.scripts)
                    if (state.selection.images) ImportCountSummary(ImportPart.Images, report.images)
                    Text(stringResource(R.string.original_import_skipped), style = MaterialTheme.typography.bodySmall)
                    report.issues.forEach { issue ->
                        val reason = stringResource(when (issue.problem) {
                            ImportProblem.Folder -> R.string.original_import_error_folder
                            ImportProblem.NoFiles -> R.string.original_import_error_empty
                            ImportProblem.InvalidScript -> R.string.original_import_error_script
                            ImportProblem.UnsupportedCommand -> R.string.original_import_error_command
                            ImportProblem.ReadWrite -> R.string.original_import_error_file
                            ImportProblem.OverlappingFolders -> R.string.original_import_error_overlap
                        })
                        Text(
                            "${stringResource(issue.part.label)}${issue.path.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}\n$reason",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (report.issueCount > report.issues.size) {
                        Text(stringResource(R.string.original_import_more_errors, report.issueCount - report.issues.size))
                    }
                } else {
                    Text(stringResource(R.string.original_import_instructions))
                    Column {
                        ImportSelection.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = state.selection == mode,
                                    enabled = !state.busy,
                                    role = Role.RadioButton,
                                    onClick = { onSelection(mode) }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = state.selection == mode, onClick = null, enabled = !state.busy)
                                Text(stringResource(when (mode) {
                                    ImportSelection.Scripts -> R.string.original_import_scripts_only
                                    ImportSelection.Images -> R.string.original_import_images_only
                                    ImportSelection.Both -> R.string.original_import_both
                                }))
                            }
                        }
                    }
                    ImportFolderButton(R.string.original_import_source_folder, state.source, !state.busy, onPickSource)
                    if (state.selection.images) {
                        Text(stringResource(R.string.original_import_image_hint), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (destinationName == null) stringResource(R.string.original_import_need_destination)
                            else stringResource(R.string.original_import_destination, destinationName),
                            color = if (destinationName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = state.overwriteImages,
                                enabled = !state.busy,
                                role = Role.Checkbox,
                                onValueChange = onOverwrite
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = state.overwriteImages, onCheckedChange = null, enabled = !state.busy)
                            Text(stringResource(R.string.original_import_overwrite), modifier = Modifier.weight(1f))
                        }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        val progress = state.progress
                        Text(if (progress == null) stringResource(R.string.original_import_loading)
                        else stringResource(
                            R.string.original_import_progress,
                            stringResource(progress.part.label), progress.completed, progress.total
                        ))
                    }
                    if (state.error) Text(stringResource(R.string.original_import_error_file), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (state.report != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            } else {
                TextButton(
                    enabled = state.canStart && (!state.selection.images || destinationName != null),
                    onClick = onImport
                ) { Text(stringResource(R.string.original_import_start)) }
            }
        },
        dismissButton = {
            if (state.report == null && !state.busy) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun ImportFolderButton(label: Int, uri: Uri?, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(label))
            uri?.lastPathSegment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ImportCountSummary(part: ImportPart, counts: ImportCounts) {
    Column {
        Text(stringResource(part.label), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.original_import_counts, counts.imported, counts.skipped, counts.failed))
    }
}

private val ImportPart.label get() = when (this) {
    ImportPart.Scripts -> R.string.original_import_scripts
    ImportPart.Images -> R.string.original_import_images
}
