package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.util.grouped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun AccountDashboardScreen(userId: String, openSettings: () -> Unit, openLogs: () -> Unit,
    vm: AccountDashboardViewModel = hiltViewModel()) {
    val version by remember(vm, userId) { vm.account(userId) }.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val battles by vm.battles.collectAsStateWithLifecycle()
    val outside by OutsideLogRepository.entries.collectAsStateWithLifecycle()
    val open by CaptureTracker.openOutsideEntries.collectAsStateWithLifecycle()
    val battle = remember(battles, userId) { battles.filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    val recentOutside = remember(outside, open, userId) { (outside + open).filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    AccountDashboardContent(version.data, names, version.displayName, openSettings, openLogs, battle, recentOutside)
}

@Composable
internal fun AccountDashboardContent(data: DashboardData?, names: GameNameMap, displayName: String?,
    openSettings: () -> Unit, openLogs: () -> Unit, battle: BattleLogEntry? = null, outside: OutsideLogEntry? = null,
    initialSection: Int = 0) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) } }
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Heading(stringResource(R.string.dashboard_title)) }
                IconButton(onClick = openSettings, modifier = Modifier.testTag("dashboard-settings")) {
                    Icon(Icons.Default.Settings, stringResource(R.string.capture_title))
                }
            }
            if (data == null) Text(stringResource(R.string.dashboard_no_data), Modifier.padding(16.dp))
            else BoxWithConstraints(Modifier.weight(1f)) {
                val sections = buildList<Int> {
                    if (data.profile.isNotEmpty()) add(0)
                    if (data.resources.isNotEmpty() || dashboardAp(data.game, now) != null || data.game?.number("carryOverActPoint") != null || (data.game?.number("commandSpellRecoverAt") ?: 0) > now) add(1)
                    if (data.storage != null) add(2)
                    if (!data.servants.isNullOrEmpty()) add(3)
                    if (!data.inventory.isNullOrEmpty()) add(4)
                    if (!data.equips.isNullOrEmpty()) add(5)
                    if (battle != null || outside != null) add(6)
                }
                val columns = if (maxWidth >= 840.dp) 2 else 1
                val groups = sections.chunked(columns)
                val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                    initialFirstVisibleItemIndex = groups.indexOfFirst { initialSection in it }.coerceAtLeast(0))
                LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { it.first() }) { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            group.forEach { section ->
                                DashboardSection(section, Modifier.weight(1f)) {
                                    when (section) {
                                        0 -> Profile(data, displayName)
                                        1 -> Resources(data, names, now)
                                        2 -> Storage(data.storage!!)
                                        3 -> Servants(data)
                                        4 -> Inventory(data.inventory!!, names)
                                        5 -> MysticCodes(data.equips!!, names)
                                        6 -> Recent(battle, outside, names, openLogs)
                                    }
                                }
                            }
                            if (columns == 2 && group.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSection(section: Int, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val titles = listOf(R.string.dashboard_profile, R.string.dashboard_resources, R.string.dashboard_storage,
        R.string.dashboard_servants, R.string.dashboard_items, R.string.dashboard_mystics, R.string.dashboard_recent)
    Card(modifier.testTag("dashboard-section:$section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(titles[section]), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun date(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

@Composable private fun Field(label: Int, value: String) {
    Text("${stringResource(label)}: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun Profile(data: DashboardData, displayName: String?) {
    val labels = mapOf("lv" to R.string.dashboard_level, "exp" to R.string.dashboard_exp,
        "costMax" to R.string.dashboard_cost, "friendKeep" to R.string.dashboard_friends,
        "createdAt" to R.string.dashboard_created, "friendPoint" to R.string.dashboard_friend_points)
    val gameName = data.profile["name"]
    listOfNotNull(displayName, gameName).distinct().takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(" · "), style = MaterialTheme.typography.titleLarge)
    }
    SuggestionChip(onClick = {}, label = { Text("KR") })
    labels.forEach { (key, label) -> data.profile[key]?.let { value ->
        Field(label, if (key == "createdAt") value.toLongOrNull()?.let(::date) ?: value else value.toLongOrNull()?.grouped() ?: value)
    } }
    data.profile["friendCode"]?.let { code ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${stringResource(R.string.dashboard_friend_code)}: $code", Modifier.weight(1f))
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", code))) } }) {
                Icon(Icons.Default.ContentCopy, stringResource(R.string.dashboard_copy))
            }
        }
    }
    Field(R.string.dashboard_snapshot, date(data.snapshotTime))
    Field(R.string.dashboard_updated, date(data.lastUpdateTime))
}

@Composable private fun Resources(data: DashboardData, names: GameNameMap, now: Long) {
    val game = data.game
    dashboardAp(game, now)?.let { ap ->
        Text("AP ${ap.current.grouped()} / ${ap.max.grouped()}", style = MaterialTheme.typography.titleSmall)
        Text(if (ap.recoverAt <= now) stringResource(R.string.capture_ap_full) else stringResource(R.string.capture_ap_full_at, date(ap.recoverAt)))
    }
    game?.number("carryOverActPoint")?.let { Field(R.string.dashboard_carry_ap, it.grouped()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        data.resources.forEach { (id, value) -> ResourceTile(LogResource(id = id, count = value, split = if (id == 2L) data.freePaid else null), names, signed = false) }
    }
    game?.number("commandSpellRecoverAt")?.takeIf { it > now }?.let { Field(R.string.dashboard_command_spell, date(it)) }
}

@Composable private fun Storage(storage: DashboardStorage) {
    if (storage.total != null) {
        if (storage.servants != null && storage.others != null && storage.equips != null) {
            StorageBar(R.string.dashboard_servants_others, storage.servants + storage.others, storage.servantLimit)
            Field(R.string.dashboard_storage_detail, "${storage.servants} / ${storage.others}")
            StorageBar(R.string.dashboard_ces, storage.equips, storage.equipLimit)
        } else Field(R.string.dashboard_total, storage.total.toString())
    }
    storage.commandCodes?.let { Field(R.string.dashboard_command_codes, it.toString()) }
    storage.secondStorage?.let { Field(R.string.dashboard_second_storage, it.toString()) }
}

@Composable private fun StorageBar(label: Int, count: Int, limit: Long?) {
    Field(label, "$count${limit?.let { " / $it" }.orEmpty()}")
    if (limit != null && limit > 0) LinearProgressIndicator(progress = { (count.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable private fun Servants(data: DashboardData) {
    val servants = data.servants.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ServantSort.Bond) }
    var className by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var classMenu by remember { mutableStateOf(false) }
    Text(if (data.bondTen != null && data.bondFifteen != null) stringResource(R.string.dashboard_bond_summary, servants.size, data.bondTen, data.bondFifteen)
        else stringResource(R.string.dashboard_owned_count, servants.size))
    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.dashboard_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ServantSort.entries.forEach { option ->
            FilterChip(selected = sort == option, onClick = { sort = option }, label = {
                Text(stringResource(when (option) { ServantSort.Bond -> R.string.dashboard_sort_bond; ServantSort.Rarity -> R.string.dashboard_sort_rarity; ServantSort.Collection -> R.string.dashboard_sort_collection }))
            })
        }
        Box {
            OutlinedButton(onClick = { classMenu = true }) { Text(className?.let { dashboardClass(it) } ?: stringResource(R.string.dashboard_all_classes)) }
            DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_all_classes)) }, onClick = { className = null; classMenu = false })
                data.servantClasses.forEach { name ->
                    DropdownMenuItem(text = { Text(dashboardClass(name)) }, onClick = { className = name; classMenu = false })
                }
            }
        }
    }
    var selected by remember { mutableStateOf(emptyList<DashboardServant>()) }
    LaunchedEffect(servants, query, sort, className) { selected = withContext(Dispatchers.Default) { servants.selectServants(query, sort, className) } }
    val shown = if (expanded) selected else selected.take(20)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("dashboard-servant-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.id }) { servant ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AtlasIcon("servants", servant.id, servant.atlas?.icon)
                Column(Modifier.weight(1f)) {
                    Text(servant.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(servant.atlas?.className?.let { dashboardClass(it) }, servant.atlas?.rarity?.let { "★$it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    val parts = buildList {
                        servant.bond?.let { add(stringResource(R.string.dashboard_bond, it)) }
                        servant.np?.let { add(stringResource(R.string.dashboard_np, it)) }
                        servant.maxLevel?.let { add(stringResource(R.string.dashboard_max_level, it)) }
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (servant.skills.any { it != null }) Text(stringResource(R.string.dashboard_skills, servant.skills.joinToString(" / ") { it?.toString() ?: "—" }), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (!expanded && selected.size > 20) TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.dashboard_show_all, selected.size)) }
}

@Composable private fun Inventory(items: List<DashboardItem>, names: GameNameMap) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.dashboard_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    var groups by remember { mutableStateOf(emptyMap<InventoryGroup, List<DashboardItem>>()) }
    LaunchedEffect(items, query, names) { groups = withContext(Dispatchers.Default) {
        items.filter { (names.items[it.id] ?: "#${it.id}").contains(query, ignoreCase = true) }.groupBy { it.group }
    } }
    InventoryGroup.entries.forEach { group ->
        val rows = groups[group].orEmpty()
        if (rows.isNotEmpty()) key(group) {
            var expanded by rememberSaveable { mutableStateOf(group !in setOf(InventoryGroup.Event, InventoryGroup.Other)) }
            TextButton(onClick = { expanded = !expanded }) {
                Text("${if (expanded) "−" else "+"} ${stringResource(group.label())} (${rows.size})")
            }
            if (expanded) LazyVerticalGrid(GridCells.Adaptive(144.dp), Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { ResourceTile(LogResource(id = it.id, count = it.count), names, signed = false) }
            }
        }
    }
}

private fun InventoryGroup.label() = when (this) {
    InventoryGroup.Materials -> R.string.dashboard_group_materials
    InventoryGroup.Gems -> R.string.dashboard_group_gems
    InventoryGroup.Pieces -> R.string.dashboard_group_pieces
    InventoryGroup.Grails -> R.string.dashboard_group_grails
    InventoryGroup.Event -> R.string.dashboard_group_event
    InventoryGroup.Other -> R.string.dashboard_group_other
}

@Composable private fun MysticCodes(equips: List<DashboardEquip>, names: GameNameMap) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(equips, key = { it.id }) { equip ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val metadata = names.mysticData[equip.id]
            AtlasIcon("mystics", equip.id, metadata?.icon)
            Column(Modifier.weight(1f)) {
                Text(metadata?.name ?: "#${equip.id}")
                equip.level?.let { Text(stringResource(R.string.dashboard_level_value, it)) }
                if (equip.equipped) Text(stringResource(R.string.dashboard_equipped), style = MaterialTheme.typography.labelSmall)
            }
        }
        }
    }
}

