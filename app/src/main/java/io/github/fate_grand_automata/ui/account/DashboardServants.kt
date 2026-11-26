package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import io.github.fate_grand_automata.util.grouped
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

/** "2 / 5": filled slots of the unlocked ones. Null for a servant without a command code row. */
internal fun DashboardServant.codesText(): String? = commandCodes?.let { "${it.filled} / ${it.unlocked}" }

/** "+1,000 (2)": the summed enhancement and the number of enhanced cards. Null when nothing is enhanced. */
internal fun DashboardServant.boostText(): String? = cardBoost?.let { "+${it.sum.grouped()} (${it.cards})" }

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

/** The face, the seven sortable columns and the gaps take 508dp; this leaves the name column at least 170dp. */
private val TABLE_MIN_WIDTH = 680.dp
/** Below this the two extra columns (command codes, card boost) do not leave the name enough room and join the class line. */
private val EXTRA_COLUMNS_MIN_WIDTH = 880.dp
private val CodesWidth = 52.dp
private val BoostWidth = 92.dp
private val FaceSize = 40.dp
private val CostWidth = 52.dp
private val LevelWidth = 68.dp
private val BondWidth = 52.dp
private val NpWidth = 48.dp
private val HeaderHeight = 48.dp
private val SkillsWidth = 76.dp
private val AppendsWidth = 116.dp

