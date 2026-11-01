package io.github.fate_grand_automata.ui.battle_config_list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import io.github.fate_grand_automata.R

@Composable
internal fun FolderRow(
    name: String,
    count: Int,
    expanded: Boolean,
    selectedCount: Int,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(24.dp)
        )
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selectedCount > 0) {
            Text(
                stringResource(R.string.battle_config_folder_selected_count, selectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun MoveToFolderDialog(
    existingFolders: List<String>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FolderNameResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battle_config_folder_move)) },
        text = {
            Column {
                existingFolders.forEach { folder ->
                    DialogOption(folder) { onApply(folder) }
                }
                if (creating) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            result = null
                        },
                        label = { Text(stringResource(R.string.battle_config_folder_name)) },
                        singleLine = true,
                        isError = result is FolderNameResult.Blank || result is FolderNameResult.TooLong,
                        supportingText = {
                            when (result) {
                                FolderNameResult.Blank ->
                                    Text(stringResource(R.string.battle_config_folder_error_blank))
                                FolderNameResult.TooLong ->
                                    Text(stringResource(R.string.battle_config_folder_error_too_long))
                                else -> {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    DialogOption(stringResource(R.string.battle_config_folder_new)) { creating = true }
                }
                DialogOption(stringResource(R.string.battle_config_folder_remove)) { onApply("") }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    val checked = ConfigFolders.normalizeName(text, existingFolders)
                    if (checked is FolderNameResult.Valid) onApply(checked.name) else result = checked
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun DialogOption(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}
