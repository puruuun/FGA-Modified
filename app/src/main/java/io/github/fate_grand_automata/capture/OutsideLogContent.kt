package io.github.fate_grand_automata.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.log.LogCardFrame
import io.github.fate_grand_automata.ui.log.ResourceGroups
import io.github.fate_grand_automata.ui.log.outsideResources
import io.github.fate_grand_automata.util.grouped
import java.text.DateFormat
import java.util.Date

internal fun signedOutsideCount(value: Long): String = when {
    value > 0 -> "+${value.grouped()}"
    value < 0 -> value.grouped().replace('-', '−')
    else -> "0"
}

@Composable
private fun periodTime(entry: OutsideLogEntry): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    val day = DateFormat.getDateInstance(DateFormat.SHORT, locale)
    val start = Date(entry.startedAt)
    val end = Date(entry.endedAt)
    return if (day.format(start) == day.format(end)) "${day.format(start)} ${time.format(start)}–${time.format(end)}"
    else "${day.format(start)} ${time.format(start)} – ${day.format(end)} ${time.format(end)}"
}

@Composable
internal fun OutsideLogCard(entry: OutsideLogEntry, selected: Boolean, onClick: () -> Unit, inProgress: Boolean = false, checked: Boolean? = null) {
    val total = entry.period.total
    LogCardFrame(
        "outside:${entry.id}", stringResource(R.string.capture_outside_title), entry.accountName,
        periodTime(entry),
        (if (inProgress) stringResource(R.string.capture_log_in_progress) + " · " else "") + stringResource(R.string.capture_outside_summary,
            total.items.count { it.value > 0 }.toLong().grouped(), total.items.count { it.value < 0 }.toLong().grouped(),
            signedOutsideCount(total.freeStone + total.chargeStone)), selected, onClick, outside = true, checked = checked)
}

internal fun LazyListScope.outsideLogDetailItems(entry: OutsideLogEntry, names: GameNameMap) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.capture_outside_title), style = MaterialTheme.typography.titleMedium)
            Text(entry.accountName)
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM, LocalConfiguration.current.locales[0]).format(Date(entry.startedAt)),
                style = MaterialTheme.typography.bodySmall)
            Text(periodTime(entry), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(stringResource(R.string.capture_outside_totals), style = MaterialTheme.typography.titleMedium)
            OutsideDelta(entry.period.total, names, entry.period.fruitUsed)
            HorizontalDivider()
            Text(stringResource(R.string.capture_outside_sources), style = MaterialTheme.typography.titleMedium)
        }
    }
    entry.period.sources.forEach { source ->
        item(key = "source:${source.source.group}:${source.source.path.orEmpty()}") {
            var expanded by rememberSaveable(entry.id, source.source) { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("outside-source:${source.source.group}:${source.source.path.orEmpty()}")) {
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    Text(sourceName(source.source), Modifier.padding(start = 8.dp))
                }
                if (expanded) OutsideDelta(source.delta, names, source.fruitUsed)
            }
        }
    }
}

@Composable
private fun sourceName(source: ChangeSource): String {
    val label = stringResource(when (source.group) {
        ChangeSource.Group.GiftBox -> R.string.capture_source_gift_box
        ChangeSource.Group.Shop -> R.string.capture_source_shop
        ChangeSource.Group.Summon -> R.string.capture_source_summon
        ChangeSource.Group.Lottery -> R.string.capture_source_lottery
        ChangeSource.Group.Enhancement -> R.string.capture_source_enhancement
        ChangeSource.Group.ApRecovery -> R.string.capture_source_ap_recovery
        ChangeSource.Group.ManualQuest -> R.string.capture_source_manual_quest
        ChangeSource.Group.Missions -> R.string.capture_source_missions
        ChangeSource.Group.Other -> R.string.capture_source_other
    })
    return if (source.group == ChangeSource.Group.Other) "$label · ${source.path.orEmpty()}" else label
}

@Composable
private fun OutsideDelta(delta: AccountDelta, names: GameNameMap, fruitUsed: Map<Long, Long>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResourceGroups(outsideResources(delta, fruitUsed), names)
    }
}

@Composable
fun OutsideLogDetail(entry: OutsideLogEntry, onBack: () -> Unit, names: GameNameMap = GameNameMap()) {
    CappedWidth {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Heading(stringResource(R.string.capture_outside_title), leading = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back)) }
            })
            LazyColumn(Modifier.weight(1f)) { outsideLogDetailItems(entry, names) }
        }
    }
}
