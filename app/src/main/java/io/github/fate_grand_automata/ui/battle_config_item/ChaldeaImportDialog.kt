package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.util.chaldea.AtlasChaldeaMetadata
import io.github.fate_grand_automata.util.chaldea.ChaldeaCommandConverter
import io.github.fate_grand_automata.util.chaldea.ChaldeaImportError
import io.github.fate_grand_automata.util.chaldea.ChaldeaImportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ChaldeaImportDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val converter = remember(context) {
        ChaldeaCommandConverter(AtlasChaldeaMetadata(File(context.cacheDir, "chaldea-metadata-v1")))
    }
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ChaldeaImportError?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val dismiss = { job?.cancel(); onDismiss() }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.chaldea_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text(stringResource(R.string.chaldea_import_url)) },
                    enabled = !busy,
                    isError = error != null,
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.chaldea_import_replace_warning), style = MaterialTheme.typography.bodySmall)
                if (busy) Text(stringResource(R.string.chaldea_import_loading))
                error?.let { reason ->
                    val message = when (reason) {
                        ChaldeaImportError.LINK -> R.string.chaldea_import_error_link
                        ChaldeaImportError.DATA -> R.string.chaldea_import_error_data
                        ChaldeaImportError.NO_ACTIONS -> R.string.chaldea_import_error_actions
                        ChaldeaImportError.NETWORK -> R.string.chaldea_import_error_network
                        ChaldeaImportError.METADATA -> R.string.chaldea_import_error_metadata
                        ChaldeaImportError.UNSUPPORTED -> R.string.chaldea_import_error_unsupported
                    }
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = submit@{
                    if (busy) return@submit
                    busy = true
                    error = null
                    val submittedUrl = url
                    job = scope.launch {
                        try {
                            val command = withContext(Dispatchers.Default) { converter.convert(submittedUrl) }
                            ensureActive()
                            onImported(command)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ChaldeaImportException) {
                            error = e.error
                        } catch (_: Exception) {
                            error = ChaldeaImportError.DATA
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(stringResource(R.string.chaldea_import_apply)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
