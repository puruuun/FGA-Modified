package io.github.fate_grand_automata.ui.more

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.capture.AdGuardAutomation
import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.captureAccountCards
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.CaptureController
import io.github.fate_grand_automata.capture.CaptureEnableAction
import io.github.fate_grand_automata.capture.CaptureProxySnapshot
import io.github.fate_grand_automata.capture.CaptureProxyState
import io.github.fate_grand_automata.capture.CaptureProxyStatus
import io.github.fate_grand_automata.capture.CaptureStopReason
import io.github.fate_grand_automata.capture.rootShutdownSettled
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.CaptureFileSaver
import io.github.fate_grand_automata.capture.CertificateSaveGuard
import io.github.fate_grand_automata.capture.CertStore
import io.github.fate_grand_automata.capture.SaveOutcome
import io.github.fate_grand_automata.capture.ChaldeaExportStore
import io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncManager
import io.github.fate_grand_automata.capture.jpGameInstalled
import io.github.fate_grand_automata.capture.JpRootPreference
import io.github.fate_grand_automata.capture.JpRootSettings
import io.github.fate_grand_automata.capture.JpRootState
import io.github.fate_grand_automata.capture.rememberCaptureAgreement
import io.github.fate_grand_automata.capture.RootCaInjector
import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.remember
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Real folder and file name of the last certificate save, for example `FGA/capture/fga-capture-ca.crt`. */
    val certSavedPath: String? = null,
    val certSaving: Boolean = false,
    val droppedCount: Int = 0,
    val lastDroppedPath: String? = null,
    val connectionMode: CaptureConnectionMode = CaptureConnectionMode.Vpn,
    val proxyPort: Int = CaptureConnectionMode.DEFAULT_PROXY_PORT,
    val proxyStatus: CaptureProxySnapshot = CaptureProxySnapshot(),
    val adGuardInstalled: Boolean = false,
    val adGuardAutoSwitch: Boolean = false,
    val adGuardAutomationPassword: String = "",
    val modeSwitching: Boolean = false,
    val rootChecking: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis(),
) {
    val adGuardAutoSwitchEffective: Boolean
        get() = adGuardInstalled &&
            AdGuardAutomation.isAutoSwitchEffective(adGuardAutoSwitch, adGuardAutomationPassword)
}

/** The system save dialog for the certificate; [pending] is committed only after the user saved it. */
class CertSaveRequest(val name: String, val bytes: ByteArray, val pending: CertStore.Pending?)

