package io.github.fate_grand_automata.ui.battle_config_item

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.util.chaldea.*
import io.github.fate_grand_automata.util.stringRes
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChaldeaImportDialog(supportPrefs: SupportPrefsCore, onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val context = LocalContext.current
    val supportModel: ChaldeaSupportViewModel = hiltViewModel()
    val converter = remember(context) {
        ChaldeaCommandConverter(AtlasChaldeaMetadata(File(context.cacheDir, "chaldea-metadata-v1")))
    }
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var ignoreEnemyTargeting by rememberSaveable { mutableStateOf(false) }
    var includeSupport by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ChaldeaSupportPlan?>(null) }
    var preparedCommand by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ChaldeaImportError?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val dismiss = { if (!confirming) { job?.cancel(); onDismiss() } }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.chaldea_import_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val plan = preview
                if (plan != null) {
                    Text(stringResource(R.string.chaldea_support_preview))
                    listOfNotNull(plan.servant, plan.ce).forEach { image ->
                        val source = when (image.origin) {
                            SupportImageOrigin.Saved -> R.string.chaldea_support_saved
                            SupportImageOrigin.Builtin -> R.string.chaldea_support_builtin
                            SupportImageOrigin.Download -> R.string.chaldea_support_download
                        }
                        Text("${image.entry.sourceRegion?.let { "$it." }.orEmpty()}No.${image.entry.number} ${image.entry.name}\n" +
                            stringResource(source) + ": " + (image.folder ?: image.entry.folderName))
                    }
                    plan.settings?.let { settings ->
                        Text(stringResource(R.string.chaldea_support_changes,
                            if (settings.mlb) "✓" else "x", settings.npLevel,
                            settings.skills.joinToString("/") { if (it) "10" else "x" },
                            settings.appends.joinToString("/") { if (it) "10" else "x" },
                            settings.supportClass?.let { stringResource(it.stringRes) } ?: "—"))
                    }
                    if (plan.settings != null && plan.ce == null && SupportImportWarning.CEImage !in plan.warnings) {
                        Text(stringResource(R.string.chaldea_support_no_ce))
                    }
                    plan.warnings.forEach { Text(stringResource(it.messageRes)) }
                } else {
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
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = ignoreEnemyTargeting,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { ignoreEnemyTargeting = it; error = null }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = ignoreEnemyTargeting, onCheckedChange = null, enabled = !busy)
                        Text(stringResource(R.string.chaldea_import_ignore_enemy_targeting), modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = includeSupport, enabled = !busy, role = Role.Checkbox,
                            onValueChange = { includeSupport = it }
                        ), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeSupport, onCheckedChange = null, enabled = !busy)
                        Text(stringResource(R.string.chaldea_support_include), modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.chaldea_import_replace_warning), style = MaterialTheme.typography.bodySmall)
                }
                if (busy) Text(stringResource(R.string.chaldea_import_loading))
                error?.let { reason ->
                    val message = when (reason) {
                        ChaldeaImportError.LINK, ChaldeaImportError.DATA,
                        ChaldeaImportError.TARGETING -> R.string.chaldea_import_error_data
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
                    val submittedIgnoreTargeting = ignoreEnemyTargeting
                    val submittedPlan = preview
                    confirming = submittedPlan != null
                    job = scope.launch {
                        try {
                            if (submittedPlan != null) {
                                val result = supportModel.supportImport.confirm(submittedPlan)
                                ensureActive()
                                submittedPlan.settings?.apply(supportPrefs, result.servant, result.ce,
                                    SupportImportWarning.CEImage in result.warnings)
                                if (result.warnings.isNotEmpty()) Toast.makeText(context,
                                    result.warnings.joinToString("\n") { context.getString(it.messageRes) }, Toast.LENGTH_LONG).show()
                                onImported(preparedCommand)
                            } else {
                                val command = withContext(Dispatchers.Default) {
                                    converter.convert(submittedUrl, ignoreEnemyTargeting = submittedIgnoreTargeting)
                                }
                                ensureActive()
                                if (includeSupport) {
                                    val plan = supportModel.supportImport.prepare(ChaldeaShare.decode(submittedUrl),
                                        context.resources.configuration.locales[0]?.toLanguageTag() ?: Locale.getDefault().toLanguageTag())
                                    ensureActive()
                                    preparedCommand = command
                                    preview = plan
                                } else onImported(command)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ChaldeaImportException) {
                            error = e.error
                        } catch (_: Exception) {
                            error = ChaldeaImportError.DATA
                        } finally {
                            busy = false
                            confirming = false
                        }
                    }
                }
            ) { Text(stringResource(if (preview != null) R.string.chaldea_support_confirm else R.string.chaldea_import_apply)) }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !confirming) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private val SupportImportWarning.messageRes get() = when (this) {
    SupportImportWarning.FriendCount -> R.string.chaldea_support_friend_count
    SupportImportWarning.Metadata -> R.string.chaldea_support_metadata_failed
    SupportImportWarning.ServantImage -> R.string.chaldea_support_servant_failed
    SupportImportWarning.CEImage -> R.string.chaldea_support_ce_failed
}
