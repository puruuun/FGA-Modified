package io.github.fate_grand_automata.ui.more

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureController
import io.github.fate_grand_automata.capture.CaptureStopReason
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.CertStore
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class GainsTrackerUiState(
    val enabled: Boolean = false,
    val stopWithScript: Boolean = true,
    val certExists: Boolean = false,
    val certInstalled: Boolean = false,
    val running: Boolean = false,
    val tlsFailures: Int = 0,
    val stopReason: CaptureStopReason? = null,
    val summary: AccountSummary? = null,
)

@HiltViewModel
class GainsTrackerViewModel @Inject constructor(
    app: Application,
    val prefsCore: PrefsCore,
) : ViewModel() {
    private val context = app.applicationContext
    private val cert = MutableStateFlow(false to false)

    init {
        CaptureTracker.init(context)
        refreshCertificate()
    }

    val state: StateFlow<GainsTrackerUiState> = combine(
        prefsCore.captureTrackerEnabled.asFlow(),
        prefsCore.captureStopWithScript.asFlow(),
        cert,
        combine(CaptureTracker.running, CaptureTracker.tlsFailures, CaptureTracker.stopReason) { a, b, c -> Triple(a, b, c) },
        CaptureTracker.state,
    ) { enabled, stopWithScript, (exists, installed), (running, failures, reason), _ ->
        GainsTrackerUiState(enabled, stopWithScript, exists, installed, running, failures, reason, CaptureTracker.summary())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GainsTrackerUiState())

    fun refreshCertificate() {
        viewModelScope.launch {
            cert.value = withContext(Dispatchers.IO) { CertStore.exists(context) to CertStore.isInstalled(context) }
        }
    }

    fun createCertificate(): Boolean {
        val ok = runCatching { CertStore.create(context) }.isSuccess
        refreshCertificate()
        return ok
    }

    fun stop() = CaptureController.stop()

    fun deleteData() = CaptureTracker.delete()
}

@Composable
fun GainsTrackerScreen(vm: GainsTrackerViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var enabled by vm.prefsCore.captureTrackerEnabled.remember()
    var stopWithScript by vm.prefsCore.captureStopWithScript.remember()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) CaptureController.start(context)
    }
    // The certificate may have been installed while the settings app was in front.
    LaunchedEffect(Unit) { vm.refreshCertificate() }

    GainsTrackerContent(
        state = state,
        onEnabled = { enabled = it; if (!it) vm.stop() },
        onStopWithScript = { stopWithScript = it },
        onCreateCertificate = {
            val ok = vm.createCertificate()
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.capture_cert_exported, "Download/fga-capture-ca.cer")
                else context.getString(R.string.capture_cert_failed),
                Toast.LENGTH_LONG
            ).show()
        },
        onOpenSecuritySettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onStart = {
            val intent = CaptureController.consentIntent(context)
            if (intent == null) CaptureController.start(context) else consent.launch(intent)
        },
        onStop = vm::stop,
        onDelete = vm::deleteData,
    )
}