@HiltViewModel
class GainsTrackerViewModel @Inject constructor(
    app: Application,
    val prefsCore: PrefsCore,
) : ViewModel() {
    private val context = app.applicationContext
    @Volatile private var ownsCertificateSave = false
    private var certificateJob: kotlinx.coroutines.Job? = null

    @Synchronized
    private fun finishCertificateSave() {
        if (ownsCertificateSave) {
            ownsCertificateSave = false
            CertificateSaveGuard.finish()
        }
    }

    override fun onCleared() {
        saveRequest.value = null
        // IO may still be writing when the scope is cancelled. Keep the VPN guard until it returns.
        if (certificateJob?.isCompleted != false) finishCertificateSave()
        super.onCleared()
    }
    val jpSettings = JpRootSettings(RootCaInjector(context), prefsCore.captureJpRoot.get()) {
        prefsCore.captureJpRoot.set(it)
    }

    fun setJpRoot(enabled: Boolean) { viewModelScope.launch { jpSettings.setEnabled(enabled) } }
    fun refreshJpRoot() { viewModelScope.launch { jpSettings.refresh() } }
    private val cert = MutableStateFlow(false to false)
    private val savedPath = MutableStateFlow<String?>(null)
    val saveRequest = MutableStateFlow<CertSaveRequest?>(null)
    private val clock = MutableStateFlow(System.currentTimeMillis() / 1000)
    private val switchingMode = MutableStateFlow(false)
    private val checkingRoot = MutableStateFlow(false)

    init {
        CaptureTracker.init(context)
        refreshCertificate()
    }

    private data class VpnStatus(
        val running: Boolean,
        val tlsFailures: Int,
        val stopReason: CaptureStopReason?,
        val droppedCount: Int,
        val lastDroppedPath: String?,
    )

    private data class ProxyUiStatus(
        val mode: CaptureConnectionMode,
        val port: Int,
        val status: CaptureProxySnapshot,
        val switching: Boolean,
        val rootChecking: Boolean,
        val adGuardInstalled: Boolean,
        val adGuardAutoSwitch: Boolean,
        val adGuardPassword: String,
    )

    private val adGuardInstalled = MutableStateFlow(AdGuardAutomation.isInstalled(context))

    private val coreStatus = combine(
        prefsCore.captureTrackerEnabled.asFlow(),
        prefsCore.captureStopWithService.asFlow(),
        cert,
        combine(
            CaptureTracker.running,
            CaptureTracker.tlsFailures,
            CaptureTracker.stopReason,
            CaptureTracker.droppedCount,
            CaptureTracker.lastDroppedPath,
        ) { running, failures, reason, dropped, lastPath ->
            VpnStatus(running, failures, reason, dropped, lastPath)
        },
        combine(CaptureTracker.accounts, clock, savedPath, CertificateSaveGuard.saving) { _, now, path, busy -> Triple(now, path, busy) },
    ) { enabled, stopWithService, (exists, installed), vpn, (now, path, busy) ->
        GainsTrackerUiState(
            enabled = enabled,
            stopWithService = stopWithService,
            certExists = exists,
            certInstalled = installed,
            running = vpn.running,
            tlsFailures = vpn.tlsFailures,
            stopReason = vpn.stopReason,
            accounts = CaptureTracker.accountSummaries(now),
            currentId = CaptureTracker.currentId.value,
            certSavedPath = path,
            certSaving = busy,
            droppedCount = vpn.droppedCount,
            lastDroppedPath = vpn.lastDroppedPath,
            nowMillis = now * 1000L,
        )
    }

    val state: StateFlow<GainsTrackerUiState> = combine(
        coreStatus,
        combine(
            prefsCore.captureConnectionMode.asFlow(),
            prefsCore.captureProxyPort.asFlow(),
            CaptureProxyStatus.state,
            combine(switchingMode, checkingRoot) { switching, rootCheck -> switching to rootCheck },
            combine(
                adGuardInstalled,
                prefsCore.captureAdGuardAutoSwitch.asFlow(),
                prefsCore.captureAdGuardAutomationPassword.asFlow(),
            ) { installed, autoSwitch, password -> Triple(installed, autoSwitch, password) },
        ) { mode, port, status, (switching, rootCheck), (installed, autoSwitch, password) ->
            ProxyUiStatus(mode, port, status, switching, rootCheck, installed, autoSwitch, password)
        },
    ) { base, proxyUi ->
        base.copy(
            connectionMode = proxyUi.mode,
            proxyPort = proxyUi.port,
            proxyStatus = proxyUi.status,
            adGuardInstalled = proxyUi.adGuardInstalled,
            adGuardAutoSwitch = proxyUi.adGuardAutoSwitch,
            adGuardAutomationPassword = proxyUi.adGuardPassword,
            modeSwitching = proxyUi.switching,
            rootChecking = proxyUi.rootChecking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GainsTrackerUiState())

    fun refreshAdGuardInstalled() {
        adGuardInstalled.value = AdGuardAutomation.isInstalled(context)
    }

    fun setAdGuardAutoSwitch(enabled: Boolean) {
        prefsCore.captureAdGuardAutoSwitch.set(enabled)
    }

    fun setAdGuardAutomationPassword(password: String) {
        prefsCore.captureAdGuardAutomationPassword.set(password)
    }

    fun refreshCertificate() {
        viewModelScope.launch {
            cert.value = withContext(Dispatchers.IO) { CertStore.exists(context) to CertStore.isInstalled(context) }
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    private fun report(outcome: SaveOutcome?, pending: CertStore.Pending?) {
        when (outcome) {
            is SaveOutcome.Saved -> {
                savedPath.value = outcome.path
                toast(context.getString(R.string.capture_cert_exported, outcome.path))
            }
            is SaveOutcome.NeedsDialog -> saveRequest.value = CertSaveRequest(outcome.name, outcome.bytes, pending)
            null -> toast(context.getString(R.string.capture_cert_failed))
        }
        refreshCertificate()
    }

    /** Saves the existing CA again. The CA is not replaced. */
    fun exportCertificate() {
        if (!CertificateSaveGuard.begin(false, CaptureTracker.running.value)) return
        ownsCertificateSave = true
        certificateJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { runCatching { CertStore.export(context) }.getOrNull() }
                report(outcome, null)
            } finally { if (saveRequest.value == null) finishCertificateSave() }
        }.also { job -> job.invokeOnCompletion { if (saveRequest.value == null) finishCertificateSave() } }
    }

    /** Makes a new CA. It replaces the stored one only after the export succeeded. */
    fun createCertificate() {
        if (!CertificateSaveGuard.begin(true, CaptureTracker.running.value)) return
        ownsCertificateSave = true
        certificateJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { CertStore.createAndExport(context) }.getOrNull() }
                report(result?.second, result?.first)
            } finally { if (saveRequest.value == null) finishCertificateSave() }
        }.also { job -> job.invokeOnCompletion { if (saveRequest.value == null) finishCertificateSave() } }
    }

    /** The user chose a place in the system save dialog; a null [uri] means they cancelled. */
    fun certificatePicked(uri: android.net.Uri?) {
        val request = saveRequest.value ?: return
        saveRequest.value = null
        certificateJob = viewModelScope.launch {
            try {
                val saved = uri?.let { withContext(Dispatchers.IO) { CaptureFileSaver.writePicked(context, it, request.bytes) } }
                if (saved == null) {
                    if (uri != null) toast(context.getString(R.string.capture_cert_failed))
                    return@launch
                }
                val pending = request.pending
                val committed = pending == null || withContext(Dispatchers.IO) {
                    runCatching { CertStore.commit(context, pending) }.isSuccess
                }
                if (committed) {
                    savedPath.value = saved.path
                    toast(context.getString(R.string.capture_cert_exported, saved.path))
                } else toast(context.getString(R.string.capture_cert_failed))
                refreshCertificate()
            } finally { finishCertificateSave() }
        }.also { job -> job.invokeOnCompletion { finishCertificateSave() } }
    }

    /** Moves the summary clock so AP keeps recovering on screen while no response arrives. */
    fun tick() { clock.value = System.currentTimeMillis() / 1000 }

    fun stop() = CaptureController.stop(context = context)

    fun applyProxyPort(port: Int) {
        if (!CaptureConnectionMode.isValidProxyPort(port)) return
        val currentState = CaptureProxyStatus.state.value.state
        if (currentState is CaptureProxyState.Listening && currentState.port == port) {
            if (prefsCore.captureProxyPort.get() != port) {
                prefsCore.captureProxyPort.set(port)
            }
            return
        }
        prefsCore.captureProxyPort.set(port)
        CaptureController.applyProxyPort(context)
        toast(context.getString(R.string.capture_adguard_port_changed_drop))
    }

    private suspend fun stopCurrentTransport(): Boolean {
        if (CaptureController.isVpnActive() ||
            (prefsCore.captureConnectionMode.get() == CaptureConnectionMode.Vpn && CaptureTracker.running.value)
        ) {
            CaptureController.stopVpn(CaptureStopReason.User)
            val stopped = withTimeoutOrNull(5_000) {
                while (CaptureController.isVpnActive() || CaptureTracker.running.value) {
                    delay(50)
                }
                true
            } == true
            if (!stopped) return false
        }
        if (CaptureController.isProxyAlive() ||
            CaptureProxyStatus.state.value.state != CaptureProxyState.Idle ||
            CaptureProxyStatus.state.value.rootRulesActive
        ) {
            CaptureController.shutdownProxy(context)
            val gone = withTimeoutOrNull(5_000) {
                while ((CaptureController.isProxyAlive() ||
                    CaptureProxyStatus.state.value.state != CaptureProxyState.Idle ||
                    CaptureProxyStatus.state.value.rootRulesActive) &&
                    !CaptureProxyStatus.state.value.rootCleanupRetrying &&
                    // G2: rules gone and reading off is enough; the listener may keep draining in the background.
                    !rootShutdownSettled(CaptureProxyStatus.state.value)
                ) {
                    delay(50)
                }
                true
            } == true
            if (!gone) return false
        }
        return true
    }

    fun switchToAdGuard() {
        if (switchingMode.value) return
        viewModelScope.launch {
            switchingMode.value = true
            try {
                if (!stopCurrentTransport()) {
                    toast(context.getString(R.string.capture_start_failed))
                    return@launch
                }
                prefsCore.captureConnectionMode.set(CaptureConnectionMode.AdGuard)
                CaptureController.ensureProxy(context)
            } finally {
                switchingMode.value = false
            }
        }
    }

    fun switchToRoot() {
        if (switchingMode.value) return
        viewModelScope.launch {
            switchingMode.value = true
            checkingRoot.value = true
            try {
                val hasRoot = withContext(Dispatchers.IO) {
                    RootCaInjector(context).hasRoot(timeoutSeconds = 30)
                }
                checkingRoot.value = false
                if (!hasRoot) {
                    toast(context.getString(R.string.capture_root_unavailable_toast))
                    return@launch
                }
                if (!stopCurrentTransport()) {
                    toast(context.getString(R.string.capture_start_failed))
                    return@launch
                }
                prefsCore.captureConnectionMode.set(CaptureConnectionMode.Root)
                CaptureController.ensureProxy(context)
            } finally {
                checkingRoot.value = false
                switchingMode.value = false
            }
        }
    }

    fun leaveAdGuard(disableFeature: Boolean) {
        if (switchingMode.value) return
        viewModelScope.launch {
            switchingMode.value = true
            try {
                if (!stopCurrentTransport()) {
                    toast(context.getString(R.string.capture_start_failed))
                    return@launch
                }
                if (disableFeature) {
                    prefsCore.captureTrackerEnabled.set(false)
                } else {
                    prefsCore.captureConnectionMode.set(CaptureConnectionMode.Vpn)
                }
            } finally {
                switchingMode.value = false
            }
        }
    }

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
    val chaldeaUi by remember { chaldeaSyncUi(vm.prefsCore) }.collectAsStateWithLifecycle(ChaldeaSyncUi())
    // Re-read CA presence after returning from system settings or another app.
    LifecycleResumeEffect(Unit) {
        vm.refreshCertificate()
        vm.refreshJpRoot()
        vm.refreshAdGuardInstalled()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            vm.tick()
        }
    }

    val saveRequest by vm.saveRequest.collectAsStateWithLifecycle()
    val saveDialog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CertStore.EXPORT_MIME),
    ) { vm.certificatePicked(it) }
    // Last layer of the save: the capture folder was unusable, so the user picks the place.
    LaunchedEffect(saveRequest) { saveRequest?.let { saveDialog.launch(it.name) } }

    GainsTrackerContent(
        state = state,
        onEnabled = {
            when (CaptureConsent.enableAction(it, vm.prefsCore.captureConsentVersion.get())) {
                CaptureEnableAction.Disable -> {
                    if (state.connectionMode == CaptureConnectionMode.AdGuard ||
                        state.connectionMode == CaptureConnectionMode.Root
                    ) {
                        vm.leaveAdGuard(disableFeature = true)
                    } else {
                        enabled = false
                        vm.stop()
                    }
                }
                CaptureEnableAction.Enable -> {
                    enabled = true
                    CaptureController.ensureProxy(context)
                }
                CaptureEnableAction.RequestConsent -> agreement(false) {
                    enabled = true
                    CaptureController.ensureProxy(context)
                }
            }
        },
        onReviewConsent = { agreement(true) {} },
        onStopWithService = { stopWithService = it },
        onCreateCertificate = vm::createCertificate,
        onExportCertificate = vm::exportCertificate,
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
        onSelectMode = { mode ->
            when (mode) {
                CaptureConnectionMode.Vpn -> vm.leaveAdGuard(disableFeature = false)
                CaptureConnectionMode.AdGuard -> vm.switchToAdGuard()
                CaptureConnectionMode.Root -> vm.switchToRoot()
            }
        },
        onApplyProxyPort = vm::applyProxyPort,
        onConfirmDisableAdGuard = { vm.leaveAdGuard(disableFeature = true) },
        onAdGuardAutoSwitch = vm::setAdGuardAutoSwitch,
        onAdGuardAutomationPassword = vm::setAdGuardAutomationPassword,
        extraItems = {
            chaldeaSyncItems(
                ui = chaldeaUi,
                onEnabled = { on ->
                    vm.prefsCore.chaldeaSyncEnabled.set(on)
                    if (on) ChaldeaSyncManager.onUserTurnedOn()
                },
                onCloseMinutes = { vm.prefsCore.chaldeaSyncCloseMinutes.set(it) },
                onStopMinutes = { vm.prefsCore.chaldeaSyncStopMinutes.set(it) },
                syncNow = ChaldeaSyncManager::syncNow,
            )
        },
    )
}

