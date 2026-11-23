package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import kotlinx.coroutines.launch

/** Tabular figures keep digits aligned between rows and across updates. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

@Composable
internal fun DashboardOverview(
    data: DashboardData, names: GameNameMap, friendCount: Int?, server: String, battle: BattleLogEntry?, outside: OutsideLogEntry?,
    now: Long, wide: Boolean, openLogs: () -> Unit, progress: DashboardProgress = DashboardProgress(),
) {
    Column(Modifier.fillMaxWidth().testTag("dashboard-overview"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val first: @Composable ColumnScope.() -> Unit = {
            OverviewResourceList(data, names)
            OverviewFruits(data, names)
            // Narrow: right after the resources and apples. Wide: the top of the right column instead.
            if (!wide) OverviewProgress(progress, now)
            data.servants?.takeIf { it.isNotEmpty() }?.let { OverviewServants(it, data.servantCatalog) }
        }
        val second: @Composable ColumnScope.() -> Unit = {
            if (wide) OverviewProgress(progress, now)
            data.storage?.let { OverviewStorage(it) }
            if (battle != null || outside != null) OverviewRecent(battle, outside, names, openLogs)
            // The left column is the longer one, so the account block fills the empty end of the right column.
            if (wide) OverviewAccount(data, friendCount, server, now)
        }
        // Narrow keeps one column in the order of the two wide columns; the account block closes it.
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = first)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), content = second)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = first)
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = second)
        }
        if (!wide) OverviewAccount(data, friendCount, server, now)
    }
}

@Composable
private fun OverviewTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** "11-17 ~ 11-23" in the device's time zone. `end - 1` keeps a set that ends at midnight on its own last day. */
internal fun weeklyDates(start: Long, end: Long, locale: Locale = Locale.getDefault(), timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): Pair<String, String> {
    val format = SimpleDateFormat("MM-dd", locale).apply { this.timeZone = timeZone }
    return format.format(Date(start * 1000)) to format.format(Date((end - 1) * 1000))
}

/** Hidden as a whole when it has no line; events that are not running are left out. */
@Composable
private fun OverviewProgress(progress: DashboardProgress, now: Long) {
    if (progress.isEmpty(now)) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-progress")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_progress))
        progress.weekly?.let { weekly ->
            val (from, to) = weeklyDates(weekly.start, weekly.end)
            Text(
                stringResource(R.string.dashboard_progress_weekly, from, to, weekly.done, weekly.total),
                Modifier.testTag("overview-progress-weekly"), style = MaterialTheme.typography.bodyMedium.tabular(),
            )
        }
        progress.running(now).forEach { event ->
            // Plain text and a primary bar also when the last reward is reached (owner, 2026-11-23).
            val color = MaterialTheme.colorScheme.primary
            Column(Modifier.fillMaxWidth().testTag("overview-progress-event:${event.eventId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (event.max != null) {
                    Text(
                        "${event.name} · ${event.points.grouped()} / ${event.max.grouped()}", style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SegmentedBar(listOf(event.fraction to color), Modifier.testTag("overview-progress-bar:${event.eventId}"))
                } else Text(
                    stringResource(R.string.dashboard_progress_event_open, event.name, event.points.grouped()),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                )
            }
        }
    }
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
    val rows = overviewResources(data).resources
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("overview-resources")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_resources))
        rows.forEach { row ->
            val name = if (row.key == TEAPOT_ROW) stringResource(R.string.dashboard_teapots) else resourceName(2, row.id!!, names)
            val note = if (row.id == 2L) data.freePaid?.let { (free, paid) -> stringResource(R.string.capture_stone_split, free.grouped(), paid.grouped()) } else null
            ResourceRow(row, name, note, names)
        }
    }
}

@Composable
private fun ResourceRow(row: OverviewResource, name: String, note: String?, names: GameNameMap) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("overview-resource:${row.key}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp)) { row.iconId?.let { ResourceIcon(2, it, names) } }
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            note.orEmpty(), Modifier.weight(1f).testTag("overview-resource-note:${row.key}"), style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(row.count.grouped(), style = MaterialTheme.typography.bodyLarge.tabular(), textAlign = TextAlign.End)
    }
}