@Composable
fun GainsTrackerContent(
    state: GainsTrackerUiState,
    onEnabled: (Boolean) -> Unit,
    onStopWithScript: (Boolean) -> Unit,
    onCreateCertificate: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmRecreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    if (confirmRecreate) AlertDialog(
        onDismissRequest = { confirmRecreate = false },
        title = { Text(stringResource(R.string.capture_cert_recreate_title)) },
        text = { Text(stringResource(R.string.capture_cert_recreate_message)) },
        confirmButton = {
            TextButton(onClick = { confirmRecreate = false; onCreateCertificate() }) {
                Text(stringResource(R.string.capture_cert_create))
            }
        },
        dismissButton = { TextButton(onClick = { confirmRecreate = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.capture_delete)) },
        text = { Text(stringResource(R.string.capture_delete_message)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.capture_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (showGuide) AlertDialog(
        onDismissRequest = { showGuide = false },
        title = { Text(stringResource(R.string.capture_guide_title)) },
        text = { Text(stringResource(R.string.capture_guide_body)) },
        confirmButton = {
            TextButton(onClick = { showGuide = false; onOpenSecuritySettings() }) {
                Text(stringResource(R.string.capture_open_security))
            }
        },
        dismissButton = { TextButton(onClick = { showGuide = false }) { Text(stringResource(android.R.string.ok)) } },
    )

    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.capture_title))
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                item {
                    Preference(
                        title = stringResource(R.string.capture_enable),
                        summary = stringResource(R.string.capture_enable_summary),
                        onClick = { onEnabled(!state.enabled) },
                    ) {
                        androidx.compose.material3.Switch(checked = state.enabled, onCheckedChange = onEnabled)
                    }
                }
                item { PreferenceGroupHeader(stringResource(R.string.capture_cert_header)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                when {
                                    !state.certExists -> R.string.capture_cert_missing
                                    state.certInstalled -> R.string.capture_cert_installed
                                    else -> R.string.capture_cert_not_installed
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (state.certExists) confirmRecreate = true else onCreateCertificate() }) {
                                Text(stringResource(R.string.capture_cert_create))
                            }
                            OutlinedButton(onClick = { showGuide = true }) { Text(stringResource(R.string.capture_guide_button)) }
                        }
                    }
                }
                item { PreferenceGroupHeader(stringResource(R.string.capture_vpn_header)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (state.running) R.string.capture_vpn_running else R.string.capture_vpn_stopped),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!state.running) state.stopReason?.let {
                            Text(
                                stringResource(
                                    when (it) {
                                        CaptureStopReason.User -> R.string.capture_stop_user
                                        CaptureStopReason.Script -> R.string.capture_stop_script
                                        CaptureStopReason.Revoked -> R.string.capture_stop_revoked
                                        CaptureStopReason.Error -> R.string.capture_stop_error
                                        CaptureStopReason.Unsupported -> R.string.capture_stop_error
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.running && state.tlsFailures > 0) Text(
                            stringResource(R.string.capture_tls_warning, state.tlsFailures),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.running) OutlinedButton(onClick = onStop) { Text(stringResource(R.string.capture_vpn_stop)) }
                        else Button(onClick = onStart, enabled = state.enabled && state.certExists) {
                            Text(stringResource(R.string.capture_vpn_start))
                        }
                    }
                }
                item {
                    Preference(
                        title = stringResource(R.string.capture_stop_with_script),
                        summary = stringResource(R.string.capture_stop_with_script_summary),
                        onClick = { onStopWithScript(!state.stopWithScript) },
                    ) {
                        androidx.compose.material3.Switch(checked = state.stopWithScript, onCheckedChange = onStopWithScript)
                    }
                }
                item { PreferenceGroupHeader(stringResource(R.string.capture_account_header)) }
                item { SummaryCard(state.summary) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.capture_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: AccountSummary?) {
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
            Text(
                "${summary.masterName}  Lv.${summary.level}",
                style = MaterialTheme.typography.titleMedium,
            )
            val ap = buildString {
                append("${summary.ap} / ${summary.apMax}")
                if (summary.carryOverActPoint != 0L) append(" (+${summary.carryOverActPoint})")
            }
            Line(R.string.capture_ap, ap)
            Line(R.string.capture_quartz, "${summary.stone} (${summary.freeStone} + ${summary.chargeStone})")
            Line(R.string.capture_fragments, summary.fragments.toString())
            Line(R.string.capture_apples_gold, summary.goldenApples.toString())
            Line(R.string.capture_apples_silver, summary.silverApples.toString())
            Line(R.string.capture_apples_bronze, summary.bronzeApples.toString())
            Line(R.string.capture_apples_copper, summary.copperApples.toString())
            Line(R.string.capture_sapling, summary.bronzeSaplings.toString())
            Line(R.string.capture_tickets, summary.tickets.toString())
            Line(R.string.capture_mana_prism, summary.manaPrisms.toString())
            Line(R.string.capture_rare_prism, summary.rarePrisms.toString())
            Line(R.string.capture_qp, summary.qp.toString())
            Text(
                stringResource(R.string.capture_snapshot_time, time(summary.snapshotTime)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.capture_last_update, time(summary.lastUpdateTime)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Line(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
