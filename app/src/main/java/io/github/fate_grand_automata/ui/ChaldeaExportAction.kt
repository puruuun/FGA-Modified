package io.github.fate_grand_automata.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.ChaldeaExportStore
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** One export flow shared by the tracker and log screens. */
@Composable
fun rememberChaldeaExport(userId: String? = null): () -> Unit {
    val export = rememberChaldeaAccountExport()
    return { export(userId) }
}

/** Allows a picker to supply a stable account id at the moment export is requested. */
@Composable
fun rememberChaldeaAccountExport(): (String?) -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return {}
    val context = LocalContext.current
    return rememberChaldeaAccountExportAction(
        picked = { uri, request -> ChaldeaExportStore.picked(context, uri, request) },
    ) { userId -> ChaldeaExportStore.export(context, userId) }
}

@Composable
internal fun rememberChaldeaAccountExportAction(
    picked: (android.net.Uri, ChaldeaExportStore.Result.NeedsSaveDialog) -> ChaldeaExportStore.Result = { _, _ -> ChaldeaExportStore.Result.Failed },
    export: suspend (String?) -> ChaldeaExportStore.Result,
): (String?) -> Unit {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ChaldeaExportStore.Result?>(null) }
    var request by remember { mutableStateOf<ChaldeaExportStore.Result.NeedsSaveDialog?>(null) }
    // Last layer: the capture folder was unusable, so the user picks where the file goes.
    val saveDialog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = request
        request = null
        if (pending != null && uri != null) {
            scope.launch { result = withContext(Dispatchers.IO) { picked(uri, pending) } }
        }
    }
    result?.let { ChaldeaExportResultDialog(it) { result = null } }
    return { userId ->
        if (!busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            busy = true
            scope.launch {
                try {
                    val r = export(userId)
                    if (r is ChaldeaExportStore.Result.NeedsSaveDialog) {
                        request = r
                        saveDialog.launch(r.name)
                    } else result = r
                } finally { busy = false }
            }
        }
    }
}

@Composable
internal fun ChaldeaExportResultDialog(result: ChaldeaExportStore.Result, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val launchIntents = remember(result, context) {
        listOf("cc.narumi.chaldea", "cc.narumi.chaldea.fdroid").mapNotNull { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)
        }
    }
    val message = when (result) {
        ChaldeaExportStore.Result.NoSnapshot -> stringResource(R.string.capture_no_snapshot)
        ChaldeaExportStore.Result.Failed, is ChaldeaExportStore.Result.NeedsSaveDialog -> stringResource(R.string.capture_chaldea_failed)
        is ChaldeaExportStore.Result.Written -> buildString {
            append(stringResource(R.string.capture_chaldea_saved))
            append("\n\n")
            append(stringResource(R.string.capture_chaldea_guide, result.path, result.name))
            if (launchIntents.isEmpty()) {
                append("\n\n")
                append(stringResource(R.string.capture_chaldea_not_installed, result.path))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.capture_chaldea_create)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            if (result is ChaldeaExportStore.Result.Written && launchIntents.isNotEmpty()) {
                TextButton(onClick = {
                    launchIntents.any { intent ->
                        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.capture_chaldea_open)) }
            }
        },
    )
}

@Composable
fun ChaldeaExportAction(lastUpdateTime: Long?, onExport: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onExport) { Text(stringResource(R.string.capture_chaldea_create)) }
        lastUpdateTime?.let {
            Text(
                stringResource(R.string.capture_last_update, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it * 1000))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
