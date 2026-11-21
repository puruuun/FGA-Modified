package io.github.fate_grand_automata.ui.account

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.util.grouped

@StringRes
private fun DashboardSection.label() = when (this) {
    DashboardSection.Overview -> R.string.dashboard_nav_overview
    DashboardSection.Servants -> R.string.dashboard_nav_servants
    DashboardSection.Items -> R.string.dashboard_nav_items
    DashboardSection.Formations -> R.string.dashboard_nav_formations
    DashboardSection.Collection -> R.string.dashboard_nav_collection
    DashboardSection.PresentBox -> R.string.dashboard_nav_present_box
}

/** The header text and the rail item text start at the same x. */
private val HEADER_PADDING = 24.dp

/**
 * The section list beside the content on wide screens, a scrollable tab row on narrow ones. Narrow screens use tabs,
 * not a list-first flow, so that switching sections needs no extra tap.
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
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountHeader(data, displayName, server, now, wide = true, Modifier.padding(horizontal = HEADER_PADDING))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column {
                        sections.forEach { section ->
                            RailItem(stringResource(section.label()), section == selected, { onSelect(section) }, section.name)
                        }
                    }
                }
            }
            SectionPane(selected, data, names, battle, outside, friendCount, now, true, openLogs, target, initialSection, Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize()) {
            AccountHeader(data, displayName, server, now, wide = false, Modifier.padding(horizontal = HEADER_PADDING, vertical = 8.dp))
            PrimaryScrollableTabRow(selectedTabIndex = sections.indexOf(selected).coerceAtLeast(0), edgePadding = 8.dp) {
                sections.forEach { section ->
                    Tab(
                        selected = section == selected, onClick = { onSelect(section) },
                        modifier = Modifier.testTag("dashboard-nav:${section.name}"),
                        text = { Text(stringResource(section.label()), maxLines = 1) },
                    )
                }
            }
            SectionPane(selected, data, names, battle, outside, friendCount, now, false, openLogs, target, initialSection, Modifier.weight(1f))
        }
    }
}

/** A square item: no container, a 3dp `primary` bar on the left edge and `primary` text when selected. At least 48dp high. */
@Composable
private fun RailItem(label: String, selected: Boolean, onClick: () -> Unit, name: String) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick).testTag("dashboard-nav:$name"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary).testTag("dashboard-nav-bar:$name"))
        else Spacer(Modifier.width(3.dp))
        Text(
            label, Modifier.padding(start = HEADER_PADDING - 3.dp, end = HEADER_PADDING), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                // Servants is one lazy list that fills the pane; a lazy list cannot sit inside a vertical scroll.
                .then(if (selected == DashboardSection.Servants) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(16.dp),
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

/** Name and game name, server badge, level, how long ago the data was updated, and AP, which every section shows. */
@Composable
private fun AccountHeader(data: DashboardData, displayName: String?, server: String, now: Long, wide: Boolean, modifier: Modifier = Modifier) {
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
        HeaderAp(data, now, wide)
    }
}

@Composable
private fun HeaderAp(data: DashboardData, now: Long, wide: Boolean) {
    val ap = dashboardAp(data.game, now)
    val carry = data.game?.number("carryOverActPoint")
    if (ap == null && carry == null) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("header-ap"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = @Composable { Text("AP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val value = @Composable {
            if (ap != null) Text("${ap.current.grouped()} / ${ap.max.grouped()}", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        }
        // Narrow screens put the label beside the value to keep the header short above the tab row.
        if (wide) { label(); value() } else Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) { label(); value() }
        if (ap != null) LinearProgressIndicator(
            progress = { if (ap.max > 0) (ap.current.toFloat() / ap.max).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().testTag("header-ap-bar"), drawStopIndicator = {},
        )
        val full = ap?.let {
            if (it.recoverAt <= now) stringResource(R.string.capture_ap_full) else stringResource(R.string.dashboard_ap_full_at, clock(it.recoverAt))
        }
        val carryText = carry?.let { "${stringResource(R.string.dashboard_carry_ap)} ${it.grouped()}" }
        Text(
            listOfNotNull(full, carryText).joinToString(" · "), Modifier.testTag("header-ap-detail"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
