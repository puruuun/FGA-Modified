package io.github.fate_grand_automata.ui.account

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.rememberChaldeaAccountExport
import io.github.fate_grand_automata.ui.log.*
import kotlinx.coroutines.delay

@Composable
fun AccountDashboardScreen(userId: String, openSettings: () -> Unit, openLogs: () -> Unit, openFriends: (String) -> Unit = {},
    vm: AccountDashboardViewModel = hiltViewModel()) {
    val version by remember(vm, userId) { vm.account(userId) }.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val battles by vm.battles.collectAsStateWithLifecycle()
    val outside by OutsideLogRepository.entries.collectAsStateWithLifecycle()
    val open by CaptureTracker.openOutsideEntries.collectAsStateWithLifecycle()
    val battle = remember(battles, userId) { battles.filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    val recentOutside = remember(outside, open, userId) { (outside + open).filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    val export = rememberChaldeaAccountExport()
    AccountDashboardContent(version.data, names, version.displayName, openSettings, openLogs, battle, recentOutside,
        userId = userId, exportAccount = export, friendCount = version.friendCount, openFriends = openFriends,
        server = version.server, loading = version.loading)
}

/**
 * @param initialSection a legacy block id (see [DashboardBlockId]). The navigation section that contains the block is
 * selected and the block is brought into view.
 */
@Composable
internal fun AccountDashboardContent(data: DashboardData?, names: GameNameMap, displayName: String?,
    openSettings: () -> Unit, openLogs: () -> Unit, battle: BattleLogEntry? = null, outside: OutsideLogEntry? = null,
    initialSection: Int = 0, userId: String = "", exportAccount: (String?) -> Unit = {}, friendCount: Int? = null,
    openFriends: (String) -> Unit = {}, server: String = "KR", loading: Boolean = false) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) } }
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Heading(stringResource(R.string.dashboard_title)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    IconButton(onClick = { exportAccount(userId) }, modifier = Modifier.testTag("dashboard-export")) {
                        Icon(Icons.Default.FileUpload, stringResource(R.string.capture_chaldea_create))
                    }
                }
                if (friendCount != null) {
                    IconButton(onClick = { openFriends(userId) }, modifier = Modifier.testTag("dashboard-friends")) {
                        Icon(Icons.Default.People, stringResource(R.string.friends_title))
                    }
                }
                IconButton(onClick = openSettings, modifier = Modifier.testTag("dashboard-settings")) {
                    Icon(Icons.Default.Settings, stringResource(R.string.capture_title))
                }
            }
            if (loading && data == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("dashboard-loading"))
                }
            } else if (data == null) Text(stringResource(R.string.dashboard_no_data), Modifier.padding(16.dp))
            else BoxWithConstraints(Modifier.weight(1f)) {
                DashboardNavigation(
                    data, names, displayName, battle, outside, initialSection, friendCount, server, now,
                    wide = maxWidth >= 840.dp, openLogs = openLogs,
                )
            }
        }
    }
}
