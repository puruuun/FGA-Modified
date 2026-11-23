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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
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
    progress: DashboardProgress = DashboardProgress(),
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
            SectionPane(selected, data, names, battle, outside, friendCount, now, true, openLogs, target, initialSection, progress, Modifier.weight(1f))
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
            SectionPane(selected, data, names, battle, outside, friendCount, now, false, openLogs, target, initialSection, progress, Modifier.weight(1f))
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
    friendCount: Int?, now: Long, wide: Boolean, openLogs: () -> Unit, target: BringIntoViewRequester, initialSection: Int, progress: DashboardProgress, modifier: Modifier,
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
                DashboardSection.Overview -> DashboardOverview(data, names, friendCount, battle, outside, now, wide, openLogs, progress)
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

/** Max font sizes of the name line; the wide rail has the room of a `titleLarge`, the narrow header above the tabs less. */
private val NAME_MAX_WIDE = 20.sp
private val NAME_MAX_NARROW = 18.sp
private val NAME_MIN = 12.sp

/**
 * The name and the AP number are what the owner reads first, so they get the large type and everything else is small
 * secondary text. Wide: the name, the level and server, the AP number with its full time and a bar, then the update
 * time. Narrow: a row of a small ring and four lines, to keep the header short above the tab row.
 */
@Composable
private fun AccountHeader(data: DashboardData, displayName: String?, server: String, now: Long, wide: Boolean, modifier: Modifier = Modifier) {
    val profile = data.profile
    val name = listOfNotNull(displayName, profile["name"]).distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ")
    val ap = dashboardAp(data.game, now)
    val updated = @Composable {
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
    val nameLine = @Composable { nameModifier: Modifier ->
        name?.let {
            // One line that shrinks before it cuts: a game name is up to 12 characters, which must fit the 192dp of the rail.
            Text(
                it, nameModifier.testTag("header-name"), style = MaterialTheme.typography.titleLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = NAME_MIN, maxFontSize = if (wide) NAME_MAX_WIDE else NAME_MAX_NARROW),
            )
        }
    }
    val levelLine = @Composable {
        Row(Modifier.testTag("header-level"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profile["lv"]?.let {
                Text(stringResource(R.string.dashboard_level_short, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(server, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (wide) Column(modifier.fillMaxWidth().testTag("dashboard-account-header"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nameLine(Modifier.fillMaxWidth())
        levelLine()
        // The line parts the account (name, level, server) from its AP (owner, 2026-11-23).
        if (ap != null) {
            HorizontalDivider(Modifier.testTag("header-divider"), color = MaterialTheme.colorScheme.outlineVariant)
            HeaderAp(ap, now)
        }
        updated()
    } else Row(
        modifier.fillMaxWidth().testTag("dashboard-account-header"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ap != null) Box(Modifier.testTag("header-ap")) { ApRing(ap, 56.dp, 5.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            nameLine(Modifier.fillMaxWidth())
            levelLine()
            if (ap != null) {
                val summary = listOfNotNull(
                    "AP ${ap.current.grouped()} / ${ap.max.grouped()}", apFullText(ap, now),
                ).joinToString(" · ")
                Text(summary, Modifier.testTag("header-ap-detail"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            updated()
        }
    }
}

@Composable
private fun apFullText(ap: DashboardAp, now: Long): String =
    if (ap.recoverAt <= now) stringResource(R.string.capture_ap_full) else stringResource(R.string.dashboard_ap_full_at, clock(ap.recoverAt))

/** The share of AP that is filled; a max of 0 has nothing to fill, and a value past either end is not drawn past it. */
internal fun apFraction(current: Long, max: Long): Float = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f

/**
 * The AP number with its max, the full time and a bar. The full time sits at the end of the number's row when both fit the
 * 192dp of the rail and drops to its own end-aligned line below when they do not, which only the measured widths can tell.
 */
@Composable
private fun HeaderAp(ap: DashboardAp, now: Long) {
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag("header-ap"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Layout(
            content = {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        ap.current.grouped(), Modifier.alignByBaseline().testTag("header-ap-current"), maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        "/ ${ap.max.grouped()} AP", Modifier.alignByBaseline().padding(start = 4.dp).testTag("header-ap-max"), maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = onVariant,
                    )
                }
                Row(Modifier.testTag("header-ap-detail"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ap.recoverAt <= now) {
                        Text(stringResource(R.string.capture_ap_full), maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleSmall)
                    } else {
                        Text(stringResource(R.string.capture_ap_full), maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodySmall, color = onVariant)
                        Text(clock(ap.recoverAt), maxLines = 1, softWrap = false, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0)
            val number = measurables[0].measure(loose)
            val detail = measurables[1].measure(loose)
            val gap = 8.dp.roundToPx()
            val beside = number.width + gap + detail.width <= constraints.maxWidth
            val width = constraints.maxWidth
            val rowHeight = maxOf(number.height, detail.height)
            if (beside) layout(width, rowHeight) {
                number.placeRelative(0, (rowHeight - number.height) / 2)
                detail.placeRelative(width - detail.width, (rowHeight - detail.height) / 2)
            } else layout(width, number.height + detail.height) {
                number.placeRelative(0, 0)
                detail.placeRelative(width - detail.width, number.height)
            }
        }
        val fraction = apFraction(ap.current, ap.max)
        val description = "AP ${ap.current.grouped()} / ${ap.max.grouped()}"
        SegmentedBar(
            listOf(fraction to MaterialTheme.colorScheme.primary),
            Modifier.testTag("header-ap-bar").clearAndSetSemantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        )
    }
}

/** The narrow header's ring: starts at 12 o'clock and runs clockwise; a full AP is a closed ring. One element for a screen reader. */
@Composable
private fun ApRing(ap: DashboardAp, size: Dp, stroke: Dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = MaterialTheme.colorScheme.primary
    val fraction = apFraction(ap.current, ap.max)
    val description = "AP ${ap.current.grouped()} / ${ap.max.grouped()}"
    Box(
        Modifier.size(size).testTag("header-ap-ring").clearAndSetSemantics { contentDescription = description }.drawBehind {
            val width = stroke.toPx()
            val inset = width / 2
            val arc = Size(this.size.width - width, this.size.height - width)
            val style = Stroke(width, cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = style)
            if (fraction > 0f) drawArc(progress, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = style)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                ap.current.grouped(), Modifier.testTag("header-ap-current"),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}
