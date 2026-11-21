package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.util.grouped
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tabular figures keep digits aligned between rows and across updates. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

@Composable
internal fun DashboardOverview(
    data: DashboardData, names: GameNameMap, friendCount: Int?, battle: BattleLogEntry?, outside: OutsideLogEntry?,
    now: Long, wide: Boolean, openLogs: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("dashboard-overview"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Headline(data, now, wide)
        val first: @Composable ColumnScope.() -> Unit = {
            OverviewFruits(data, names)
            OverviewResourceList(data, names)
        }
        val second: @Composable ColumnScope.() -> Unit = {
            data.storage?.let { OverviewStorage(it) }
            if (battle != null || outside != null) OverviewRecent(battle, outside, names, openLogs)
            OverviewAccount(data, friendCount, now)
        }
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = first)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = second)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = first)
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = second)
        }
    }
}

@Composable
private fun Headline(data: DashboardData, now: Long, wide: Boolean) {
    val game = data.game
    val ap = dashboardAp(game, now)
    val carry = game?.number("carryOverActPoint")
    val quartz = data.resources[2L]
    val qp = data.resources[1L]
    val apBlock: @Composable (Modifier) -> Unit = { modifier ->
        if (ap != null || carry != null) Column(modifier.testTag("overview-ap"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeadlineLabel("AP")
            if (ap != null) {
                Text("${ap.current.grouped()} / ${ap.max.grouped()}", style = MaterialTheme.typography.headlineSmall.tabular())
                LinearProgressIndicator(
                    progress = { if (ap.max > 0) (ap.current.toFloat() / ap.max).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().testTag("overview-ap-bar"),
                )
                Text(
                    if (ap.recoverAt <= now) stringResource(R.string.capture_ap_full)
                    else stringResource(R.string.dashboard_ap_full_at, clock(ap.recoverAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            carry?.let {
                Text(
                    "${stringResource(R.string.dashboard_carry_ap)} ${it.grouped()}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val quartzBlock: @Composable (Modifier) -> Unit = { modifier ->
        if (quartz != null) Column(modifier.testTag("overview-quartz"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeadlineLabel(stringResource(R.string.capture_quartz))
            Text(quartz.grouped(), style = MaterialTheme.typography.headlineSmall.tabular())
            data.freePaid?.let { (free, paid) ->
                Text(
                    stringResource(R.string.capture_stone_split, free.grouped(), paid.grouped()),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val qpBlock: @Composable (Modifier) -> Unit = { modifier ->
        if (qp != null) Column(modifier.testTag("overview-qp"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeadlineLabel(stringResource(R.string.capture_qp))
            Text(qp.grouped(), style = MaterialTheme.typography.headlineSmall.tabular())
        }
    }
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        apBlock(Modifier.weight(1f)); quartzBlock(Modifier.weight(1f)); qpBlock(Modifier.weight(1f))
    } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        apBlock(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            quartzBlock(Modifier.weight(1f)); qpBlock(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeadlineLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun OverviewTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun OverviewFruits(data: DashboardData, names: GameNameMap) {
    val fruits = overviewResources(data).fruits
    if (fruits.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-fruits")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_fruits))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fruits.forEach { (id, count) ->
                val name = resourceName(2, id, names)
                var iconShown by remember(id) { mutableStateOf(false) }
                Row(
                    Modifier.testTag("overview-fruit:$id").semantics(mergeDescendants = true) { contentDescription = "$name, ${count.grouped()}" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ResourceIcon(2, id, names) { iconShown = it }
                    if (!iconShown) Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(count.grouped(), style = MaterialTheme.typography.bodyLarge.tabular())
                }
            }
        }
    }
}

@Composable
private fun OverviewResourceList(data: DashboardData, names: GameNameMap) {
    val split = overviewResources(data)
    if (split.resources.isEmpty() && split.teapots == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("overview-resources")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_resources))
        split.resources.forEach { (id, count) -> ResourceRow(id, resourceName(2, id, names), count, names) }
        split.teapots?.let { ResourceRow(it.iconId, stringResource(R.string.dashboard_teapots), it.total, names) }
    }
}

@Composable
private fun ResourceRow(id: Long, name: String, count: Long, names: GameNameMap) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("overview-resource:$id"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp)) { ResourceIcon(2, id, names) }
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.grouped(), style = MaterialTheme.typography.bodyLarge.tabular(), textAlign = TextAlign.End)
    }
}

@Composable
private fun OverviewStorage(storage: DashboardStorage) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-storage")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_storage))
        if (storage.total != null) {
            if (storage.servants != null && storage.others != null && storage.equips != null) {
                val primary = MaterialTheme.colorScheme.primary
                val secondary = MaterialTheme.colorScheme.secondary
                val limit = storage.servantLimit
                BarHeader(stringResource(R.string.dashboard_servants_others), storage.servants + storage.others, limit)
                if (limit != null && limit > 0) SegmentedBar(
                    listOf(storage.servants.toFloat() / limit to primary, storage.others.toFloat() / limit to secondary),
                    Modifier.testTag("overview-storage-bar"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(primary, stringResource(R.string.dashboard_legend_servants), storage.servants)
                    Legend(secondary, stringResource(R.string.dashboard_legend_others), storage.others)
                }
                BarHeader(stringResource(R.string.dashboard_ces), storage.equips, storage.equipLimit)
                storage.equipLimit?.takeIf { it > 0 }?.let {
                    SegmentedBar(listOf(storage.equips.toFloat() / it to primary), Modifier.testTag("overview-ce-bar"))
                }
            } else LabeledValue(stringResource(R.string.dashboard_total), storage.total.toString())
        }
        if (storage.commandCodes != null || storage.secondStorage != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                storage.commandCodes?.let { LabeledValue(stringResource(R.string.dashboard_command_codes), it.toString(), Modifier.weight(1f)) }
                storage.secondStorage?.let { LabeledValue(stringResource(R.string.dashboard_second_storage), it.toString(), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BarHeader(label: String, count: Int, limit: Long?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$count${limit?.let { " / ${it.grouped()}" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium.tabular())
    }
}

/** Segments are fractions of the whole bar, drawn left to right over the track. */
@Composable
private fun SegmentedBar(segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track).drawBehind {
            var x = 0f
            segments.forEach { (fraction, color) ->
                val width = (size.width * fraction.coerceIn(0f, 1f)).coerceAtMost(size.width - x)
                drawRect(color, Offset(x, 0f), Size(width, size.height))
                x += width
            }
        },
    )
}

@Composable
private fun Legend(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text("$label $count", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.tabular())
    }
}

@Composable
private fun OverviewRecent(battle: BattleLogEntry?, outside: OutsideLogEntry?, names: GameNameMap, openLogs: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-recent")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_recent))
        battle?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${date(it.endedAt / 1000)} · ${it.configName}", style = MaterialTheme.typography.bodyMedium)
                (it.gains?.lastQuestId ?: it.gains?.battles?.maxByOrNull { row -> row.time }?.questId)?.let { id ->
                    Text(stringResource(R.string.dashboard_quest, id), style = MaterialTheme.typography.bodySmall)
                }
                val gains = it.gains?.let(::battleResources).orEmpty()
                Text(
                    if (gains.isEmpty()) it.reasonText else gainLine(gains, names), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        outside?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${date(it.startedAt / 1000)} – ${date(it.endedAt / 1000)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    gainLine(outsideResources(it.period.total, it.period.fruitUsed), names), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = openLogs, modifier = Modifier.align(Alignment.End).testTag("dashboard-logs")) {
            Text(stringResource(R.string.dashboard_view_all))
        }
    }
}

@Composable
private fun gainLine(resources: List<LogResource>, names: GameNameMap): String =
    resources.take(3).map { "${resourceName(it.type, it.id, names)} ${it.count?.let(::signedOutsideCount) ?: "—"}" }.joinToString(" · ")

@Composable
private fun OverviewAccount(data: DashboardData, friendCount: Int?, now: Long) {
    val profile = data.profile
    val fields = buildList {
        profile["friendPoint"]?.let { add(Triple("friendPoint", R.string.dashboard_friend_points, it.toLongOrNull()?.grouped() ?: it)) }
        profile["exp"]?.let { add(Triple("exp", R.string.dashboard_exp, it.toLongOrNull()?.grouped() ?: it)) }
        profile["costMax"]?.let { add(Triple("cost", R.string.dashboard_cost, it.toLongOrNull()?.grouped() ?: it)) }
        profile["friendKeep"]?.let { add(Triple("friends", R.string.dashboard_friends, friendLimitText(it, friendCount))) }
        profile["createdAt"]?.let { add(Triple("created", R.string.dashboard_created, it.toLongOrNull()?.let(::date) ?: it)) }
        add(Triple("snapshot", R.string.dashboard_snapshot, date(data.snapshotTime)))
        data.game?.number("commandSpellRecoverAt")?.takeIf { it > now }
            ?.let { add(Triple("commandSpell", R.string.dashboard_command_spell, date(it))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-account")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_account))
        FlowRow(
            Modifier.fillMaxWidth(), maxItemsInEachRow = 2, verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fields.forEach { (key, label, value) ->
                LabeledValue(stringResource(label), value, Modifier.weight(1f).testTag("overview-account:$key"))
            }
        }
    }
}

/** "143 / 146" once a friend list was seen, the plain limit before that. */
internal fun friendLimitText(keep: String, count: Int?): String {
    val limit = keep.toLongOrNull()?.grouped() ?: keep
    return if (count == null) limit else "$count / $limit"
}

private fun clock(seconds: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