/** Combines the switch, the root and Shizuku access and the scheduler status into what the section shows. */
private fun chaldeaSyncUi(prefs: PrefsCore): Flow<ChaldeaSyncUi> = combine(
    combine(
        prefs.chaldeaSyncEnabled.asFlow(),
        prefs.chaldeaSyncCloseMinutes.asFlow(),
        prefs.chaldeaSyncStopMinutes.asFlow(),
    ) { on, closeMinutes, stopMinutes -> Triple(on, closeMinutes, stopMinutes) },
    combine(prefs.captureTrackerEnabled.asFlow(), prefs.captureConsentVersion.asFlow()) { tracker, consent ->
        tracker && CaptureConsent.agreed(consent)
    },
    ChaldeaSyncManager.access,
    combine(ChaldeaSyncManager.status, ChaldeaSyncManager.serverResults) { status, results -> status to results },
    ChaldeaSyncManager.scriptRunning,
) { (on, closeMinutes, stopMinutes), logging, access, (status, results), script ->
    ChaldeaSyncUi(on, logging, closeMinutes, stopMinutes, access, status, script, results)
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
    onExportCertificate: () -> Unit = {},
    onRenameAccount: (String, String) -> Unit = { _, _ -> },
    onDeleteAccount: (String) -> Unit = {},
    jpState: JpRootState = JpRootState(),
    jpInstalled: Boolean = false,
    onJpRoot: (Boolean) -> Unit = {},
    onSelectMode: (CaptureConnectionMode) -> Unit = {},
    onApplyProxyPort: (Int) -> Unit = {},
    onConfirmDisableAdGuard: () -> Unit = {},
    onAdGuardAutoSwitch: (Boolean) -> Unit = {},
    onAdGuardAutomationPassword: (String) -> Unit = {},
    extraItems: LazyListScope.() -> Unit = {},
) {
    var confirmRecreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var pendingLeaveAdGuardAction by remember { mutableStateOf<LeaveAdGuardAction?>(null) }

    if (confirmRecreate) AlertDialog(
        onDismissRequest = { confirmRecreate = false },
        title = { Text(stringResource(R.string.capture_cert_recreate_title)) },
        text = { Text(stringResource(R.string.capture_cert_recreate_message)) },
        confirmButton = {
            TextButton(onClick = { confirmRecreate = false; onCreateCertificate() }, enabled = !state.running && !state.certSaving) {
                Text(stringResource(R.string.capture_cert_create_new))
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
    pendingLeaveAdGuardAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingLeaveAdGuardAction = null },
            title = { Text(stringResource(R.string.capture_adguard_leave_title)) },
            text = { Text(stringResource(R.string.capture_adguard_leave_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLeaveAdGuardAction = null
                        when (action) {
                            LeaveAdGuardAction.SwitchToVpn -> onSelectMode(CaptureConnectionMode.Vpn)
                            LeaveAdGuardAction.SwitchToRoot -> onSelectMode(CaptureConnectionMode.Root)
                            LeaveAdGuardAction.DisableFeature -> onConfirmDisableAdGuard()
                        }
                    },
                ) {
                    Text(stringResource(R.string.capture_adguard_leave_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLeaveAdGuardAction = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val handleEnabledChange: (Boolean) -> Unit = { targetEnabled ->
        if (!targetEnabled && state.connectionMode == CaptureConnectionMode.AdGuard) {
            if (state.adGuardAutoSwitchEffective) {
                onConfirmDisableAdGuard()
            } else {
                pendingLeaveAdGuardAction = LeaveAdGuardAction.DisableFeature
            }
        } else {
            onEnabled(targetEnabled)
        }
    }

    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.capture_title))
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                item {
                    Preference(
                        title = stringResource(R.string.capture_enable),
                        onClick = { handleEnabledChange(!state.enabled) },
                    ) {
                        androidx.compose.material3.Switch(
                            checked = state.enabled,
                            enabled = !state.modeSwitching,
                            onCheckedChange = handleEnabledChange,
                        )
                    }
                }
                item {
                    Preference(title = stringResource(R.string.capture_consent_review), onClick = onReviewConsent)
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
                        if (state.running) Text(stringResource(R.string.capture_cert_stop_first), style = MaterialTheme.typography.bodySmall)
                        state.certSavedPath?.let {
                            Text(
                                stringResource(R.string.capture_cert_exported, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.certExists) {
                                // An existing CA is saved again; a new one is a separate, confirmed action.
                                Button(onClick = onExportCertificate, enabled = !state.certSaving) { Text(stringResource(R.string.capture_cert_export)) }
                                OutlinedButton(onClick = { confirmRecreate = true }, enabled = !state.running && !state.certSaving) {
                                    Text(stringResource(R.string.capture_cert_create_new))
                                }
                            } else {
                                Button(onClick = onCreateCertificate, enabled = !state.running && !state.certSaving) { Text(stringResource(R.string.capture_cert_create)) }
                            }
                            OutlinedButton(onClick = { showGuide = true }) { Text(stringResource(R.string.capture_guide_button)) }
                        }
                    }
                }
                item { JpRootPreference(jpState, jpInstalled, onJpRoot) }
                item { PreferenceGroupHeader(stringResource(R.string.capture_vpn_header)) }
                item {
                    val context = LocalContext.current
                    val resources = LocalResources.current
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (CaptureController.isSupported) {
                            Text(
                                stringResource(R.string.capture_connection_mode_label),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = state.connectionMode == CaptureConnectionMode.Vpn,
                                    enabled = !state.modeSwitching,
                                    onClick = {
                                        if (state.connectionMode == CaptureConnectionMode.AdGuard) {
                                            if (state.adGuardAutoSwitchEffective) {
                                                onSelectMode(CaptureConnectionMode.Vpn)
                                            } else {
                                                pendingLeaveAdGuardAction = LeaveAdGuardAction.SwitchToVpn
                                            }
                                        } else if (state.connectionMode == CaptureConnectionMode.Root) {
                                            onSelectMode(CaptureConnectionMode.Vpn)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.capture_connection_mode_vpn)) },
                                )
                                FilterChip(
                                    selected = state.connectionMode == CaptureConnectionMode.AdGuard,
                                    enabled = !state.modeSwitching,
                                    onClick = {
                                        if (state.connectionMode != CaptureConnectionMode.AdGuard) {
                                            onSelectMode(CaptureConnectionMode.AdGuard)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.capture_connection_mode_adguard)) },
                                )
                                FilterChip(
                                    selected = state.connectionMode == CaptureConnectionMode.Root,
                                    enabled = !state.modeSwitching,
                                    onClick = {
                                        if (state.connectionMode == CaptureConnectionMode.AdGuard) {
                                            if (state.adGuardAutoSwitchEffective) {
                                                onSelectMode(CaptureConnectionMode.Root)
                                            } else {
                                                pendingLeaveAdGuardAction = LeaveAdGuardAction.SwitchToRoot
                                            }
                                        } else if (state.connectionMode != CaptureConnectionMode.Root) {
                                            onSelectMode(CaptureConnectionMode.Root)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.capture_connection_mode_root)) },
                                )
                            }
                            if (state.rootChecking) {
                                Text(
                                    stringResource(R.string.capture_root_checking),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        when (state.connectionMode) {
                            CaptureConnectionMode.AdGuard -> {
                                AdGuardProxyCard(
                                    state = state,
                                    onCopyAddress = { addr ->
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(ClipData.newPlainText("External proxy", addr))
                                        Toast.makeText(
                                            context,
                                            resources.getString(R.string.capture_adguard_copied, addr),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onApplyPort = onApplyProxyPort,
                                    onAdGuardAutoSwitch = onAdGuardAutoSwitch,
                                    onAdGuardAutomationPassword = onAdGuardAutomationPassword,
                                )
                            }

                            CaptureConnectionMode.Root -> {
                                RootCaptureCard(
                                    state = state,
                                    onApplyPort = onApplyProxyPort,
                                )
                            }

                            CaptureConnectionMode.Vpn -> {
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
                            }
                        }

                        if (state.connectionMode != CaptureConnectionMode.Root && state.proxyStatus.rootCleanupRetrying) {
                            Text(
                                text = stringResource(R.string.capture_root_cleanup_retry_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        val showTlsWarning = state.tlsFailures > 0 &&
                            (state.running || state.connectionMode == CaptureConnectionMode.AdGuard)
                        if (showTlsWarning) Text(
                            stringResource(
                                if (state.connectionMode == CaptureConnectionMode.AdGuard) {
                                    R.string.capture_adguard_tls_warning
                                } else {
                                    R.string.capture_tls_warning
                                },
                                state.tlsFailures,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.connectionMode == CaptureConnectionMode.AdGuard && state.proxyStatus.upstreamFailureCount > 0) {
                            Text(
                                stringResource(R.string.capture_adguard_upstream_warning, state.proxyStatus.upstreamFailureCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.running && state.droppedCount > 0 && state.lastDroppedPath != null) Text(
                            stringResource(R.string.capture_dropped_warning, state.droppedCount, state.lastDroppedPath),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        val isProxyMode = state.connectionMode == CaptureConnectionMode.AdGuard ||
                            state.connectionMode == CaptureConnectionMode.Root
                        if (state.running) {
                            OutlinedButton(onClick = onStop, enabled = !state.modeSwitching) {
                                Text(
                                    stringResource(
                                        if (isProxyMode) {
                                            R.string.capture_proxy_notification_action_stop
                                        } else {
                                            R.string.capture_vpn_stop
                                        }
                                    )
                                )
                            }
                        } else {
                            Button(
                                onClick = onStart,
                                enabled = state.enabled && state.certExists && !state.certSaving && !state.modeSwitching,
                            ) {
                                Text(
                                    stringResource(
                                        if (isProxyMode) {
                                            R.string.capture_proxy_notification_action_start
                                        } else {
                                            R.string.capture_vpn_start
                                        }
                                    )
                                )
                            }
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
                extraItems()
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

private enum class LeaveAdGuardAction {
    SwitchToVpn,
    SwitchToRoot,
    DisableFeature,
}

@Composable
private fun RootCaptureCard(
    state: GainsTrackerUiState,
    onApplyPort: (Int) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var portText by rememberSaveable(state.proxyPort) { mutableStateOf(state.proxyPort.toString()) }
    var portInvalid by remember { mutableStateOf(false) }

    fun submitPort() {
        val parsed = portText.trim().toIntOrNull()
        if (parsed == null || !CaptureConnectionMode.isValidProxyPort(parsed)) {
            portInvalid = true
            return
        }
        portInvalid = false
        keyboard?.hide()
        onApplyPort(parsed)
    }

    val snap = state.proxyStatus
    val displayPort = when (val st = snap.state) {
        is CaptureProxyState.Listening -> st.port
        is CaptureProxyState.PortError -> st.port
        CaptureProxyState.Idle -> state.proxyPort
    }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.capture_root_card_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (snap.rootRulesActive) {
                    stringResource(
                        R.string.capture_root_status_active,
                        snap.rootIpv4RuleCount,
                        snap.rootIpv6RuleCount,
                        displayPort,
                    )
                } else {
                    stringResource(R.string.capture_root_status_inactive)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    if (snap.rootWatchdogActive) {
                        R.string.capture_root_watchdog_active
                    } else {
                        R.string.capture_root_watchdog_inactive
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snap.rootIpv6Unsupported) {
                Text(
                    text = stringResource(R.string.capture_root_ipv6_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.capture_root_connections_since_apply,
                    snap.gameConnectionsSinceApply,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lastConnectedAt = snap.lastGameHostConnectedAt
            if (lastConnectedAt != null) {
                val minutes = ((state.nowMillis - lastConnectedAt).coerceAtLeast(0L) / 60_000L).toInt()
                Text(
                    text = stringResource(R.string.capture_adguard_last_connection, minutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.capture_root_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snap.unexpectedConnectionCount > 0) {
                Text(
                    text = stringResource(
                        R.string.capture_root_unexpected_connections,
                        snap.unexpectedConnectionCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snap.nonGameUpstreamFailureCount > 0) {
                Text(
                    text = stringResource(
                        R.string.capture_root_non_game_failures,
                        snap.nonGameUpstreamFailureCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snap.rootCleanupRetrying) {
                Text(
                    text = stringResource(R.string.capture_root_cleanup_retry_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            snap.rootLastError?.takeIf { it.isNotEmpty() && !snap.rootCleanupRetrying }?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snap.state is CaptureProxyState.PortError) {
                Text(
                    text = stringResource(R.string.capture_proxy_port_in_use, displayPort),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        portInvalid = false
                    },
                    label = { Text(stringResource(R.string.capture_adguard_port_label)) },
                    isError = portInvalid,
                    supportingText = if (portInvalid) {
                        { Text(stringResource(R.string.capture_adguard_port_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitPort() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { submitPort() },
                    enabled = !state.modeSwitching,
                ) {
                    Text(stringResource(R.string.capture_adguard_port_apply))
                }
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.capture_root_adguard_vpn_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdGuardProxyCard(
    state: GainsTrackerUiState,
    onCopyAddress: (String) -> Unit,
    onApplyPort: (Int) -> Unit,
    onAdGuardAutoSwitch: (Boolean) -> Unit,
    onAdGuardAutomationPassword: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var portText by rememberSaveable(state.proxyPort) { mutableStateOf(state.proxyPort.toString()) }
    var portInvalid by remember { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    fun submitPort() {
        val parsed = portText.trim().toIntOrNull()
        if (parsed == null || !CaptureConnectionMode.isValidProxyPort(parsed)) {
            portInvalid = true
            return
        }
        portInvalid = false
        keyboard?.hide()
        onApplyPort(parsed)
    }

    val displayPort = when (val st = state.proxyStatus.state) {
        is CaptureProxyState.Listening -> st.port
        is CaptureProxyState.PortError -> st.port
        CaptureProxyState.Idle -> state.proxyPort
    }
    val address = "127.0.0.1:$displayPort"

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.capture_adguard_card_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.capture_adguard_address, address),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onCopyAddress(address) }) {
                    Text(stringResource(R.string.capture_adguard_copy))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it
                        portInvalid = false
                    },
                    label = { Text(stringResource(R.string.capture_adguard_port_label)) },
                    isError = portInvalid,
                    supportingText = if (portInvalid) {
                        { Text(stringResource(R.string.capture_adguard_port_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitPort() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { submitPort() },
                    enabled = !state.modeSwitching,
                ) {
                    Text(stringResource(R.string.capture_adguard_port_apply))
                }
            }
            val isPortError = state.proxyStatus.state is CaptureProxyState.PortError
            Text(
                text = when {
                    isPortError -> stringResource(R.string.capture_adguard_status_port_error, displayPort)
                    state.running -> stringResource(R.string.capture_adguard_status_collecting, displayPort)
                    else -> stringResource(R.string.capture_adguard_status_waiting, displayPort)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPortError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            val lastConnectedAt = state.proxyStatus.lastGameHostConnectedAt
            Text(
                text = if (lastConnectedAt != null) {
                    val minutes = ((state.nowMillis - lastConnectedAt).coerceAtLeast(0L) / 60_000L).toInt()
                    stringResource(R.string.capture_adguard_last_connection, minutes)
                } else {
                    stringResource(R.string.capture_adguard_no_connection)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val batteryContext = LocalContext.current
            var batteryExcluded by remember { mutableStateOf(isIgnoringBatteryOptimizations(batteryContext)) }
            LifecycleResumeEffect(Unit) {
                batteryExcluded = isIgnoringBatteryOptimizations(batteryContext)
                onPauseOrDispose {}
            }
            Text(
                text = stringResource(
                    if (batteryExcluded) R.string.capture_adguard_battery_excluded
                    else R.string.capture_adguard_battery_not_excluded
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (batteryExcluded) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            if (!batteryExcluded) {
                OutlinedButton(onClick = { requestBatteryExclusion(batteryContext) }) {
                    Text(stringResource(R.string.capture_adguard_battery_button))
                }
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.capture_proxy_common_requirements_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.capture_proxy_common_requirements, displayPort),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.capture_adguard_section_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.capture_adguard_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.adGuardInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.capture_adguard_auto_switch_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.capture_adguard_auto_switch_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.adGuardAutoSwitch,
                        onCheckedChange = onAdGuardAutoSwitch,
                    )
                }
                OutlinedTextField(
                    value = state.adGuardAutomationPassword,
                    onValueChange = onAdGuardAutomationPassword,
                    label = { Text(stringResource(R.string.capture_adguard_automation_password_label)) },
                    supportingText = {
                        Text(stringResource(R.string.capture_adguard_automation_password_help))
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.capture_adguard_automation_password_hide
                                    else R.string.capture_adguard_automation_password_show
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

private fun requestBatteryExclusion(context: android.content.Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
