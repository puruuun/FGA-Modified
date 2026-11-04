package io.github.fate_grand_automata.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore

/** Both first enable and manual starts pass through this gate. Cancel never runs the action. */
@Composable
fun rememberCaptureAgreement(prefs: PrefsCore): (Boolean, () -> Unit) -> Unit {
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    if (pending.value != null) CaptureConsentDialog(
        onCancel = { pending.value = null },
        onAgree = {
            prefs.captureConsentVersion.set(CaptureConsent.VERSION)
            val action = pending.value
            pending.value = null
            action?.invoke()
        },
    )
    return { showAgain, action ->
        if (showAgain || !CaptureConsent.agreed(prefs.captureConsentVersion.get())) pending.value = action
        else action()
    }
}

@Composable
fun CaptureConsentDialog(onCancel: () -> Unit, onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.capture_consent_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Notice(R.string.capture_consent_how_title, R.string.capture_consent_how)
                Notice(R.string.capture_consent_needs_title, R.string.capture_consent_needs)
                Text(stringResource(R.string.capture_consent_risks_title), style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(stringResource(R.string.capture_consent_risks))
                Text(stringResource(R.string.capture_consent_responsibility), style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(R.string.capture_consent_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun Notice(title: Int, body: Int) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(stringResource(body))
    }
}
