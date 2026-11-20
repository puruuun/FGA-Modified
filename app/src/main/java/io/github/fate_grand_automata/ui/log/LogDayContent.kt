package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.sourceName
import io.github.fate_grand_automata.scripts.capture.GameDay
import io.github.fate_grand_automata.scripts.capture.gameDay
import io.github.fate_grand_automata.ui.Heading
import java.text.SimpleDateFormat
import java.util.GregorianCalendar

@Composable
internal fun dayLabel(day: GameDay, now: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val skeleton = if (day.year == gameDay(now).year) "MMMdEEE" else "yMMMdEEE"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    // Local calendar on purpose: only the numeric date is formatted, so no zone shifts it.
    return SimpleDateFormat(pattern, locale).format(GregorianCalendar(day.year, day.month - 1, day.day).time)
}

@Composable
internal fun LogDayCard(day: LogDay, selected: Boolean, now: Long, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .testTag("log-day:${day.key}").semantics { this.selected = selected },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(dayLabel(day.day, now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                (if (day.inProgress) stringResource(R.string.capture_log_in_progress) + " · " else "") +
                    stringResource(R.string.capture_log_day_summary, day.battles.size, day.accounts.size),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun LogViewSwitch(dayView: Boolean, onDayView: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!dayView, { onDayView(false) }, { Text(stringResource(R.string.capture_log_view_record)) },
            Modifier.testTag("log-view-record"))
        FilterChip(dayView, { onDayView(true) }, { Text(stringResource(R.string.capture_log_view_day)) },
            Modifier.testTag("log-view-day"))
    }
}

internal fun LazyListScope.logDayItems(day: LogDay, names: GameNameMap, now: Long, onBattle: (String) -> Unit) {
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(dayLabel(day.day, now), style = MaterialTheme.typography.titleMedium)
            if (deviceZoneDiffers(now)) Text(stringResource(R.string.capture_log_day_zone_note),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("log-day-zone-note"))
        }
    }
    item { Text(stringResource(R.string.capture_log_day_changes), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall) }
    if (day.accounts.isEmpty()) item { Text(stringResource(R.string.capture_log_day_no_changes),
        Modifier.padding(horizontal = 16.dp).testTag("log-day-no-changes")) }
    items(day.accounts, key = { "account:${it.accountId}" }) { LogDayAccountCard(day, it, names) }
    item { Text(stringResource(R.string.capture_log_day_scripts), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall) }
    if (day.battles.isEmpty()) item { Text(stringResource(R.string.capture_log_day_no_scripts),
        Modifier.padding(horizontal = 16.dp).testTag("log-day-no-scripts")) }
    items(day.battles, key = { it.id }) { entry ->
        Box(Modifier.padding(bottom = 6.dp)) { entry.battle?.let { LogCard(it, false, { onBattle(entry.id) }, null) } }
    }
}

@Composable
private fun LogDayAccountCard(day: LogDay, account: LogDayAccount, names: GameNameMap) {
    var expanded by rememberSaveable(day.key, account.accountId) { mutableStateOf(false) }
    val noProfile = stringResource(R.string.capture_log_no_profile)
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("log-day-account:${account.accountId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(account.name ?: noProfile, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                account.server?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (account.totals.isEmpty()) Text(stringResource(R.string.capture_log_day_no_changes), style = MaterialTheme.typography.bodySmall)
            else ResourceGroups(account.totals.map { (key, count) ->
                val (type, id) = resourceKeyParts(key)
                LogResource(type, id, count)
            }, names)
            TextButton(onClick = { expanded = !expanded }, Modifier.testTag("log-day-expand:${account.accountId}")) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Text(
                    if (expanded) stringResource(R.string.capture_gains_collapse)
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
internal fun LogDayDetail(day: LogDay, names: GameNameMap, now: Long, onBack: (() -> Unit)?, onBattle: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) Heading(stringResource(R.string.p_logs), leading = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back)) }
        })
        LazyColumn(Modifier.weight(1f).testTag("log-day-detail")) { logDayItems(day, names, now, onBattle) }
    }
}