/** Owned and NP totals, then one row per rarity. Without the server's list there are no totals and no bars. */
@Composable
private fun OverviewServants(servants: List<DashboardServant>, catalog: List<AtlasBasic>?) {
    val summary = remember(servants, catalog) { servantSummary(servants, catalog) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-servants")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_servants))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val owned = summary.total?.let { "${summary.owned} / $it" } ?: summary.owned.toString()
            LabeledValue(stringResource(R.string.dashboard_stat_owned), owned, Modifier.weight(1f).testTag("overview-servants-owned"))
            LabeledValue(stringResource(R.string.dashboard_stat_star5_np), summary.star5Np.toString(), Modifier.weight(1f).testTag("overview-servants-star5-np"))
            LabeledValue(stringResource(R.string.dashboard_stat_all_np), summary.allNp.toString(), Modifier.weight(1f).testTag("overview-servants-all-np"))
        }
        summary.rarities.forEach { row ->
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).testTag("overview-rarity:${row.rarity}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("★${row.rarity}", Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium.tabular())
                if (row.total != null) SegmentedBar(
                    listOf((if (row.total > 0) row.owned.toFloat() / row.total else 0f) to MaterialTheme.colorScheme.primary),
                    Modifier.weight(1f).testTag("overview-rarity-bar:${row.rarity}"),
                ) else Spacer(Modifier.weight(1f))
                Text(
                    row.total?.let { "${row.owned} / $it" } ?: row.owned.toString(), Modifier.widthIn(min = 72.dp),
                    style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, maxLines = 1, softWrap = false,
                )
                Text(
                    stringResource(R.string.dashboard_col_np) + " " + row.np, Modifier.widthIn(min = 56.dp),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false,
                )
            }
        }
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
private fun OverviewAccount(data: DashboardData, friendCount: Int?, server: String, now: Long) {
    val profile = data.profile
    val fields = buildList {
        // The level and server left the header (owner, 2026-11-23); they come first here.
        profile["lv"]?.let { add(Triple("level", R.string.dashboard_account_level, it)) }
        add(Triple("server", R.string.dashboard_server, server))
        profile["friendCode"]?.let { add(Triple("friendCode", R.string.dashboard_friend_code, it)) }
        profile["friendPoint"]?.let { add(Triple("friendPoint", R.string.dashboard_friend_points, it.toLongOrNull()?.grouped() ?: it)) }
        profile["exp"]?.let { add(Triple("exp", R.string.dashboard_exp, it.toLongOrNull()?.grouped() ?: it)) }
        profile["costMax"]?.let { add(Triple("cost", R.string.dashboard_cost, it.toLongOrNull()?.grouped() ?: it)) }
        profile["friendKeep"]?.let { add(Triple("friends", R.string.dashboard_friends, friendLimitText(it, friendCount))) }
        profile["createdAt"]?.let { add(Triple("created", R.string.dashboard_created, it.toLongOrNull()?.let(::date) ?: it)) }
        data.login?.let { login ->
            login.total?.let { add(Triple("loginTotal", R.string.dashboard_login_total, loginDays(it))) }
            login.seq?.let { add(Triple("loginSeq", R.string.dashboard_login_seq, loginDays(it))) }
            val left = login.daysLeft
            if (login.nextRewardDay != null && left != null) {
                add(Triple("loginNext", R.string.dashboard_login_next, stringResource(R.string.dashboard_login_next_value, loginDays(login.nextRewardDay), loginDays(left))))
            }
        }
        add(Triple("snapshot", R.string.dashboard_snapshot, date(data.snapshotTime)))
        data.game?.number("commandSpellRecoverAt")?.takeIf { it > now }
            ?.let { add(Triple("commandSpell", R.string.dashboard_command_spell, date(it))) }
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("overview-account")) {
        OverviewTitle(stringResource(R.string.dashboard_overview_account))
        FlowRow(
            Modifier.fillMaxWidth(), maxItemsInEachRow = 2, verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fields.forEach { (key, label, value) ->
                if (key == "friendCode") Row(Modifier.weight(1f).testTag("overview-account:$key"), verticalAlignment = Alignment.CenterVertically) {
                    LabeledValue(stringResource(label), value, Modifier.weight(1f, fill = false))
                    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", value))) } }, modifier = Modifier.testTag("overview-friend-code-copy")) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.dashboard_copy))
                    }
                } else LabeledValue(stringResource(label), value, Modifier.weight(1f).testTag("overview-account:$key"))
            }
        }
    }
}

@Composable
private fun loginDays(days: Long): String =
    pluralStringResource(R.plurals.dashboard_login_days, days.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), days.grouped())

/** "143 / 146" once a friend list was seen, the plain limit before that. */
internal fun friendLimitText(keep: String, count: Int?): String {
    val limit = keep.toLongOrNull()?.grouped() ?: keep
    return if (count == null) limit else "$count / $limit"
}

internal fun clock(seconds: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
