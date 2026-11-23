package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.sourceName
import io.github.fate_grand_automata.ui.Heading

@Composable
internal fun LogDayCard(row: LogDayRow, selected: Boolean, now: Long, onClick: () -> Unit) {
    val noProfile = stringResource(R.string.capture_log_no_profile)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .testTag("log-day:${row.key}").semantics { this.selected = selected },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.testTag("log-day-line1:${row.key}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The date and the server never shrink; only a long name gives way.
                Text(dayLabel(row.day.day, now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false)
                Text(row.account.name ?: noProfile, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                row.account.server?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
            }
            val battle = stringResource(R.string.capture_log_day_source_battle)
            val sources = daySources(row.account).map {
                when (it) { DaySource.Battle -> battle; is DaySource.Other -> sourceFilterLabel(it.source) }
            }
            val line2 = listOfNotNull(stringResource(R.string.capture_log_in_progress).takeIf { row.account.inProgress }, *sources.toTypedArray())
                .joinToString(" · ")
            // An empty line is left out instead of kept blank: a day without sources has nothing to say there.
            if (line2.isNotEmpty()) Text(line2, Modifier.testTag("log-day-line2:${row.key}"), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun LazyListScope.logDayItems(row: LogDayRow, names: GameNameMap, now: Long, onBattle: (String) -> Unit) {
    val account = row.account
    item {
        val noProfile = stringResource(R.string.capture_log_no_profile)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(dayLabel(row.day.day, now), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(account.name ?: noProfile, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                account.server?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (deviceZoneDiffers(now)) Text(stringResource(R.string.capture_log_day_zone_note),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("log-day-zone-note"))
        }
    }
    item { Text(stringResource(R.string.capture_log_day_changes), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall) }
    item(key = "account:${row.key}") { LogDayAccountChanges(row, names) }
    item { Text(stringResource(R.string.capture_log_day_scripts), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall) }
    if (account.battles.isEmpty()) item { Text(stringResource(R.string.capture_log_day_no_scripts),
        Modifier.padding(horizontal = 16.dp).testTag("log-day-no-scripts")) }
    items(account.battles, key = { it.id }) { entry ->
        entry.battle?.let { LogDayScriptItem(it, { onBattle(entry.id) }) }
    }
}

/** Plain list item on the page (no card): the day detail is a report, the record view keeps the cards. */
@Composable
private fun LogDayScriptItem(entry: BattleLogEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("log-script:${entry.id}")
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.gains != null) Icon(painterResource(R.drawable.ic_swords),
                    stringResource(R.string.capture_log_recorded_with_collection), Modifier.size(16.dp))
                Text(entry.configName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(localDateTimeText(entry.endedAt), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(battleSummaryText(entry), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LogDayAccountChanges(row: LogDayRow, names: GameNameMap) {
    val account = row.account
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    // No card container: the changes sit on the page under the account heading.
    Column(Modifier.fillMaxWidth().testTag("log-day-account:${account.accountId}")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account.totals.isEmpty()) Text(stringResource(R.string.capture_log_day_no_changes), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("log-day-no-changes"))
            else ResourceGroups(account.totals.map { (key, count) ->
                val (type, id) = resourceKeyParts(key)
                LogResource(type, id, count)
            }, names)
            TextButton(onClick = { expanded = !expanded }, Modifier.testTag("log-day-expand:${account.accountId}")) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Text(
                    if (expanded) stringResource(R.string.capture_log_day_collapse)
                    else if (account.battles.isNotEmpty()) stringResource(R.string.capture_log_day_included, account.battles.size)
                    else stringResource(R.string.capture_log_day_breakdown),
                    Modifier.padding(start = 8.dp))
            }
            if (expanded) {
                account.outside.forEach { record -> record.outside!!.period.sources.forEach { source ->
                    Text(sourceName(source.source), style = MaterialTheme.typography.titleSmall)
                    ResourceGroups(outsideResources(source.delta, source.fruitUsed), names)
                } }
                account.battles.forEach { record -> record.battle?.let { battle ->
                    Text(battle.configName, style = MaterialTheme.typography.titleSmall)
                    battle.gains?.let { ResourceGroups(battleResources(it), names) }
                } }
            }
        }
    }
}

@Composable
internal fun LogDayDetail(row: LogDayRow, names: GameNameMap, now: Long, onBack: (() -> Unit)?, onBattle: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) Heading(stringResource(R.string.p_logs), leading = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back)) }
        })
        LazyColumn(Modifier.weight(1f).testTag("log-day-detail")) { logDayItems(row, names, now, onBattle) }
    }
}
