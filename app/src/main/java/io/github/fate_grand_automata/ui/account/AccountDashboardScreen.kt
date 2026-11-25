package io.github.fate_grand_automata.ui.account

import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import io.github.fate_grand_automata.ui.LocalNavigateBack
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
    val progress by remember(vm, userId) { vm.progress(userId) }.collectAsStateWithLifecycle(DashboardProgress())
    val questRecords = remember(vm, userId) { QuestRecordsSource({ vm.questRecords(userId) }, vm::retryQuestNames) }
    val shopRecords = remember(vm, userId) { ShopRecordsSource({ vm.shopRecords(userId) }, vm::retryShopNames) }
    AccountDashboardContent(version.data, names, version.displayName, openSettings, openLogs, battle, recentOutside,
        userId = userId, exportAccount = export, friendCount = version.friendCount, openFriends = openFriends,
        server = version.server, loading = version.loading, progress = progress, questRecords = questRecords,
        shopRecords = shopRecords)
}

/**
 * @param initialSection a legacy block id (see [DashboardBlockId]). The navigation section that contains the block is
 * selected and the block is brought into view.
 */
@Composable
internal fun AccountDashboardContent(data: DashboardData?, names: GameNameMap, displayName: String?,
    openSettings: () -> Unit, openLogs: () -> Unit, battle: BattleLogEntry? = null, outside: OutsideLogEntry? = null,
    initialSection: Int = 0, userId: String = "", exportAccount: (String?) -> Unit = {}, friendCount: Int? = null,
    openFriends: (String) -> Unit = {}, server: String = "KR", loading: Boolean = false,
    progress: DashboardProgress = DashboardProgress(), questRecords: QuestRecordsSource? = null,
    shopRecords: ShopRecordsSource? = null) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) } }
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints {
                // At 360dp three 48dp action icons leave the title about 160dp, so the icons shrink and the title scales down to one line.
                val compact = maxWidth < 420.dp
                val iconModifier = if (compact) Modifier.size(40.dp) else Modifier
                val back = LocalNavigateBack.current
                Row(Modifier.padding(vertical = 16.dp).padding(start = if (back != null) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    back?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back)) } }
                    val title = stringResource(R.string.dashboard_title)
                    // Landscape keeps the small upper-case title every Heading has.
                    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        Text(title.uppercase(), Modifier.weight(1f).testTag("dashboard-title"), style = MaterialTheme.typography.titleSmall)
                    } else Text(title, Modifier.weight(1f).testTag("dashboard-title"), style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.headlineMedium.fontSize))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        IconButton(onClick = { exportAccount(userId) }, modifier = iconModifier.testTag("dashboard-export")) {
                            Icon(Icons.Default.FileUpload, stringResource(R.string.capture_chaldea_create))
                        }
                    }
                    if (friendCount != null) {
                        IconButton(onClick = { openFriends(userId) }, modifier = iconModifier.testTag("dashboard-friends")) {
                            Icon(Icons.Default.People, stringResource(R.string.friends_title))
                        }
                    }
                    IconButton(onClick = openSettings, modifier = iconModifier.testTag("dashboard-settings")) {
                        Icon(Icons.Default.Settings, stringResource(R.string.capture_title))
                    }
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
                    wide = maxWidth >= 840.dp, openLogs = openLogs, progress = progress, questRecords = questRecords,
                    shopRecords = shopRecords,
                )
            }
        }
    }
}