private val numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun ColumnScope.Servants(data: DashboardData) {
    val servants = data.servants.orEmpty()
    var sort by rememberSaveable { mutableStateOf(ServantSort.Bond) }
    var descending by rememberSaveable { mutableStateOf(ServantSort.Bond.firstTapDescending) }
    val onSort = { column: ServantSort ->
        if (column == sort) descending = !descending else { sort = column; descending = column.firstTapDescending }
    }
    var classLabel by rememberSaveable { mutableStateOf<String?>(null) }
    val labels = data.servantClasses.associateWith { dashboardClass(it) }
    val classes = classGroups(data.servantClasses) { labels.getValue(it) }
    val selectedClasses = classes[classLabel]?.toSet()
    var selected by remember { mutableStateOf(emptyList<DashboardServant>()) }
    LaunchedEffect(servants, sort, descending, selectedClasses) { selected = withContext(Dispatchers.Default) { servants.selectServants(sort, descending, selectedClasses) } }
    val summary = remember(servants, data.servantCatalog, selectedClasses) { servantSummary(servants, data.servantCatalog, selectedClasses) }
    val scheme = MaterialTheme.colorScheme

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val table = maxWidth >= TABLE_MIN_WIDTH
        // The columns are only worth their width when some servant has a value for them.
        val extraColumns = maxWidth >= EXTRA_COLUMNS_MIN_WIDTH && servants.any { it.commandCodes != null || it.cardBoost != null }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ServantSummaryLine(summary, Modifier)
            // The wide table sorts through its header cells; the narrow rows have no header, so they keep a chip row.
            if (!table) SortChips(sort, descending, onSort)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("dashboard-class-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = classLabel == null, onClick = { classLabel = null }, modifier = Modifier.testTag("dashboard-class:all"),
                    label = { Text(stringResource(R.string.dashboard_filter_all)) })
                classes.keys.forEach { label ->
                    FilterChip(selected = classLabel == label, onClick = { classLabel = label }, modifier = Modifier.testTag("dashboard-class:$label"), label = { Text(label) })
                }
            }
            if (table) TableHeader(extraColumns, sort, descending, onSort)
            HorizontalDivider(color = scheme.outlineVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("dashboard-servant-list")) {
                items(selected, key = { it.id }) { servant ->
                    if (table) TableRow(servant, extraColumns) else NarrowRow(servant)
                    HorizontalDivider(color = scheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ServantSort.label(): String = stringResource(when (this) {
    ServantSort.Name -> R.string.dashboard_col_name
    ServantSort.Cost -> R.string.dashboard_sort_cost
    ServantSort.Level -> R.string.dashboard_sort_level
    ServantSort.Bond -> R.string.dashboard_sort_bond
    ServantSort.Np -> R.string.dashboard_col_np
    ServantSort.Skills -> R.string.dashboard_col_skills
    ServantSort.Appends -> R.string.dashboard_col_appends
})

/** The column and, for the active one, its direction; this is what a screen reader announces. */
@Composable
private fun sortDescription(column: ServantSort, active: Boolean, descending: Boolean): String = when {
    !active -> stringResource(R.string.dashboard_sort_by, column.label())
    descending -> stringResource(R.string.dashboard_sorted_desc, column.label())
    else -> stringResource(R.string.dashboard_sorted_asc, column.label())
}

@Composable
private fun SortArrow(descending: Boolean, tint: Color) {
    Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, Modifier.size(14.dp).testTag("dashboard-sort-arrow"), tint = tint)
}

/** One tappable header cell. The arrow sits before the text of right-aligned cells so the text keeps lining up with the numbers below. */
@Composable
private fun HeaderCell(column: ServantSort, active: Boolean, descending: Boolean, onSort: (ServantSort) -> Unit, modifier: Modifier) {
    val alignEnd = column != ServantSort.Name
    val color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val description = sortDescription(column, active, descending)
    Row(
        modifier.heightIn(min = HeaderHeight).testTag("dashboard-sort:${column.name}")
            .semantics(mergeDescendants = true) { contentDescription = description; selected = active; role = Role.Button }
            .clickable { onSort(column) },
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active && alignEnd) SortArrow(descending, color)
        Text(column.label(), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
        if (active && !alignEnd) SortArrow(descending, color)
    }
}

/** The narrow layout's stand-in for the header: the same seven columns, the active chip shows the arrow, tapping it again reverses. */
@Composable
private fun SortChips(sort: ServantSort, descending: Boolean, onSort: (ServantSort) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("dashboard-sort-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ServantSort.entries.forEach { column ->
            val active = sort == column
            val description = sortDescription(column, active, descending)
            FilterChip(
                selected = active, onClick = { onSort(column) },
                modifier = Modifier.testTag("dashboard-sort:${column.name}").semantics { contentDescription = description },
                label = { Text(column.label()) },
                trailingIcon = if (active) ({ SortArrow(descending, MaterialTheme.colorScheme.onSecondaryContainer) }) else null,
            )
        }
    }
}

@Composable
private fun TableHeader(extraColumns: Boolean, sort: ServantSort, descending: Boolean, onSort: (ServantSort) -> Unit) {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable fun cell(column: ServantSort, modifier: Modifier) = HeaderCell(column, sort == column, descending, onSort, modifier)
    Row(Modifier.fillMaxWidth().testTag("dashboard-servant-header"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(FaceSize))
        cell(ServantSort.Name, Modifier.weight(1f))
        cell(ServantSort.Cost, Modifier.width(CostWidth))
        cell(ServantSort.Level, Modifier.width(LevelWidth))
        cell(ServantSort.Bond, Modifier.width(BondWidth))
        cell(ServantSort.Np, Modifier.width(NpWidth))
        cell(ServantSort.Skills, Modifier.width(SkillsWidth))
        cell(ServantSort.Appends, Modifier.width(AppendsWidth))
        if (extraColumns) {
            Text(stringResource(R.string.dashboard_col_codes), Modifier.width(CodesWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
            Text(stringResource(R.string.dashboard_col_card_boost), Modifier.width(BoostWidth), style = style, color = color, textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

@Composable
private fun classAndRarity(servant: DashboardServant): String = listOfNotNull(
    servant.atlas?.className?.let { dashboardClass(it) },
    servant.atlas?.rarity?.let { "★$it" },
).joinToString(" · ")

@Composable
private fun TableRow(servant: DashboardServant, extraColumns: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyMedium.merge(numeric)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("dashboard-servant-row"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AtlasIcon("servants", servant.id, servant.atlas?.icon, size = FaceSize)
        Column(Modifier.weight(1f)) {
            Text(servant.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = (listOf(classAndRarity(servant)) + if (extraColumns) emptyList() else servantExtras(servant)).filter { it.isNotEmpty() }.joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, Modifier.testTag("dashboard-servant-subline"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(servant.cost?.toString() ?: LOCKED_TEXT, Modifier.width(CostWidth).testTag("dashboard-servant-cost"), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(levelText(servant.level, servant.maxLevel), Modifier.width(LevelWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(servant.bond?.toString() ?: LOCKED_TEXT, Modifier.width(BondWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(servant.np?.toString() ?: LOCKED_TEXT, Modifier.width(NpWidth), style = body, textAlign = TextAlign.End, maxLines = 1)
        Text(annotatedCells(servant.skillCells(), scheme), Modifier.width(SkillsWidth).testTag("dashboard-servant-skills"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        Text(annotatedCells(servant.appendCells(), scheme), Modifier.width(AppendsWidth).testTag("dashboard-servant-appends"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        if (extraColumns) {
            Text(servant.codesText() ?: "", Modifier.width(CodesWidth).testTag("dashboard-servant-codes"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
            Text(servant.boostText() ?: "", Modifier.width(BoostWidth).testTag("dashboard-servant-boost"), style = body, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
        }
    }
}

/** Command codes and card boost as the sentences of the narrow rows; a part without data is left out. */
@Composable
private fun servantExtras(servant: DashboardServant): List<String> = listOfNotNull(
    servant.commandCodes?.let { stringResource(R.string.dashboard_servant_codes, it.filled, it.unlocked) },
    servant.cardBoost?.let { stringResource(R.string.dashboard_servant_card_boost, it.sum.grouped(), it.cards) },
)

/** Three lines, because two do not fit at 360dp: face, name and class; then the Lv, bond and NP; then skills and appends. A fourth line holds command codes and card boost. */
@Composable
private fun NarrowRow(servant: DashboardServant) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val stats = joinSegments(listOf(
        AnnotatedString(stringResource(R.string.dashboard_sort_cost) + " " + (servant.cost?.toString() ?: LOCKED_TEXT)),
        AnnotatedString(stringResource(R.string.dashboard_sort_level) + " " + levelText(servant.level, servant.maxLevel)),
        AnnotatedString(stringResource(R.string.dashboard_sort_bond) + " " + (servant.bond?.toString() ?: LOCKED_TEXT)),
        AnnotatedString(stringResource(R.string.dashboard_col_np) + " " + (servant.np?.toString() ?: LOCKED_TEXT)),
    ), muted)
    val levels = joinSegments(listOf(
        buildAnnotatedString { append(stringResource(R.string.dashboard_col_skills) + " "); append(annotatedCells(servant.skillCells(), scheme)) },
        buildAnnotatedString { append(stringResource(R.string.dashboard_col_appends) + " "); append(annotatedCells(servant.appendCells(), scheme)) },
    ), muted)
    val extras = servantExtras(servant).joinToString(" · ")
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
            // Command codes and card boost do not fit the third line at 360dp, so servants that have them get a fourth.
            if (extras.isNotEmpty()) Text(extras, Modifier.testTag("dashboard-servant-extras"), style = detail, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
