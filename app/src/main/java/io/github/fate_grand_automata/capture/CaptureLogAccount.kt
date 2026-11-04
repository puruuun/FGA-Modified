package io.github.fate_grand_automata.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.fate_grand_automata.battlelog.BattleLogEntry

/** Historical names win over later renames; an id-only entry can use the surviving account. */
internal fun BattleLogEntry.captureAccountName(accounts: List<CaptureAccount>): String? =
    accountName ?: accountId?.let { id -> accounts.find { it.id == id }?.displayName }

@Composable
internal fun rememberCaptureLogEntries(entries: List<BattleLogEntry>): List<BattleLogEntry> {
    val accounts by CaptureTracker.accounts.collectAsState()
    return entries.map { it.copy(accountName = it.captureAccountName(accounts)) }
}
