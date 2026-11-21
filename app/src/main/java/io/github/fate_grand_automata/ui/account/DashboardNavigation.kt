package io.github.fate_grand_automata.ui.account

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import kotlinx.coroutines.launch

@StringRes
private fun DashboardSection.label() = when (this) {
    DashboardSection.Overview -> R.string.dashboard_nav_overview
    DashboardSection.Servants -> R.string.dashboard_nav_servants
    DashboardSection.Items -> R.string.dashboard_nav_items
    DashboardSection.Formations -> R.string.dashboard_nav_formations
    DashboardSection.Collection -> R.string.dashboard_nav_collection
    DashboardSection.PresentBox -> R.string.dashboard_nav_present_box
}

/**
 * The section list beside the content on wide screens, a scrollable tab row on narrow ones. Narrow screens use tabs,
 * not a list-first flow, so that AP is visible without an extra tap.
 */
@Composable
internal fun DashboardNavigation(
    data: DashboardData, names: GameNameMap, displayName: String?, battle: BattleLogEntry?, outside: OutsideLogEntry?,
    initialSection: Int, friendCount: Int?, server: String, now: Long, wide: Boolean, openLogs: () -> Unit,
) {
    val sections = remember(data) { dashboardSections(data) }
    // The raw choice survives rotation and process recreation; each opening of the dashboard starts from initialSection.
    var chosen by rememberSaveable { mutableStateOf(sectionOfBlock(initialSection).name) }
    val selected = resolveSection(DashboardSection.entries.firstOrNull { it.name == chosen } ?: DashboardSection.Overview, sections)
    LaunchedEffect(selected) { chosen = selected.name }
    val target = remember { BringIntoViewRequester() }
    val onSelect: (DashboardSection) -> Unit = { chosen = it.name }
    CompositionLocalProvider(LocalDashboardTarget provides DashboardTarget(initialSection, target)) {
        if (wide) Row(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(240.dp).fillMaxHeight().testTag("dashboard-nav-pane")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountHeader(data, displayName, friendCount, server, now)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        sections.forEach { section ->
                            val count = sectionCount(section, data)
                            NavigationDrawerItem(
                                label = { Text(stringResource(section.label())) },
                                selected = section == selected,
                                onClick = { onSelect(section) },
                                badge = count?.let { { Text(it.toString()) } },
                                modifier = Modifier.testTag("dashboard-nav:${section.name}"),
                            )
                        }
                    }
                }
            }
            SectionPane(selected, data, names, battle, outside, friendCount, now, true, openLogs, target, initialSection, Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize()) {
            AccountHeader(data, displayName, friendCount, server, now, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            PrimaryScrollableTabRow(selectedTabIndex = sections.indexOf(selected).coerceAtLeast(0), edgePadding = 8.dp) {
                sections.forEach { section ->
                    val count = sectionCount(section, data)
                    Tab(
                        selected = section == selected, onClick = { onSelect(section) },
                        modifier = Modifier.testTag("dashboard-nav:${section.name}"),
                        text = { Text(stringResource(section.label()) + (count?.let { " $it" } ?: ""), maxLines = 1) },
                    )
                }
            }
            SectionPane(selected, data, names, battle, outside, friendCount, now, false, openLogs, target, initialSection, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionPane(
    selected: DashboardSection, data: DashboardData, names: GameNameMap, battle: BattleLogEntry?, outside: OutsideLogEntry?,
    friendCount: Int?, now: Long, wide: Boolean, openLogs: () -> Unit, target: BringIntoViewRequester, initialSection: Int, modifier: Modifier,
) {
    key(selected) {
        Column(
            modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("dashboard-page")
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (selected) {
                DashboardSection.Overview -> DashboardOverview(data, names, friendCount, battle, outside, now, wide, openLogs)
                DashboardSection.Servants -> DashboardServantsSection(data)
                DashboardSection.Items -> data.inventory?.let { DashboardItemsSection(it, names) }
                DashboardSection.Formations -> DashboardFormationsSection(data, names)
                DashboardSection.Collection -> DashboardCollectionSection(data, names)
                DashboardSection.PresentBox -> data.presentBox?.let { DashboardPresentBoxSection(it, names) }
            }
        }
        if (sectionOfBlock(initialSection) == selected && isBlockAddressable(initialSection)) {
            LaunchedEffect(initialSection) {
                withFrameNanos { }
                // The block may be absent (no data); there is then nothing to bring into view.
                runCatching { target.bringIntoView() }
            }
        }
    }
}

/** Name and game name, server badge, level, friend code and how long ago the data was updated. */
@Composable
private fun AccountHeader(data: DashboardData, displayName: String?, friendCount: Int?, server: String, now: Long, modifier: Modifier = Modifier) {
    val profile = data.profile
    Column(modifier.fillMaxWidth().testTag("dashboard-account-header"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOfNotNull(displayName, profile["name"]).distinct().takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(server, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
            profile["lv"]?.let { Text(stringResource(R.string.dashboard_level_short, it), style = MaterialTheme.typography.bodyMedium) }
        }
        profile["friendCode"]?.let { code ->
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dashboard_friend_code), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(code, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                }
                IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", code))) } }) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.dashboard_copy))
                }
            }
        }
        val age = relativeAge(now, data.lastUpdateTime)
        val ageText = when (age.unit) {
            AgeUnit.JustNow -> stringResource(R.string.dashboard_updated_just_now)
            AgeUnit.Minutes -> pluralStringResource(R.plurals.dashboard_updated_minutes, age.amount, age.amount)
            AgeUnit.Hours -> pluralStringResource(R.plurals.dashboard_updated_hours, age.amount, age.amount)
            AgeUnit.Days -> pluralStringResource(R.plurals.dashboard_updated_days, age.amount, age.amount)
        }
        // The absolute time is not shown, but a screen reader and the tests can read it from the description.
        val absolute = "${stringResource(R.string.dashboard_updated)}: ${date(data.lastUpdateTime)}"
        Text(ageText, Modifier.semantics { contentDescription = absolute }.testTag("dashboard-updated"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
