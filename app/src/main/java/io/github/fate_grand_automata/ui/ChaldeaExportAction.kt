package io.github.fate_grand_automata.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One export flow shared by the tracker and log screens. */
@Composable
fun rememberChaldeaExport(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.capture_chaldea_create)) },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    return {
        if (!busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            busy = true
            scope.launch {
                try {
                    when (val result = ChaldeaExportStore.export(context)) {
                        ChaldeaExportStore.Result.NoSnapshot -> message = context.getString(R.string.capture_no_snapshot)
                        ChaldeaExportStore.Result.Failed -> message = context.getString(R.string.capture_chaldea_failed)
                        is ChaldeaExportStore.Result.Written -> {
                            val guide = context.getString(R.string.capture_chaldea_guide, result.name)
                            val opened = listOf("cc.narumi.chaldea", "cc.narumi.chaldea.fdroid").any { pkg ->
                                runCatching {
                                    val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
                                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    true
                                }.getOrDefault(false)
                            }
                            message = if (opened) guide else context.getString(R.string.capture_chaldea_not_installed, result.name) + "\n\n" + guide
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                } finally { busy = false }
            }
        }
    }
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