@Composable private fun Recent(battle: BattleLogEntry?, outside: OutsideLogEntry?, names: GameNameMap, openLogs: () -> Unit) {
    battle?.let {
        Text("${date(it.endedAt / 1000)} · ${it.configName}")
        it.gains?.battles?.maxByOrNull { row -> row.time }?.questId?.let { id ->
            Text(stringResource(R.string.dashboard_quest, id), style = MaterialTheme.typography.bodySmall)
        }
        val gains = it.gains?.let(::battleResources).orEmpty()
        Text(if (gains.isEmpty()) it.reasonText else gainLine(gains, names), style = MaterialTheme.typography.bodySmall)
    }
    outside?.let {
        Text("${date(it.startedAt / 1000)} – ${date(it.endedAt / 1000)}")
        Text(gainLine(outsideResources(it.period.total, it.period.fruitUsed), names), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = openLogs, modifier = Modifier.testTag("dashboard-logs")) { Text(stringResource(R.string.p_logs)) }
}

@Composable private fun gainLine(resources: List<LogResource>, names: GameNameMap): String {
    val parts = mutableListOf<String>()
    for (resource in resources.take(3)) parts += "${resourceName(resource.type, resource.id, names)} ${resource.count?.let(::signedOutsideCount) ?: "—"}"
    return parts.joinToString(" · ")
}

/** Per-kind directories prevent mystic code IDs colliding with item IDs. */
@Composable private fun AtlasIcon(kind: String, id: Long, url: String?) {
    val context = LocalContext.current
    val store = remember(context, kind) { LogIconLoader.files(context, kind) }
    val loader = LocalLogIconLoader.current ?: LogIconLoader.get(context)
    var file by remember(kind, id, url) { mutableStateOf<File?>(null) }
    LaunchedEffect(kind, id, url) { file = store.resolve(id, url) }
    if (file != null) AsyncImage(file!!.toURI().toString(), contentDescription = null, imageLoader = loader, modifier = Modifier.size(40.dp))
}

@Composable private fun dashboardClass(name: String): String {
    val resource = when (name) {
        "saber" -> R.string.p_support_class_saber; "archer" -> R.string.p_support_class_archer
        "lancer" -> R.string.p_support_class_lancer; "rider" -> R.string.p_support_class_rider
        "caster" -> R.string.p_support_class_caster; "assassin" -> R.string.p_support_class_assassin
        "berserker" -> R.string.p_support_class_berserker; "shielder" -> R.string.portrait_class_shielder
        "ruler" -> R.string.portrait_class_ruler; "avenger" -> R.string.portrait_class_avenger
        "moonCancer" -> R.string.portrait_class_mooncancer; "alterEgo" -> R.string.portrait_class_alterego
        "foreigner" -> R.string.portrait_class_foreigner; "pretender" -> R.string.portrait_class_pretender
        else -> if (name.contains("beast", true)) R.string.portrait_class_beast else null
    }
    return resource?.let { stringResource(it) } ?: name
}
