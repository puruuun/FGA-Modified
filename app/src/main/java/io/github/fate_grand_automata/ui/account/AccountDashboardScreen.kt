package io.github.fate_grand_automata.ui.account

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.rememberChaldeaAccountExport
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
fun AccountDashboardScreen(userId: String, openSettings: () -> Unit, openLogs: () -> Unit, openFriends: (String) -> Unit = {},
    vm: AccountDashboardViewModel = hiltViewModel()) {
    val version by remember(vm, userId) { vm.account(userId) }.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val battles by vm.battles.collectAsStateWithLifecycle()
    val outside by OutsideLogRepository.entries.collectAsStateWithLifecycle()
    val open by CaptureTracker.openOutsideEntries.collectAsStateWithLifecycle()
    val battle = remember(battles, userId) { battles.filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    val recentOutside = remember(outside, open, userId) { (outside + open).filter { it.accountId == userId }.maxByOrNull { it.endedAt } }
    val export = rememberChaldeaAccountExport()
    AccountDashboardContent(version.data, names, version.displayName, openSettings, openLogs, battle, recentOutside,
        userId = userId, exportAccount = export, friendCount = version.friendCount, openFriends = openFriends,
        server = version.server, loading = version.loading)
}

@Composable
internal fun AccountDashboardContent(data: DashboardData?, names: GameNameMap, displayName: String?,
    openSettings: () -> Unit, openLogs: () -> Unit, battle: BattleLogEntry? = null, outside: OutsideLogEntry? = null,
    initialSection: Int = 0, userId: String = "", exportAccount: (String?) -> Unit = {}, friendCount: Int? = null,
    openFriends: (String) -> Unit = {}, server: String = "KR", loading: Boolean = false) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) } }
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Heading(stringResource(R.string.dashboard_title)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    IconButton(onClick = { exportAccount(userId) }, modifier = Modifier.testTag("dashboard-export")) {
                        Icon(Icons.Default.FileUpload, stringResource(R.string.capture_chaldea_create))
                    }
                }
                if (friendCount != null) {
                    IconButton(onClick = { openFriends(userId) }, modifier = Modifier.testTag("dashboard-friends")) {
                        Icon(Icons.Default.People, stringResource(R.string.friends_title))
                    }
                }
                IconButton(onClick = openSettings, modifier = Modifier.testTag("dashboard-settings")) {
                    Icon(Icons.Default.Settings, stringResource(R.string.capture_title))
                }
            }
            if (loading && data == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("dashboard-loading"))
                }
            } else if (data == null) Text(stringResource(R.string.dashboard_no_data), Modifier.padding(16.dp))
            else BoxWithConstraints(Modifier.weight(1f)) {
                val sections = buildList<Int> {
                    if (data.profile.isNotEmpty()) add(0)
                    if (data.resources.isNotEmpty() || data.teapots != null || dashboardAp(data.game, now) != null || data.game?.number("carryOverActPoint") != null || (data.game?.number("commandSpellRecoverAt") ?: 0) > now) add(1)
                    if (data.storage != null) add(2)
                    if (!data.servants.isNullOrEmpty()) add(3)
                    if (!data.inventory.isNullOrEmpty()) add(4)
                    if (data.presentBox != null) add(10)
                    add(7)
                    if (data.formations?.grand?.isNotEmpty() == true) add(8)
                    if (!data.equips.isNullOrEmpty()) add(5)
                    if (data.commandCodes != null) add(12)
                    if (!data.classBoards.isNullOrEmpty()) add(13)
                    if (data.summons != null) add(14)
                    if (battle != null || outside != null) add(6)
                }
                val target = remember { BringIntoViewRequester() }
                LaunchedEffect(initialSection) {
                    withFrameNanos { }
                    target.bringIntoView()
                }
                val sectionContent: @Composable (Int) -> Unit = { section ->
                    key(section) {
                        DashboardSection(section, sectionTitle(section, data), Modifier.fillMaxWidth().semantics {
                            isTraversalGroup = true
                            traversalIndex = sections.indexOf(section).toFloat()
                        }.then(
                            if (section == initialSection) Modifier.bringIntoViewRequester(target) else Modifier)) {
                            when (section) {
                                0 -> Profile(data, displayName, friendCount, server)
                                1 -> Resources(data, names, now)
                                2 -> Storage(data.storage!!)
                                3 -> Servants(data)
                                4 -> Inventory(data.inventory!!, names)
                                5 -> MysticCodes(data.equips!!, names)
                                6 -> Recent(battle, outside, names, openLogs)
                                7 -> SupportFormation(data.formations, names)
                                8 -> GrandFormation(data.formations!!.grand, names)
                                10 -> PresentBox(data.presentBox!!, names)
                                12 -> CommandCodeCollection(data.commandCodes!!, names)
                                13 -> ClassScore(data.classBoards!!)
                                14 -> SummonHistory(data.summons!!)
                            }
                        }
                    }
                }
                // One page scroll, with bounded lazy lists inside each card.
                val page = Modifier.fillMaxSize().testTag("dashboard-page").verticalScroll(rememberScrollState()).padding(16.dp)
                DashboardMasonry(wide = maxWidth >= 840.dp, modifier = page) {
                    sections.forEach { sectionContent(it) }
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(section: Int, data: DashboardData): String = when (section) {
    10 -> stringResource(R.string.dashboard_present_box_header, data.presentBox?.totalCount ?: 0)
    else -> {
        val titles = mapOf(
            0 to R.string.dashboard_profile,
            1 to R.string.dashboard_resources,
            2 to R.string.dashboard_storage,
            3 to R.string.dashboard_servants,
            4 to R.string.dashboard_items,
            5 to R.string.dashboard_mystics,
            6 to R.string.dashboard_recent,
            7 to R.string.dashboard_formation_support,
            8 to R.string.dashboard_formation_grand,
            12 to R.string.dashboard_command_code_collection,
            13 to R.string.dashboard_class_score,
            14 to R.string.dashboard_summon_history,
        )
        stringResource(titles.getValue(section))
    }
}

@Composable
private fun DashboardSection(section: Int, title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.testTag("dashboard-section:$section")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

internal fun date(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

@Composable private fun Field(label: Int, value: String) {
    Text("${stringResource(label)}: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun Profile(data: DashboardData, displayName: String?, friendCount: Int?, server: String) {
    val labels = mapOf("lv" to R.string.dashboard_level, "exp" to R.string.dashboard_exp,
        "costMax" to R.string.dashboard_cost, "friendKeep" to R.string.dashboard_friends,
        "createdAt" to R.string.dashboard_created, "friendPoint" to R.string.dashboard_friend_points)
    val gameName = data.profile["name"]
    listOfNotNull(displayName, gameName).distinct().takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(" · "), style = MaterialTheme.typography.titleLarge)
    }
    SuggestionChip(onClick = {}, label = { Text(server) })
    labels.forEach { (key, label) -> data.profile[key]?.let { value ->
        Field(label, if (key == "createdAt") value.toLongOrNull()?.let(::date) ?: value
            else if (key == "friendKeep") friendsText(value, friendCount) else value.toLongOrNull()?.grouped() ?: value)
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
        data.teapots?.let { ResourceTile(LogResource(id = it.iconId, count = it.total), names, signed = false, label = stringResource(R.string.dashboard_teapots)) }
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

@Composable internal fun Servants(data: DashboardData) {
    val servants = data.servants.orEmpty()
    var sort by rememberSaveable { mutableStateOf(ServantSort.Bond) }
    var classLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var classMenu by remember { mutableStateOf(false) }
    // Deduplicate by the displayed label, including future beast variants and raw fallbacks.
    val classes = linkedMapOf<String, MutableSet<String>>()
    data.servantClasses.forEach { name -> classes.getOrPut(dashboardClass(name)) { linkedSetOf() }.add(name) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ServantSort.entries.forEach { option ->
            FilterChip(selected = sort == option, onClick = { sort = option }, label = {
                Text(stringResource(when (option) { ServantSort.Bond -> R.string.dashboard_sort_bond; ServantSort.Cost -> R.string.dashboard_sort_cost; ServantSort.Level -> R.string.dashboard_sort_level }))
            })
        }
        Box {
            OutlinedButton(onClick = { classMenu = true }) { Text(classLabel ?: stringResource(R.string.dashboard_all_classes)) }
            DropdownMenu(expanded = classMenu, onDismissRequest = { classMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_all_classes)) }, onClick = { classLabel = null; classMenu = false })
                classes.keys.forEach { label ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { classLabel = label; classMenu = false })
                }
            }
        }
    }
    var selected by remember { mutableStateOf(emptyList<DashboardServant>()) }
    val selectedClasses = classes[classLabel]?.toSet()
    LaunchedEffect(servants, sort, selectedClasses) { selected = withContext(Dispatchers.Default) { servants.selectServants(sort, selectedClasses) } }
    val shown = if (expanded) selected else selected.take(5)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (expanded) 560.dp else 280.dp).testTag("dashboard-servant-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(shown, key = { it.id }) { servant ->
            Row(Modifier.heightIn(min = 48.dp).testTag("dashboard-servant-row"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AtlasIcon("servants", servant.id, servant.atlas?.icon)
                Column(Modifier.weight(1f)) {
                    val parts = buildList {
                        servant.atlas?.className?.let { add(dashboardClass(it)) }
                        servant.atlas?.rarity?.let { add("★$it") }
                        servant.level?.let { add(stringResource(R.string.friends2_level_compact, it)) }
                        servant.bond?.let { add(stringResource(R.string.dashboard_bond, it)) }
                        servant.np?.let { add(stringResource(R.string.friends2_np_compact, it)) }
                    }
                    Text(buildAnnotatedString {
                        append(servant.name)
                        if (parts.isNotEmpty()) withStyle(MaterialTheme.typography.labelSmall.toSpanStyle()) {
                            append("  "); append(parts.joinToString(" · "))
                        }
                    }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.friends2_dashboard_skills,
                        (0..2).joinToString("/") { servant.skills.getOrNull(it)?.toString() ?: "–" },
                        (0..4).joinToString("/") { servant.appends.getOrNull(it)?.toString() ?: "–" }), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (expanded || selected.size > 5) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-servants-toggle")) {
        Text(if (expanded) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, selected.size))
    }
}

@Composable private fun Inventory(items: List<DashboardItem>, names: GameNameMap) {
    var groups by remember { mutableStateOf(emptyMap<InventoryGroup, List<DashboardItem>>()) }
    LaunchedEffect(items) { groups = withContext(Dispatchers.Default) { items.groupBy { it.group } } }
    InventoryGroup.entries.forEach { group ->
        val rows = groups[group].orEmpty()
        if (rows.isNotEmpty()) key(group) {
            var expanded by rememberSaveable { mutableStateOf(group !in setOf(InventoryGroup.Event, InventoryGroup.Coins, InventoryGroup.Other)) }
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
    InventoryGroup.Coins -> R.string.dashboard_group_coins
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
        (it.gains?.lastQuestId ?: it.gains?.battles?.maxByOrNull { row -> row.time }?.questId)?.let { id ->
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

@Composable private fun PresentBox(presentBox: DashboardPresentBox, names: GameNameMap) {
    Text(
        stringResource(R.string.dashboard_present_box_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("dashboard-present-notice"),
    )
    if (presentBox.entries.isEmpty()) {
        Text(
            stringResource(R.string.dashboard_present_box_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("dashboard-present-empty"),
        )
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) presentBox.entries else presentBox.entries.take(12)
    LazyVerticalGrid(
        GridCells.Adaptive(144.dp),
        Modifier.fillMaxWidth().heightIn(min = 48.dp, max = if (expanded) 380.dp else 360.dp).testTag("dashboard-present-grid"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(shown, key = { "${it.giftType}:${it.objectId}" }) { entry ->
            PresentTile(entry, names)
        }
    }
    if (expanded || presentBox.entries.size > 12) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-present-toggle")) {
            Text(if (expanded) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, presentBox.entries.size))
        }
    }
}

@Composable private fun PresentTile(entry: DashboardPresent, names: GameNameMap) {
    val id = entry.objectId
    when (entry.giftType) {
        2 -> ResourceTile(LogResource(id = id, count = entry.count), names, signed = false)
        11 -> ResourceTile(LogResource(LogResource.COMMAND_CODE, id, entry.count), names, signed = false)
        1 -> {
            val servant = names.servantData[id]
            val equip = if (servant == null) names.equipData[id] else null
            when {
                servant != null -> CompactResourceTile(
                    kind = "servants",
                    id = id,
                    name = servant.name ?: names.servants[id] ?: "#$id",
                    iconUrl = servant.icon,
                    countText = entry.count.grouped(),
                    tag = "present:1:$id",
                )
                equip != null -> CompactResourceTile(
                    kind = "servants",
                    id = id,
                    name = equip.name ?: names.equips[id] ?: "#$id",
                    iconUrl = equip.icon,
                    countText = entry.count.grouped(),
                    tag = "present:1:$id",
                )
                else -> ResourceTile(LogResource(LogResource.SVT, id, entry.count), names, signed = false)
            }
        }
        else -> CompactResourceTile(
            kind = null,
            id = id,
            name = "#$id",
            iconUrl = null,
            countText = entry.count.grouped(),
            tag = "present:${entry.giftType}:$id",
        )
    }
}

@Composable private fun CommandCodeCollection(codes: DashboardCommandCodes, names: GameNameMap) {
    val ownedCount = codes.ownedIds.size
    val header = if (codes.total != null) {
        stringResource(R.string.dashboard_command_codes_progress, ownedCount, codes.total)
    } else {
        stringResource(R.string.dashboard_command_codes_owned_only, ownedCount)
    }
    Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("dashboard-cc-header"))
    if (codes.ownedIds.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("dashboard-cc-toggle")) {
            Text("${if (expanded) "−" else "+"} ${stringResource(R.string.dashboard_command_codes_owned_group)} ($ownedCount)")
        }
        if (expanded) {
            LazyVerticalGrid(
                GridCells.Adaptive(144.dp),
                Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp).testTag("dashboard-cc-grid"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(codes.ownedIds, key = { it }) { id ->
                    val atlas = names.commandCodeData[id]
                    CompactResourceTile(
                        kind = "commandCodes",
                        id = id,
                        name = atlas?.name?.takeIf { it.isNotBlank() } ?: "#$id",
                        iconUrl = atlas?.icon,
                        countText = null,
                        tag = "command-code:$id",
                    )
                }
            }
        }
    }
}

@Composable private fun ClassScore(boards: List<DashboardClassBoard>) {
    Text(
        stringResource(R.string.dashboard_last_login_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("dashboard-class-score-notice"),
    )
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) boards else boards.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (board in shown) {
            Row(
                Modifier.fillMaxWidth().testTag("class-board:${board.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AtlasIcon("classBoards", board.id, board.icon, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        board.name?.takeIf { it.isNotBlank() } ?: "#${board.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val counts = if (board.squareTotal != null && board.lockTotal != null) {
                        stringResource(
                            R.string.dashboard_class_score_squares_locks,
                            board.acquired,
                            board.squareTotal,
                            board.unlockedLocks,
                            board.lockTotal,
                        )
                    } else {
                        stringResource(
                            R.string.dashboard_class_score_counts_only,
                            board.acquired,
                            board.unlockedLocks,
                        )
                    }
                    Text(
                        counts,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (board.isComplete) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.testTag("class-board-complete:${board.id}"),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                stringResource(R.string.dashboard_class_score_complete),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
    if (expanded || boards.size > 3) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("dashboard-class-score-toggle"),
        ) {
            Text(
                if (expanded) stringResource(R.string.friends2_collapse)
                else stringResource(R.string.dashboard_show_all, boards.size),
            )
        }
    }
}

@Composable private fun SummonHistory(summons: DashboardSummons) {
    Text(
        stringResource(R.string.dashboard_last_login_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("dashboard-summons-notice"),
    )
    if (summons.friendPointTotal != null) {
        Text(
            stringResource(R.string.dashboard_friend_point_summons, summons.friendPointTotal.grouped()),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("dashboard-summons-fp"),
        )
    }
    if (summons.otherSummons.isNotEmpty()) {
        var expandedOthers by rememberSaveable { mutableStateOf(false) }
        val shown = if (expandedOthers) summons.otherSummons else summons.otherSummons.take(10)
        Text(stringResource(R.string.dashboard_other_summons), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.dashboard_summon_bonus_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = if (expandedOthers) 380.dp else 360.dp)
                .testTag("dashboard-summons-others"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shown, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().testTag("summon:${entry.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.name?.takeIf { it.isNotBlank() } ?: "#${entry.id}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        formatSummonPeriod(entry.openedAt, entry.closedAt)?.let { period ->
                            Text(
                                period,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.dashboard_summon_count, entry.count.grouped()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (expandedOthers || summons.otherSummons.size > 10) {
            TextButton(onClick = { expandedOthers = !expandedOthers }, modifier = Modifier.testTag("dashboard-summons-others-toggle")) {
                Text(if (expandedOthers) stringResource(R.string.friends2_collapse) else stringResource(R.string.dashboard_show_all, summons.otherSummons.size))
            }
        }
    }
    if (summons.recentLog.isNotEmpty()) {
        var logExpanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { logExpanded = !logExpanded }, modifier = Modifier.testTag("dashboard-summons-log-toggle")) {
            Text("${if (logExpanded) "−" else "+"} ${stringResource(R.string.dashboard_recent_summon_log)} (${summons.recentLog.size})")
        }
        if (logExpanded) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp).testTag("dashboard-summons-log"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(summons.recentLog, key = { "${it.date}:${it.gachaId}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().testTag("summon-log:${entry.date}:${entry.gachaId}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            entry.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            entry.name?.takeIf { it.isNotBlank() } ?: "#${entry.gachaId}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.dashboard_summon_log_count, entry.count),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSummonPeriod(openedAt: Long?, closedAt: Long?): String? {
    val start = openedAt?.let { formatEpochDate(it) }
    val end = closedAt?.let { formatEpochDate(it) }
    return when {
        start != null && end != null -> "$start ~ $end"
        start != null -> "$start ~"
        end != null -> "~ $end"
        else -> null
    }
}

@Composable private fun CompactResourceTile(
    kind: String?,
    id: Long,
    name: String,
    iconUrl: String?,
    countText: String?,
    tag: String,
) {
    val summary = listOfNotNull(name, countText).joinToString(", ")
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(max = 160.dp).testTag(tag).semantics(mergeDescendants = true) {
            contentDescription = summary
        },
    ) {
        Row(
            Modifier.height(48.dp).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (kind != null) AtlasIcon(kind, id, iconUrl, size = 32.dp, reserveSpace = false)
            Row(
                Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 100.dp),
                )
                if (countText != null) {
                    Text(countText, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Per-kind directories prevent mystic code IDs colliding with item IDs. */
@Composable internal fun AtlasIcon(kind: String, id: Long, url: String?, size: Dp = 40.dp, reserveSpace: Boolean = true) {
    val context = LocalContext.current
    val store = remember(context, kind) { LogIconLoader.files(context, kind) }
    val loader = LocalLogIconLoader.current ?: LogIconLoader.get(context)
    var file by remember(kind, id, url) { mutableStateOf<File?>(null) }
    LaunchedEffect(kind, id, url) { file = store.resolve(id, url) }
    if (file != null || reserveSpace) {
        Box(Modifier.size(size)) {
            if (file != null) AsyncImage(
                file!!.toURI().toString(),
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize().testTag("dashboard-icon-ready"),
            )
        }
    }
}

@Composable internal fun dashboardClass(name: String): String {
    val resource = when (name) {
        "saber" -> R.string.p_support_class_saber; "archer" -> R.string.p_support_class_archer
        "lancer" -> R.string.p_support_class_lancer; "rider" -> R.string.p_support_class_rider
        "caster" -> R.string.p_support_class_caster; "assassin" -> R.string.p_support_class_assassin
        "berserker" -> R.string.p_support_class_berserker; "shielder" -> R.string.portrait_class_shielder
        "ruler" -> R.string.portrait_class_ruler; "avenger" -> R.string.portrait_class_avenger
        "moonCancer" -> R.string.portrait_class_mooncancer; "alterEgo" -> R.string.portrait_class_alterego
        "foreigner" -> R.string.portrait_class_foreigner; "pretender" -> R.string.portrait_class_pretender
        else -> if (name.startsWith("beast", true)) R.string.portrait_class_beast else null
    }
    return resource?.let { stringResource(it) } ?: name
}
