package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Servants section. Its content and helpers stay inside this file so the servant rewrite touches nothing else. */
@Composable
internal fun DashboardServantsSection(data: DashboardData) {
    // The list is lazy and fills the pane, so the pane must not scroll this section (see SectionPane).
    DashboardBlock(DashboardBlockId.Servants, stringResource(R.string.dashboard_servants), Modifier.fillMaxSize()) { Servants(data) }
}

internal enum class CellKind { Normal, Maxed, Locked }

internal data class ServantCell(val text: String, val kind: CellKind)

private const val MAX_LEVEL = 10L
private const val LOCKED_TEXT = "–"

internal fun skillCell(value: Long?): ServantCell = when {
    value == null -> ServantCell(LOCKED_TEXT, CellKind.Locked)
    value >= MAX_LEVEL -> ServantCell(value.toString(), CellKind.Maxed)
    else -> ServantCell(value.toString(), CellKind.Normal)
}

/** An append that is not unlocked is stored as null or 0; both read as "not unlocked". */
internal fun appendCell(value: Long?): ServantCell = if (value == null || value == 0L) ServantCell(LOCKED_TEXT, CellKind.Locked) else skillCell(value)

internal fun DashboardServant.skillCells() = (0..2).map { skillCell(skills.getOrNull(it)) }
internal fun DashboardServant.appendCells() = (0..4).map { appendCell(appends.getOrNull(it)) }

/** Gold marks maxed values only; a locked value is muted. */
internal fun CellKind.color(scheme: ColorScheme): Color = when (this) {
    CellKind.Maxed -> scheme.tertiary
    CellKind.Locked -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
    CellKind.Normal -> scheme.onSurface
}

internal fun annotatedCells(cells: List<ServantCell>, scheme: ColorScheme): AnnotatedString = buildAnnotatedString {
    cells.forEachIndexed { index, cell ->
        if (index > 0) withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append("/") }
        withStyle(SpanStyle(color = cell.kind.color(scheme))) { append(cell.text) }
    }
}

internal fun joinSegments(parts: List<AnnotatedString>, separatorColor: Color): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) withStyle(SpanStyle(color = separatorColor)) { append(" · ") }
        append(part)
    }
}

internal fun levelText(level: Long?, maxLevel: Long?): String = when {
    level == null && maxLevel == null -> LOCKED_TEXT
    else -> "${level ?: LOCKED_TEXT}/${maxLevel ?: LOCKED_TEXT}"
}

/** Owned / total with its percentage, then the two NP sums. On narrow screens the line wraps between the parts, never inside one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServantSummaryLine(summary: ServantSummary, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    fun part(label: String, value: String, suffix: String = "") = buildAnnotatedString {
        append("$label ")
        withStyle(bold) { append(value) }
        append(suffix)
    }
    val owned = part(
        stringResource(R.string.dashboard_stat_owned),
        summary.total?.let { "${summary.owned} / $it" } ?: summary.owned.toString(),
        summary.percent?.let { " ($it%)" }.orEmpty(),
    )
    val parts = listOf(
        "dashboard-servant-owned" to owned,
        "dashboard-servant-star5-np" to part(stringResource(R.string.dashboard_stat_star5_np), summary.star5Np.toString()),
        "dashboard-servant-all-np" to part(stringResource(R.string.dashboard_stat_all_np), summary.allNp.toString()),
    )
    FlowRow(modifier.testTag("dashboard-servant-count"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEachIndexed { index, (tag, text) ->
            Text(
                if (index < parts.lastIndex) buildAnnotatedString { append(text); withStyle(SpanStyle(color = muted)) { append(" · ") } } else text,
                Modifier.testTag(tag), style = MaterialTheme.typography.titleSmall.merge(numeric), softWrap = false,
            )
        }
    }
}

/** The game's class order. Beast classes and anything unknown follow, alphabetically. */
private val CLASS_ORDER = listOf(
    "saber", "archer", "lancer", "rider", "caster", "assassin", "berserker", "shielder",
    "ruler", "alterEgo", "avenger", "moonCancer", "foreigner", "pretender",
)

/** Class chips deduplicated by the displayed label, so beast variants and raw fallback names share one chip. */
internal fun classGroups(servantClasses: List<String>, label: (String) -> String): Map<String, Set<String>> {
    val ordered = servantClasses.sortedWith(compareBy<String> { CLASS_ORDER.indexOf(it).let { i -> if (i < 0) CLASS_ORDER.size else i } }.thenBy { it })
    val groups = linkedMapOf<String, MutableSet<String>>()
    ordered.forEach { name -> groups.getOrPut(label(name)) { linkedSetOf() }.add(name) }
    return groups
}

private val TABLE_MIN_WIDTH = 600.dp
private val FaceSize = 40.dp
private val LevelWidth = 76.dp
private val BondWidth = 44.dp
private val NpWidth = 36.dp
private val SkillsWidth = 76.dp
private val AppendsWidth = 116.dp

