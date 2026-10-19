package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.util.portraits.*

@Composable
fun ServantPortraitImportDialog(
    state: PortraitImportState,
    destinationName: String?,
    onNumber: (String) -> Unit,
    onKind: (SupportCatalogKind) -> Unit,
    onSelect: (SupportCatalogEntry) -> Unit,
    onSearch: () -> Unit,
    onStart: () -> Unit,
    onPickDirectory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(stringResource(R.string.portrait_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportCatalogKind.entries.forEach { kind ->
                        FilterChip(selected = state.kind == kind, onClick = { onKind(kind) }, enabled = !state.busy,
                            label = { Text(stringResource(if (kind == SupportCatalogKind.CE) R.string.portrait_import_ce else R.string.portrait_import_servant)) })
                    }
                }
                OutlinedTextField(value = state.query, onValueChange = onNumber,
                    label = { Text(stringResource(R.string.portrait_import_number)) }, singleLine = true, enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (state.canSearch) onSearch() }), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onSearch, enabled = state.canSearch) { Text(stringResource(R.string.portrait_import_search)) }
                if (state.searched) {
                    Text(stringResource(if (state.candidates.isEmpty()) R.string.portrait_import_no_results else R.string.portrait_import_candidates, state.candidates.size))
                    if (state.candidates.isNotEmpty()) {
                        LazyColumn(Modifier.width(320.dp).height(240.dp)) {
                            items(state.candidates, key = { it.id }) { entry ->
                                Row(Modifier.fillMaxWidth().selectable(state.selected?.id == entry.id,
                                    enabled = !state.busy, role = Role.RadioButton, onClick = { onSelect(entry) }).padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = state.selected?.id == entry.id, onClick = null, enabled = !state.busy)
                                    AsyncImage(model = entry.face, contentDescription = null, modifier = Modifier.size(44.dp).padding(end = 6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("No.${entry.number} ${entry.name}")
                                        Text("${if (entry.kind == SupportCatalogKind.Servant) portraitClass(entry.className) + " · " else ""}★${entry.rarity}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                if (destinationName == null) {
                    OutlinedButton(onClick = onPickDirectory, enabled = !state.busy) { Text(stringResource(R.string.p_choose_folder_action)) }
                } else Text(stringResource(R.string.portrait_import_destination, destinationName,
                    if (state.kind == SupportCatalogKind.CE) "ce" else "servant"), style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    val progress = state.progress
                    Text(if (progress == null) stringResource(R.string.portrait_import_loading)
                    else stringResource(R.string.portrait_import_progress, progress.name, progress.completed, progress.total))
                }
                state.report?.let { report ->
                    Text(stringResource(R.string.portrait_import_result, report.name, report.saved, report.failed))
                    if (report.saved > 0) Text(stringResource(if (report.kind == SupportCatalogKind.CE) R.string.portrait_import_use_ce else R.string.portrait_import_use, report.folder))
                    if (report.failed > 0) Text(stringResource(R.string.portrait_import_partial), color = MaterialTheme.colorScheme.error)
                }
                state.error?.let { error ->
                    Text(stringResource(when (error) {
                        PortraitImportError.Query -> R.string.portrait_import_error_query
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
        confirmButton = { TextButton(enabled = state.canStart && destinationName != null, onClick = onStart) {
            Text(stringResource(if (state.report == null) R.string.portrait_import_start else R.string.portrait_import_retry))
        } },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onDismiss) { Text(stringResource(R.string.portrait_import_close)) } }
    )
}

@Composable
private fun portraitClass(name: String): String {
    val resource = when (name) {
        "saber" -> R.string.p_support_class_saber
        "archer" -> R.string.p_support_class_archer
        "lancer" -> R.string.p_support_class_lancer
        "rider" -> R.string.p_support_class_rider
        "caster" -> R.string.p_support_class_caster
        "assassin" -> R.string.p_support_class_assassin
        "berserker" -> R.string.p_support_class_berserker
        "ruler" -> R.string.portrait_class_ruler
        "avenger" -> R.string.portrait_class_avenger
        "moonCancer" -> R.string.portrait_class_mooncancer
        "alterEgo" -> R.string.portrait_class_alterego
        "foreigner" -> R.string.portrait_class_foreigner
        "pretender" -> R.string.portrait_class_pretender
        "shielder" -> R.string.portrait_class_shielder
        else -> if (name.contains("beast", ignoreCase = true)) R.string.portrait_class_beast else null
    }
    return resource?.let { stringResource(it) } ?: name
}
