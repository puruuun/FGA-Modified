package io.github.fate_grand_automata.ui.more

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.captureAccountCards
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureController
import io.github.fate_grand_automata.capture.CaptureEnableAction
import io.github.fate_grand_automata.capture.CaptureStopReason
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.CertStore
import io.github.fate_grand_automata.capture.ChaldeaExportStore
import io.github.fate_grand_automata.capture.jpGameInstalled
import io.github.fate_grand_automata.capture.JpRootPreference
import io.github.fate_grand_automata.capture.JpRootSettings
import io.github.fate_grand_automata.capture.JpRootState
import io.github.fate_grand_automata.capture.rememberCaptureAgreement
import io.github.fate_grand_automata.capture.RootCaInjector
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.remember
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GainsTrackerUiState(
    val enabled: Boolean = false,
    val stopWithService: Boolean = true,
    val certExists: Boolean = false,
    val certInstalled: Boolean = false,
    val running: Boolean = false,
    val tlsFailures: Int = 0,
    val stopReason: CaptureStopReason? = null,
    val accounts: List<CaptureAccount> = emptyList(),
    val currentId: String? = null,
)

@HiltViewModel
class GainsTrackerViewModel @Inject constructor(
    app: Application,
    val prefsCore: PrefsCore,
) : ViewModel() {
    private val context = app.applicationContext
    val jpSettings = JpRootSettings(RootCaInjector(context), prefsCore.captureJpRoot.get()) {
        prefsCore.captureJpRoot.set(it)
    }

    fun setJpRoot(enabled: Boolean) { viewModelScope.launch { jpSettings.setEnabled(enabled) } }
    fun refreshJpRoot() { viewModelScope.launch { jpSettings.refresh() } }
    private val cert = MutableStateFlow(false to false)
    private val clock = MutableStateFlow(System.currentTimeMillis() / 1000)

    init {
        CaptureTracker.init(context)
        refreshCertificate()
    }

    val state: StateFlow<GainsTrackerUiState> = combine(
        prefsCore.captureTrackerEnabled.asFlow(),
        prefsCore.captureStopWithService.asFlow(),
        cert,
        combine(CaptureTracker.running, CaptureTracker.tlsFailures, CaptureTracker.stopReason) { a, b, c -> Triple(a, b, c) },
        combine(CaptureTracker.accounts, clock) { _, now -> now },
    ) { enabled, stopWithService, (exists, installed), (running, failures, reason), now ->
        GainsTrackerUiState(enabled, stopWithService, exists, installed, running, failures, reason,
            CaptureTracker.accountSummaries(now), CaptureTracker.currentId.value)
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

    /** Moves the summary clock so AP keeps recovering on screen while no response arrives. */
    fun tick() { clock.value = System.currentTimeMillis() / 1000 }

    fun stop() = CaptureController.stop()

    fun deleteData() {
        viewModelScope.launch {
            if (!ChaldeaExportStore.deleteTrackerData(context)) {
                Toast.makeText(context, R.string.capture_chaldea_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun renameAccount(id: String, name: String) = CaptureTracker.rename(id, name)

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            if (!ChaldeaExportStore.deleteAccount(context, id)) {
                Toast.makeText(context, R.string.capture_chaldea_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun GainsTrackerScreen(vm: GainsTrackerViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val jpState by vm.jpSettings.state.collectAsStateWithLifecycle()
    var enabled by vm.prefsCore.captureTrackerEnabled.remember()
    val agreement = rememberCaptureAgreement(vm.prefsCore)
    var stopWithService by vm.prefsCore.captureStopWithService.remember()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK && enabled &&
            CaptureConsent.agreed(vm.prefsCore.captureConsentVersion.get())) CaptureController.start(context)
    }
    // Re-read CA presence after returning from system settings or another app.
    LifecycleResumeEffect(Unit) {
        vm.refreshCertificate()
        vm.refreshJpRoot()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            vm.tick()
        }
    }

    GainsTrackerContent(
        state = state,
        onEnabled = {
            when (CaptureConsent.enableAction(it, vm.prefsCore.captureConsentVersion.get())) {
                CaptureEnableAction.Disable -> { enabled = false; vm.stop() }
                CaptureEnableAction.Enable -> enabled = true
                CaptureEnableAction.RequestConsent -> agreement(false) { enabled = true }
            }
        },
        onReviewConsent = { agreement(true) {} },
        onStopWithService = { stopWithService = it },
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
            agreement(false) {
                val intent = CaptureController.consentIntent(context)
                if (intent == null) CaptureController.start(context) else consent.launch(intent)
            }
        },
        onStop = vm::stop,
        onDelete = vm::deleteData,
        onRenameAccount = vm::renameAccount,
        onDeleteAccount = vm::deleteAccount,
        jpState = jpState,
        jpInstalled = jpGameInstalled(context),
        onJpRoot = vm::setJpRoot,
    )
}

@Composable
fun GainsTrackerContent(
    state: GainsTrackerUiState,
    onEnabled: (Boolean) -> Unit,
    onStopWithService: (Boolean) -> Unit,
    onCreateCertificate: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onReviewConsent: () -> Unit = {},
    onRenameAccount: (String, String) -> Unit = { _, _ -> },
    onDeleteAccount: (String) -> Unit = {},
    jpState: JpRootState = JpRootState(),
    jpInstalled: Boolean = false,
    onJpRoot: (Boolean) -> Unit = {},
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
                        onClick = { onEnabled(!state.enabled) },
                    ) {
                        androidx.compose.material3.Switch(checked = state.enabled, onCheckedChange = onEnabled)
                    }
                }
                item {
                    Preference(title = stringResource(R.string.capture_consent_review), onClick = onReviewConsent)
                }
                item { PreferenceGroupHeader(stringResource(R.string.capture_cert_header)) }
                item { JpRootPreference(jpState, jpInstalled, onJpRoot) }
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
                                        CaptureStopReason.Service -> R.string.capture_stop_service
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
                        title = stringResource(R.string.capture_stop_with_service),
                        summary = stringResource(R.string.capture_stop_with_service_summary),
                        onClick = { onStopWithService(!state.stopWithService) },
                    ) {
                        androidx.compose.material3.Switch(checked = state.stopWithService, onCheckedChange = onStopWithService)
                    }
                }
                item { PreferenceGroupHeader(stringResource(R.string.capture_account_header)) }
                captureAccountCards(state.accounts, state.currentId, onRenameAccount, onDeleteAccount)
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.capture_delete)) }
                    }
                }
            }
        }
    }
}