private val numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun ColumnScope.Servants(data: DashboardData) {
    val servants = data.servants.orEmpty()
    var sort by rememberSaveable { mutableStateOf(ServantSort.Bond) }
    var classLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = data.servantClasses.associateWith { dashboardClass(it) }
    val classes = classGroups(data.servantClasses) { labels.getValue(it) }
    val selectedClasses = classes[classLabel]?.toSet()
    var selected by remember { mutableStateOf(emptyList<DashboardServant>()) }
    LaunchedEffect(servants, sort, selectedClasses) { selected = withContext(Dispatchers.Default) { servants.selectServants(sort, selectedClasses) } }
    val summary = remember(servants, data.servantCatalog, selectedClasses) { servantSummary(servants, data.servantCatalog, selectedClasses) }
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val table = maxWidth >= TABLE_MIN_WIDTH
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sortChips = @Composable {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServantSort.entries.forEach { option ->
                        FilterChip(selected = sort == option, onClick = { sort = option }, modifier = Modifier.testTag("dashboard-sort:${option.name}"), label = {
                            Text(stringResource(when (option) { ServantSort.Bond -> R.string.dashboard_sort_bond; ServantSort.Cost -> R.string.dashboard_sort_cost; ServantSort.Level -> R.string.dashboard_sort_level }))
                        })
                    }
                }
            }
            if (table) Row(verticalAlignment = Alignment.CenterVertically) {
                ServantSummaryLine(summary, Modifier.weight(1f))
                sortChips()
            } else {
                ServantSummaryLine(summary, Modifier)
                sortChips()
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("dashboard-class-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = classLabel == null, onClick = { classLabel = null }, modifier = Modifier.testTag("dashboard-class:all"),
                    label = { Text(stringResource(R.string.dashboard_filter_all)) })
                classes.keys.forEach { label ->
                    FilterChip(selected = classLabel == label, onClick = { classLabel = label }, modifier = Modifier.testTag("dashboard-class:$label"), label = { Text(label) })
                }
            }
            if (table) TableHeader()
            HorizontalDivider(color = scheme.outlineVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("dashboard-servant-list")) {
                items(selected, key = { it.id }) { servant ->
                    if (table) TableRow(servant) else NarrowRow(servant)
                    HorizontalDivider(color = scheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().testTag("dashboard-servant-header"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(FaceSize))
        Text(stringResource(R.string.dashboard_col_name), Modifier.weight(1f), style = style, color = color)
        Text(stringResource(R.string.dashboard_sort_level), Modifier.width(LevelWidth), style = style, color = color, textAlign = TextAlign.End)
        Text(stringResource(R.string.dashboard_sort_bond), Modifier.width(BondWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
        Text(stringResource(R.string.dashboard_col_np), Modifier.width(NpWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
        Text(stringResource(R.string.dashboard_col_skills), Modifier.width(SkillsWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
        Text(stringResource(R.string.dashboard_col_appends), Modifier.width(AppendsWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun classAndRarity(servant: DashboardServant): String = listOfNotNull(
    servant.atlas?.className?.let { dashboardClass(it) },
    servant.atlas?.rarity?.let { "★$it" },
).joinToString(" · ")

@Composable
private fun TableRow(servant: DashboardServant) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium.merge(numeric)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("dashboard-servant-row"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AtlasIcon("servants", servant.id, servant.atlas?.icon, size = FaceSize)
        Column(Modifier.weight(1f)) {
            Text(servant.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            classAndRarity(servant).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(levelText(servant.level, servant.maxLevel), Modifier.width(LevelWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(servant.bond?.toString() ?: LOCKED_TEXT, Modifier.width(BondWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(servant.np?.toString() ?: LOCKED_TEXT, Modifier.width(NpWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(annotatedCells(servant.skillCells(), scheme), Modifier.width(SkillsWidth).testTag("dashboard-servant-skills"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        Text(annotatedCells(servant.appendCells(), scheme), Modifier.width(AppendsWidth).testTag("dashboard-servant-appends"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

/** Three lines, because two do not fit at 360dp: face, name and class; then the Lv, bond and NP; then skills and appends. */
@Composable
private fun NarrowRow(servant: DashboardServant) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val stats = joinSegments(listOf(
        AnnotatedString(stringResource(R.string.dashboard_sort_level) + " " + levelText(servant.level, servant.maxLevel)),
        AnnotatedString(stringResource(R.string.dashboard_sort_bond) + " " + (servant.bond?.toString() ?: LOCKED_TEXT)),
        AnnotatedString(stringResource(R.string.dashboard_col_np) + " " + (servant.np?.toString() ?: LOCKED_TEXT)),
    ), muted)
    val levels = joinSegments(listOf(
        buildAnnotatedString { append(stringResource(R.string.dashboard_col_skills) + " "); append(annotatedCells(servant.skillCells(), scheme)) },
        buildAnnotatedString { append(stringResource(R.string.dashboard_col_appends) + " "); append(annotatedCells(servant.appendCells(), scheme)) },
    ), muted)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp).testTag("dashboard-servant-row"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AtlasIcon("servants", servant.id, servant.atlas?.icon, size = FaceSize)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(servant.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                classAndRarity(servant).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, softWrap = false)
                }
            }
            val detail = MaterialTheme.typography.bodySmall.merge(numeric)
            Text(stats, Modifier.testTag("dashboard-servant-detail"), style = detail, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(levels, Modifier.testTag("dashboard-servant-levels"), style = detail, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
