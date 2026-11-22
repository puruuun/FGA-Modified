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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.LoginBreakdown
import io.github.fate_grand_automata.scripts.capture.LoginTopServant
import io.github.fate_grand_automata.ui.account.AtlasIcon
import io.github.fate_grand_automata.ui.account.IconPlaceholder
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.log.LogCardFrame
import io.github.fate_grand_automata.ui.log.LogSourceFilter
import io.github.fate_grand_automata.ui.log.logSource
import io.github.fate_grand_automata.ui.log.orderedLogSources
import io.github.fate_grand_automata.ui.log.sourceFilterLabel
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
    val sources = entry.period.sources.map { sourceName(it.source) }.joinToString(" · ")
    LogCardFrame(
        "outside:${entry.id}", stringResource(R.string.capture_outside_title), entry.accountName,
        periodTime(entry),
        (if (inProgress) stringResource(R.string.capture_log_in_progress) + " · " else "") + sources,
        selected, onClick, outside = true, checked = checked)
}

internal fun LazyListScope.outsideLogDetailItems(entry: OutsideLogEntry, names: GameNameMap, source: LogSourceFilter? = null) {
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
    val ordered = orderedLogSources(entry.period.sources, source)
    ordered.forEachIndexed { index, source ->
        val key = outsideSourceItemKey(index, source.source)
        item(key = key) {
            var expanded by rememberSaveable(entry.id, source.source) { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("outside-source:${source.source.group}:${source.source.path.orEmpty()}${if (source.source.script) ":script" else ""}")) {
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    Text(sourceName(source.source), Modifier.padding(start = 8.dp))
                }
                if (expanded) {
                    OutsideDelta(source.delta, names, source.fruitUsed)
                    // Records written before the breakdown existed have no logins and keep the tile alone.
                    if (source.source.group == ChangeSource.Group.Login && !source.source.script && entry.period.logins.isNotEmpty()) {
                        LoginBreakdowns(entry.period.logins, names)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginBreakdowns(logins: List<LoginBreakdown>, names: GameNameMap) {
    val locale = LocalConfiguration.current.locales[0]
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    Column(Modifier.padding(top = 8.dp).testTag("login-breakdowns"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        logins.forEachIndexed { index, login ->
            Column(Modifier.testTag("login-block:$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // A single login needs no heading: its time is the period's own.
                if (logins.size > 1) Text(time.format(Date(login.at * 1000)), Modifier.testTag("login-time:$index"), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.capture_login_received), style = MaterialTheme.typography.titleSmall)
                LoginAmount(index, "friend", stringResource(R.string.capture_login_friend), login.friend)
                LoginAmount(index, "follow", stringResource(R.string.capture_login_follow), login.follow)
                LoginAmount(index, "recommend", stringResource(R.string.capture_login_recommend), login.recommend)
                Text(stringResource(R.string.capture_login_top), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall)
                LoginTop(index, "normal", stringResource(R.string.capture_login_top_normal), login.normal, names)
                LoginTop(index, "event", stringResource(R.string.capture_login_top_event), login.event, names)
                LoginTop(index, "grand", stringResource(R.string.capture_login_top_grand), login.grand, names)
            }
        }
    }
}

@Composable
private fun LoginAmount(index: Int, key: String, label: String, amount: Long) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("login-$index-$key"), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (amount == 0L) muted else MaterialTheme.colorScheme.onSurface)
        Text(signedOutsideCount(amount), color = if (amount == 0L) muted else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LoginTop(index: Int, key: String, label: String, top: LoginTopServant?, names: GameNameMap) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("login-$index-top-$key"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
        if (top == null) {
            Text(stringResource(R.string.capture_login_top_none), color = muted)
        } else {
            val atlas = names.servantData[top.svtId]
            // Same face and the same name fallback as the dashboard party rows.
            IconPlaceholder { AtlasIcon("servants", top.svtId, atlas?.icon) }
            Text(names.servants[top.svtId] ?: atlas?.name ?: "#${top.svtId}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(signedOutsideCount(top.friendPoint))
        }
    }
}
private val otherSourceLabels = mapOf(
    "externalPayment/reflect" to R.string.capture_source_external_payment,
)

@Composable
internal fun sourceName(source: ChangeSource): String {
    if (!source.script && source.group == ChangeSource.Group.Other) {
        otherSourceLabels[source.path]?.let { return stringResource(it) }
        return "${sourceFilterLabel(source.logSource())} · ${source.path.orEmpty()}"
    }
    return sourceFilterLabel(source.logSource())
}

@Composable
private fun OutsideDelta(delta: AccountDelta, names: GameNameMap, fruitUsed: Map<Long, Long>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResourceGroups(outsideResources(delta, fruitUsed), names)
    }
}

@Composable
internal fun OutsideLogDetail(entry: OutsideLogEntry, onBack: () -> Unit, names: GameNameMap = GameNameMap(), source: LogSourceFilter? = null) {
    CappedWidth {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Heading(stringResource(R.string.capture_outside_title), leading = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Default.ArrowBack, stringResource(R.string.navigate_back)) }
            })
            LazyColumn(Modifier.weight(1f)) { outsideLogDetailItems(entry, names, source) }
        }
    }
}

/** The index keeps keys unique even if two entries ever share group, path and script. */
internal fun outsideSourceItemKey(index: Int, source: ChangeSource) =
    "source:$index:${source.group}:${source.path.orEmpty()}:${source.script}"
