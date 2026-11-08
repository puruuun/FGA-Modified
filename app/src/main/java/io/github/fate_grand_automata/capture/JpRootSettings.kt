package io.github.fate_grand_automata.capture

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class JpCaStatus { Unknown, Installed, Absent, NoRoot, Failed, Removed }
data class JpRootState(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val status: JpCaStatus = JpCaStatus.Unknown,
    val reason: String = "",
    val restartGame: Boolean = false,
)

fun jpRootVisible(api: Int, installed: Boolean) = api >= 29 && installed
fun jpGameInstalled(context: Context) = runCatching {
    context.packageManager.getPackageInfo(CaptureVpnService.JP_GAME_PACKAGE, 0)
}.isSuccess

/** State shared by the real settings UI and fake-backed Robolectric tests. All installer work is IO. */
class JpRootSettings(
    private val installer: JpCaInstaller,
    enabled: Boolean,
    private val save: (Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(JpRootState(enabled))
    val state = mutableState.asStateFlow()
    private var revision = 0

    suspend fun refresh() {
        if (!state.value.enabled || state.value.busy) return
        val observedRevision = revision
        val present = withContext(Dispatchers.IO) { installer.isPresent() }
        if (observedRevision == revision && state.value.enabled && !state.value.busy) mutableState.value = state.value.copy(
            status = if (present) JpCaStatus.Installed else JpCaStatus.Absent)
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (state.value.busy) return
        revision++
        mutableState.value = state.value.copy(busy = true)
        try {
            if (!enabled) {
                val removed = withContext(Dispatchers.IO) { installer.remove() }
                save(false)
                mutableState.value = JpRootState(status = JpCaStatus.Removed,
                    reason = if (removed) "" else "Unmount incomplete")
                return
            }
            if (!withContext(Dispatchers.IO) { installer.hasRoot() }) {
                save(false)
                mutableState.value = JpRootState(status = JpCaStatus.NoRoot)
                return
            }
            val (result, restartGame) = withContext(Dispatchers.IO) {
                installer.ensureCertificate()
                val result = installer.inject()
                result to (result == RootCaResult.Injected && installer.isGameRunning())
            }
            val rootConfirmed = result != RootCaResult.RootMissing && result != RootCaResult.Denied
            save(rootConfirmed)
            mutableState.value = JpRootState(enabled = rootConfirmed, status = when (result) {
                RootCaResult.Injected, RootCaResult.AlreadyPresent -> JpCaStatus.Installed
                RootCaResult.RootMissing, RootCaResult.Denied -> JpCaStatus.NoRoot
                is RootCaResult.Failed -> JpCaStatus.Failed
            }, reason = (result as? RootCaResult.Failed)?.reason.orEmpty(), restartGame = restartGame)
        } catch (_: Exception) {
            if (!enabled) save(false)
            mutableState.value = state.value.copy(enabled = if (enabled) state.value.enabled else false,
                busy = false, status = if (enabled) JpCaStatus.Failed else JpCaStatus.Removed,
                reason = "Certificate or shell error")
        }
    }
}

@Composable
fun JpRootPreference(state: JpRootState, installed: Boolean, onEnabled: (Boolean) -> Unit) {
    if (!jpRootVisible(Build.VERSION.SDK_INT, installed)) return
    Column {
        PreferenceGroupHeader(stringResource(R.string.capture_jp_root))
        Preference(title = stringResource(R.string.capture_jp_root_summary), enabled = !state.busy,
            onClick = { onEnabled(!state.enabled) }) {
            if (state.busy) CircularProgressIndicator()
            else Switch(checked = state.enabled, onCheckedChange = onEnabled, modifier = Modifier.testTag("jp-root-switch"))
        }
        if (state.status != JpCaStatus.Unknown) Text(
            when (state.status) {
                JpCaStatus.Installed -> stringResource(R.string.capture_jp_installed)
                JpCaStatus.NoRoot -> stringResource(R.string.capture_jp_no_root)
                JpCaStatus.Failed -> stringResource(R.string.capture_jp_failed, state.reason)
                JpCaStatus.Removed -> stringResource(R.string.capture_jp_reboot)
                else -> stringResource(R.string.capture_jp_absent)
            }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (state.restartGame) Text(stringResource(R.string.capture_jp_restart),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
