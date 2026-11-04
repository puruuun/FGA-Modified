package io.github.fate_grand_automata.capture

import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.OnResume
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.grouped
import kotlinx.coroutines.delay
import java.util.Date

data class CaptureMainState(
    val showButton: Boolean = false,
    val running: Boolean = false,
    val summary: AccountSummary? = null,
    val now: Long = System.currentTimeMillis() / 1000,
)

class CaptureMainActions(
    val state: CaptureMainState,
    val toggle: () -> Unit,
    val withAgreement: (Boolean, () -> Unit) -> Unit,
)

@Composable
fun rememberCaptureMain(prefs: PrefsCore, openSettings: () -> Unit): CaptureMainActions {
    val context = LocalContext.current
    val enabled by prefs.captureTrackerEnabled.remember()
    val running by CaptureTracker.running.collectAsStateWithLifecycle()
    val accounts by CaptureTracker.accounts.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val agreement = rememberCaptureAgreement(prefs)
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var gameInstalled by remember { mutableStateOf(false) }
    OnResume {
        gameInstalled = runCatching {
            context.packageManager.getPackageInfo(CaptureVpnService.GAME_PACKAGE, 0)
        }.isSuccess
    }
    LaunchedEffect(lifecycle) {
        CaptureTracker.init(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis() / 1000
                delay(60_000)
            }
        }
    }
    fun start() {
        if (!captureButtonVisible(prefs.captureTrackerEnabled.get(), Build.VERSION.SDK_INT, gameInstalled) ||
            !CaptureConsent.agreed(prefs.captureConsentVersion.get())) return
        runCatching {
            if (!CertStore.exists(context)) {
                Toast.makeText(context, R.string.capture_main_no_certificate, Toast.LENGTH_SHORT).show()
                openSettings()
            } else CaptureController.start(context)
        }.onFailure { Toast.makeText(context, R.string.capture_start_failed, Toast.LENGTH_SHORT).show() }
    }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) start()
    }
    val toggle: () -> Unit = {
        if (CaptureTracker.running.value) CaptureController.stop()
        else agreement(false) {
            runCatching {
                if (!CertStore.exists(context)) {
                    Toast.makeText(context, R.string.capture_main_no_certificate, Toast.LENGTH_SHORT).show()
                    openSettings()
                } else {
                    val intent = CaptureController.consentIntent(context)
                    if (intent == null) start() else vpnConsent.launch(intent)
                }
            }.onFailure { Toast.makeText(context, R.string.capture_start_failed, Toast.LENGTH_SHORT).show() }
        }
    }
    return CaptureMainActions(
        CaptureMainState(
            showButton = captureButtonVisible(enabled, Build.VERSION.SDK_INT, gameInstalled),
            running = running,
            summary = if (enabled && accounts.isNotEmpty()) CaptureTracker.summary(now) else null,
            now = now,
        ),
        toggle,
        agreement,
    )
}

// Fixed pairs retain their contrast in both themes: white on blue and dark on yellow, both above 4.5:1.
val captureStartBackground = Color(0xFF1565C0)
val captureStartForeground = Color.White
val captureStopBackground = Color(0xFFFFD54F)
val captureStopForeground = Color(0xFF1B1B1B)

@Composable
fun CaptureMainButton(running: Boolean, onClick: () -> Unit) {
    val collecting = captureButtonState(running) == CaptureButtonState.Stop
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = if (collecting) captureStopBackground else captureStartBackground,
        contentColor = if (collecting) captureStopForeground else captureStartForeground,
    ) {
        Text(stringResource(if (collecting) R.string.capture_main_stop else R.string.capture_main_start))
    }
}

/** Natural measurement wraps the tracker above the service button, also with large fonts. */
@Composable
fun CaptureServiceButtons(
    state: CaptureMainState,
    onToggle: () -> Unit,
    serviceButton: @Composable () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.showButton) CaptureMainButton(state.running, onToggle)
        serviceButton()
    }
}

@Composable
fun CaptureProfileCard(summary: AccountSummary, now: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val fullAt = fullApAt(summary, now)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${summary.masterName}  Lv.${summary.level.grouped()}", style = MaterialTheme.typography.titleMedium)
            Text("AP ${summary.ap.grouped()} / ${summary.apMax.grouped()}")
            Text(
                if (fullAt == null) stringResource(R.string.capture_ap_full)
                else stringResource(
                    R.string.capture_ap_full_at,
                    android.text.format.DateFormat.getTimeFormat(context).format(Date(fullAt * 1000)),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
