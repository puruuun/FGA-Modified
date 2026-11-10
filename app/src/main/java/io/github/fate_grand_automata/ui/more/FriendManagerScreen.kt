package io.github.fate_grand_automata.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.account.dashboardClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AgoUnit { Minutes, Hours, Days }

internal fun agoParts(seconds: Long): Pair<AgoUnit, Long> = when {
    seconds < 3600 -> AgoUnit.Minutes to (seconds / 60).coerceAtLeast(1)
    seconds < 86_400 -> AgoUnit.Hours to seconds / 3600
    else -> AgoUnit.Days to seconds / 86_400
}

internal fun friendCodeText(code: String): String = code.filter { it.isDigit() }.let { digits ->
    if (digits.length == code.length && digits.length > 3) digits.reversed().chunked(3).joinToString(",").reversed() else code
}

@Composable
fun FriendManagerScreen(vm: FriendManagerViewModel = hiltViewModel(), requestedId: String? = null) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val current by vm.currentId.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    FriendManagerContent(null, names, accounts = accounts, currentId = current, requestedId = requestedId, onSelected = vm::select)
}

@Composable
internal fun FriendManagerContent(roster: FriendRoster?, names: GameNameMap, initialNow: Long? = null,
    accounts: List<FriendManagerAccount> = emptyList(), currentId: String? = null, requestedId: String? = null,
    onSelected: (String?) -> Unit = {}) {
    var now by remember { mutableLongStateOf(initialNow ?: (System.currentTimeMillis() / 1000)) }
    LaunchedEffect(initialNow) {
        if (initialNow == null) while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) }
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = chosen?.takeIf { id -> accounts.any { it.account.id == id } } ?: defaultFriendAccount(currentId, accounts, requestedId)
    LaunchedEffect(selectedId) { onSelected(selectedId) }
    val selected = accounts.firstOrNull { it.account.id == selectedId }
    val activeRoster = selected?.roster ?: roster
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (activeRoster == null && selected != null) {
                // The roster of the selected account is being read from disk.
                Heading(stringResource(R.string.friends_title))
            } else if (activeRoster == null) {
                Heading(stringResource(R.string.friends_title))
                Text(stringResource(R.string.friends_empty), Modifier.padding(16.dp).testTag("friends-empty"))
            } else key(selectedId) {
                FriendListBody(activeRoster, names, now) {
                    if (selected != null) AccountPicker(accounts, selected, now) { chosen = it }
                }
            }
        }
    }
}

@Composable
private fun AccountPicker(accounts: List<FriendManagerAccount>, selected: FriendManagerAccount, now: Long, choose: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().then(if (accounts.size > 1) Modifier.clickable { menu = true } else Modifier)
            .padding(vertical = 8.dp).testTag("friends-account"), verticalAlignment = Alignment.CenterVertically) {
            AccountLabel(selected, now, Modifier.weight(1f))
            if (accounts.size > 1) Icon(Icons.Default.ExpandMore, stringResource(R.string.friends2_account))
        }
        DropdownMenu(menu, { menu = false }) {
            accounts.forEach { item -> DropdownMenuItem(text = { AccountLabel(item, now) }, onClick = { choose(item.account.id); menu = false }) }
        }
    }
}

@Composable
private fun AccountLabel(item: FriendManagerAccount, now: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.account.displayName, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.account.server, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.friends2_account_detail, item.account.summary.level, item.friendCount,
            loginText(agoParts(now - item.updatedAt))), style = MaterialTheme.typography.bodySmall)
    }
}

internal val FriendFilterSidePanelWidth = 360.dp
internal val FriendFilterWideBreakpoint = 600.dp
private const val FRIEND_NAME_DEBOUNCE_MS = 250L

@Composable
private fun FriendListBody(roster: FriendRoster, names: GameNameMap, now: Long, accountPicker: @Composable () -> Unit) {
    var formation by rememberSaveable { mutableStateOf(FriendFormation.Main) }
    var servant by rememberSaveable { mutableStateOf<Long?>(null) }
    var ces by rememberSaveable { mutableStateOf(arrayListOf<Long>()) }
    var ceLimit by rememberSaveable { mutableStateOf(FriendCeLimit.All) }
    var friendName by rememberSaveable { mutableStateOf("") }
    var minLevel by rememberSaveable { mutableIntStateOf(0) }
    var levelAtMost by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(FriendLogin.All) }
    var loginAmount by rememberSaveable { mutableIntStateOf(FRIEND_INACTIVE_DEFAULT) }
    var loginUnit by rememberSaveable { mutableStateOf(LoginUnit.Days) }
    var panel by rememberSaveable { mutableStateOf(false) }
    val filter = FriendFilter(formation, servant, ces.toSet(), ceLimit, login, loginAmount, loginUnit, friendName, minLevel, levelAtMost)
    val change: (FriendFilter) -> Unit = {
        formation = it.formation; servant = it.servantId; ces = ArrayList(it.ceIds); ceLimit = it.ceLimit
        login = it.login; loginAmount = it.inactiveAmount; loginUnit = it.loginUnit
        friendName = it.friendName; minLevel = it.minLevel; levelAtMost = it.levelAtMost
    }
    // Narrow windows edit a draft that only "N칸 보기" applies; wide windows edit the applied filter directly.
    var draft by remember { mutableStateOf(filter) }
    // The roster is already in memory: the rows are recomputed only when the applied filter (or the clock minute) changes.
    val shown = remember(roster, filter, now) { FriendList.select(roster, filter, now) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= FriendFilterWideBreakpoint
        LaunchedEffect(wide) { draft = filter }
        val openPanel = { draft = filter; panel = true }
        val panelFilter = if (wide) filter else draft
        val panelRows by remember(roster, panelFilter.formation) { derivedStateOf { FriendList.rows(roster, panelFilter.formation) } }
        val servantCounts by remember { derivedStateOf { FriendList.servantCounts(panelRows) } }
        val ceCounts by remember { derivedStateOf { FriendList.ceCounts(panelRows) } }
        val panelShown by remember(roster, panelFilter, now) { derivedStateOf { if (wide) shown.size else FriendList.select(roster, panelFilter, now).size } }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CappedWidth {
                    Column(Modifier.fillMaxSize()) {
                        Heading(stringResource(R.string.friends_title))
                        accountPicker()
                        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = openPanel, modifier = Modifier.testTag("friends-filter"),
                                    leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                                    label = { Text(stringResource(R.string.friends2_filters)) })
                                AssistChip(onClick = openPanel, label = { Text(stringResource(formation.label())) })
                                servant?.let { RemovableFilter(names.servants[it] ?: "#$it") { servant = null } }
                                ces.forEach { id -> RemovableFilter(names.equips[id] ?: "#$id") { ces = ArrayList(ces - id) } }
                                if (ceLimit != FriendCeLimit.All) RemovableFilter(stringResource(ceLimit.label())) { ceLimit = FriendCeLimit.All }
                                if (friendName.isNotEmpty()) RemovableFilter(friendName) { friendName = "" }
                                if (minLevel > 0) RemovableFilter(stringResource(if (levelAtMost) R.string.friends_level_max else R.string.friends_level_min, minLevel)) { minLevel = 0 }
                                if (login == FriendLogin.Inactive) RemovableFilter(stringResource(R.string.friends2_login_chip, filter.inactiveAmount, stringResource(loginUnit.label()))) { login = FriendLogin.All }
                            }
                            if (shown.isEmpty()) Text(stringResource(R.string.friends_no_match), Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("friends-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                                stickyHeader { TableHeader() }
                                items(shown, key = { "${it.friend.userId}:${it.slot.deckId}:${it.slot.classId}" }) { FriendTableRow(it, names, now) }
                            }
                        }
                    }
                }
            }
            if (wide && panel) FriendFilterPanel(filter, servantCounts, ceCounts, names, panelShown, wide = true,
                onChange = change, onReset = { change(FriendFilter()) }, onDismiss = { panel = false })
        }
        if (!wide && panel) FriendFilterPanel(draft, servantCounts, ceCounts, names, panelShown, wide = false,
            onChange = { draft = it }, onReset = { draft = FriendFilter() }, onDismiss = { panel = false },
            onApply = { change(draft); panel = false })
    }
}

@Composable
private fun RemovableFilter(label: String, remove: () -> Unit) {
    InputChip(selected = true, onClick = remove, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.friends2_remove_filter), Modifier.size(16.dp)) })
}

private fun LoginUnit.label() = when (this) {
    LoginUnit.Minutes -> R.string.friends2_unit_minutes
    LoginUnit.Hours -> R.string.friends2_unit_hours
    LoginUnit.Days -> R.string.friends2_unit_days
}

private fun FriendFormation.label() = when (this) {
    FriendFormation.Main -> R.string.friends_deck_main
    FriendFormation.Event -> R.string.friends_deck_event
    FriendFormation.Grand -> R.string.friends_deck_grand
}

private fun FriendCeLimit.label() = when (this) {
    FriendCeLimit.All -> R.string.friends_filter_all
    FriendCeLimit.Max -> R.string.friends_max_lb
    FriendCeLimit.NotMax -> R.string.friends_not_max_lb
}

internal val FriendFilterPanelMaxWidth = 640.dp

/**
 * Wide windows ([wide]): a [FriendFilterSidePanelWidth] panel on the right at full height, no scrim; every change in [filter] is applied
 * to the list at once, and "닫기" closes it. Narrow windows: the top panel at the old sheet width; the caller keeps a draft and only
 * [onApply] ("N칸 보기") commits it, while [onDismiss] discards it.
 */
@Composable
internal fun FriendFilterPanel(filter: FriendFilter, servantCounts: Map<Long, Int>, ceCounts: Map<Long, Int>,
    names: GameNameMap, shown: Int, wide: Boolean = false, onChange: (FriendFilter) -> Unit, onReset: () -> Unit,
    onDismiss: () -> Unit, onApply: () -> Unit = onDismiss) {
    var picker by remember { mutableStateOf<String?>(null) }
    BackHandler { if (picker != null) picker = null else onDismiss() }
    val content: @Composable () -> Unit = {
        if (picker != null) {
            val ce = picker == "ce"
            FriendPicker(ce, if (ce) ceCounts else servantCounts, names, filter, onDismiss = { picker = null }) { id ->
                if (ce) onChange(filter.copy(ceIds = when {
                    id == null -> emptySet()
                    id in filter.ceIds -> filter.ceIds - id
                    else -> filter.ceIds + id
                })) else {
                    onChange(filter.copy(servantId = id))
                    picker = null
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.friends2_filters), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("friends-panel-x")) { Icon(Icons.Default.Close, stringResource(android.R.string.cancel)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterSections(filter, names, wide, onChange) { picker = it }
            }
            if (wide) Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onReset, modifier = Modifier.testTag("friends-reset")) { Text(stringResource(R.string.friends2_reset)) }
                Text(stringResource(R.string.friends2_result_count, shown), Modifier.weight(1f).testTag("friends-result-count"),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = onDismiss, modifier = Modifier.testTag("friends-close")) { Text(stringResource(R.string.friends2_close)) }
            } else Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.friends2_reset)) }
                Button(onClick = onApply, modifier = Modifier.weight(1f).testTag("friends-apply")) { Text(stringResource(R.string.friends2_show_slots, shown)) }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxHeight()) {
            VerticalDivider()
            Surface(Modifier.width(FriendFilterSidePanelWidth).fillMaxHeight().testTag("friends-filter-panel"),
                color = MaterialTheme.colorScheme.surface, content = content)
        }
    } else {
        val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .4f)).clickable(onClick = onDismiss))
            // Same width as the old bottom sheet (owner, 2026-11-10): full width on phones, at most 640 dp and centred.
            AnimatedVisibility(visibleState = entrance, enter = slideInVertically { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(Modifier.widthIn(max = FriendFilterPanelMaxWidth).fillMaxWidth().fillMaxHeight(.92f).testTag("friends-filter-panel"),
                    shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface, content = content)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.titleSmall)

@Composable
private fun SectionDivider() = HorizontalDivider(Modifier.padding(vertical = 2.dp))

/** The panel's sections in one column: formation, friend name, servant, level, CE, CE limit break, last login. */
@Composable
private fun FilterSections(filter: FriendFilter, names: GameNameMap, wide: Boolean, onChange: (FriendFilter) -> Unit, openPicker: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FriendFormation.entries.forEachIndexed { index, deck ->
            SegmentedButton(selected = filter.formation == deck,
                onClick = { if (filter.formation != deck) onChange(filter.copy(formation = deck, servantId = null, ceIds = emptySet())) },
                shape = SegmentedButtonDefaults.itemShape(index, 3),
                modifier = Modifier.testTag("friends-deck-" + deck.name.lowercase())) {
                Text(stringResource(deck.label()))
            }
        }
    }
    SectionDivider()
    FriendNameField(filter.friendName, if (wide) FRIEND_NAME_DEBOUNCE_MS else 0L) { onChange(filter.copy(friendName = it)) }
    SectionDivider()
    SectionTitle(stringResource(R.string.friends2_servant))
    OutlinedButton(onClick = { openPicker("servant") }, modifier = Modifier.fillMaxWidth().testTag("friends-servant")) {
        Text(filter.servantId?.let { servantPickerName(names, it) } ?: stringResource(R.string.friends_all_servants),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    filter.servantId?.let { id ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RemovableFilter(names.servants[id] ?: "#$id") { onChange(filter.copy(servantId = null)) }
        }
    }
    SectionDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.friends_servant_level), Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            listOf(false, true).forEachIndexed { index, atMost ->
                SegmentedButton(selected = filter.levelAtMost == atMost, onClick = { onChange(filter.copy(levelAtMost = atMost)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                    modifier = Modifier.testTag(if (atMost) "friends-level-at-most" else "friends-level-at-least")) {
                    Text(stringResource(if (atMost) R.string.friends_level_dir_max else R.string.friends_level_dir_min), maxLines = 1)
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 80, 90, 100, 120).forEach { level ->
            FilterChip(selected = filter.minLevel == level, onClick = { onChange(filter.copy(minLevel = level)) },
                modifier = Modifier.testTag("friends-level-$level"),
                label = { Text(if (level == 0) stringResource(R.string.friends_filter_all) else level.toString()) })
        }
    }
    SectionDivider()
    SectionTitle(stringResource(R.string.friends2_ce))
    OutlinedButton(onClick = { openPicker("ce") }, modifier = Modifier.fillMaxWidth().testTag("friends-ce")) {
        Text(if (filter.ceIds.isEmpty()) stringResource(R.string.friends_all_ces)
            else stringResource(R.string.friends2_ce_selected_count, filter.ceIds.size))
    }
    if (filter.ceIds.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        filter.ceIds.forEach { id -> RemovableFilter(names.equips[id] ?: "#$id") { onChange(filter.copy(ceIds = filter.ceIds - id)) } }
    }
    SectionDivider()
    SectionTitle(stringResource(R.string.friends_ce_limit))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FriendCeLimit.entries.forEach { limit ->
            FilterChip(selected = filter.ceLimit == limit, onClick = { onChange(filter.copy(ceLimit = limit)) },
                modifier = Modifier.testTag("friends-limit-" + limit.name),
                label = { Text(stringResource(limit.label())) })
        }
    }
    SectionDivider()
    SectionTitle(stringResource(R.string.friends2_last_login))
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(filter.login == FriendLogin.All, { onChange(filter.copy(login = FriendLogin.All)) }, Modifier.testTag("friends-login-all"))
        Text(stringResource(R.string.friends_login_all), Modifier.clickable { onChange(filter.copy(login = FriendLogin.All)) })
        RadioButton(filter.login == FriendLogin.Inactive, { onChange(filter.copy(login = FriendLogin.Inactive)) }, Modifier.testTag("friends-login-inactive"))
        Text(stringResource(R.string.friends2_login_inactive), Modifier.clickable { onChange(filter.copy(login = FriendLogin.Inactive)) })
    }
    if (filter.login == FriendLogin.Inactive) InactiveInput(filter, onChange)
}

/** The name is applied [debounceMs] after the last keystroke; an outside change (reset, chip removal) replaces the text. */
@Composable
private fun FriendNameField(applied: String, debounceMs: Long, apply: (String) -> Unit) {
    var text by remember { mutableStateOf(applied) }
    var pushed by remember { mutableStateOf(applied) }
    val latestApply by rememberUpdatedState(apply)
    LaunchedEffect(applied) { if (applied != pushed) { text = applied; pushed = applied } }
    LaunchedEffect(text) {
        if (text != pushed) {
            if (debounceMs > 0) delay(debounceMs)
            pushed = text
            latestApply(text)
        }
    }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("friends-name"), singleLine = true,
        label = { Text(stringResource(R.string.friends_friend_name)) })
}

/** `[−] [N] [+] [unit ▾] 이상`: N is typeable (digits only, clamped), the unit is a dropdown. */
@Composable
private fun InactiveInput(filter: FriendFilter, onChange: (FriendFilter) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val amount = filter.inactiveAmount
    var text by remember { mutableStateOf(amount.toString()) }
    LaunchedEffect(amount) { if (text.toIntOrNull() != amount) text = amount.toString() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(filter.copy(loginAmount = amount - 1)) }, enabled = amount > FRIEND_INACTIVE_MIN,
            modifier = Modifier.testTag("friends-login-minus")) { Text("−") }
        OutlinedTextField(text, { input ->
            val digits = input.filter { it.isDigit() }.take(3)
            if (digits.isEmpty()) text = "" else {
                val value = digits.toInt().coerceIn(FRIEND_INACTIVE_MIN, FRIEND_INACTIVE_MAX)
                text = value.toString()
                if (value != amount) onChange(filter.copy(loginAmount = value))
            }
        }, Modifier.width(72.dp).testTag("friends-login-amount"), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        IconButton(onClick = { onChange(filter.copy(loginAmount = amount + 1)) }, enabled = amount < FRIEND_INACTIVE_MAX,
            modifier = Modifier.testTag("friends-login-plus")) { Text("+") }
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("friends-login-unit"),
                contentPadding = PaddingValues(start = 12.dp, end = 6.dp)) {
                Text(stringResource(filter.loginUnit.label()), maxLines = 1)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                LoginUnit.entries.forEach { unit ->
                    DropdownMenuItem(text = { Text(stringResource(unit.label())) }, modifier = Modifier.testTag("friends-unit-" + unit.name),
                        onClick = { onChange(filter.copy(loginUnit = unit)); menu = false })
                }
            }
        }
        Text(stringResource(R.string.friends2_login_or_more), maxLines = 1)
    }
}

@Composable
internal fun servantPickerName(names: GameNameMap, id: Long): String {
    val name = names.servants[id] ?: "#$id"
    val cls = names.servantData[id]?.className ?: return name
    val label = when (cls) {
        "saber" -> stringResource(R.string.friends_class_saber)
        "archer" -> stringResource(R.string.friends_class_archer)
        "lancer" -> stringResource(R.string.friends_class_lancer)
        "rider" -> stringResource(R.string.friends_class_rider)
        "caster" -> stringResource(R.string.friends_class_caster)
        "assassin" -> stringResource(R.string.friends_class_assassin)
        "berserker" -> stringResource(R.string.friends_class_berserker)
        else -> dashboardClass(cls)
    }
    return "($label) $name"
}

@Composable
private fun FriendPicker(ce: Boolean, counts: Map<Long, Int>, names: GameNameMap, filter: FriendFilter,
    onDismiss: () -> Unit, choose: (Long?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val displayNames = if (ce) names.equips else counts.keys.associateWith { servantPickerName(names, it) }
    val options = if (ce) friendCeOptions(counts, displayNames) else friendServantOptions(counts, displayNames, query)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (ce) R.string.friends2_ce else R.string.friends2_servant), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("friends-picker-done")) { Text(stringResource(android.R.string.ok)) }
        }
        if (!ce) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.friends2_search)) }, modifier = Modifier.fillMaxWidth().testTag("friends-search"))
        LazyColumn(Modifier.weight(1f)) {
            item { TextButton(onClick = { choose(null) }) { Text(stringResource(if (ce) R.string.friends_all_ces else R.string.friends_all_servants)) } }
            if (ce && options.firstOrNull()?.id?.let(::isBondCe) == true) item {
                Text(stringResource(R.string.friends2_bond_bonus), style = MaterialTheme.typography.titleSmall)
            }
            itemsIndexed(options, key = { _, option -> option.id }) { index, option ->
                if (ce && index > 0 && isBondCe(options[index - 1].id) && !isBondCe(option.id)) HorizontalDivider()
                val label = stringResource(R.string.friends_option_count, option.name, option.count)
                if (ce) Row(Modifier.fillMaxWidth().clickable { choose(option.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(option.id in filter.ceIds, { choose(option.id) })
                    Text(label)
                } else TextButton(onClick = { choose(option.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun isBondCe(id: Long) = id == FRIEND_TEATIME_CE_ID || id in FRIEND_BOND_20_CE_IDS

private val COLUMN_WEIGHTS = listOf(.24f, .175f, .175f, .29f, .12f)

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 10.dp)) {
        listOf(R.string.friends2_name, R.string.friends2_skills, R.string.friends2_appends, R.string.friends2_ce, R.string.friends2_login_column)
            .forEachIndexed { index, label ->
                Text(stringResource(label), Modifier.weight(COLUMN_WEIGHTS[index]).padding(horizontal = 1.dp), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
    }
}

@Composable
private fun FriendTableRow(row: FriendRow, names: GameNameMap, now: Long) {
    val friend = row.friend
    val slot = row.slot
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("friends-row")) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(COLUMN_WEIGHTS[0]).padding(end = 2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(names.servants[slot.svtId] ?: "#${slot.svtId}", Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (slot.grand) Text(stringResource(R.string.friends_grand),
                        Modifier.testTag("friends-grand-badge")
                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1, softWrap = false)
                }
                Text(stringResource(R.string.friends2_name_line, slot.lv, slot.np, friend.name), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Numbers are never ellipsized: they do not wrap and are clipped only past the column edge.
            Text(slot.skills.joinToString("/"), Modifier.weight(COLUMN_WEIGHTS[1]).padding(horizontal = 1.dp), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text((0..4).joinToString("/") { slot.appends.getOrNull(it)?.toString() ?: "-" },
                Modifier.weight(COLUMN_WEIGHTS[2]).padding(horizontal = 1.dp).testTag("friends-appends"),
                style = MaterialTheme.typography.bodySmall, softWrap = true)
            Column(Modifier.weight(COLUMN_WEIGHTS[3]).padding(start = 1.dp, end = 4.dp)) {
                val twoCes = row.grandSlot != null || slot.ce2Id?.takeIf { it != 0L } != null
                if (slot.ceId == 0L) Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else CeLine(names.equips[slot.ceId] ?: "#${slot.ceId}", slot.ceLimitCount, slot.ceLv, if (twoCes) 1 else 2)
                // The two equipment lines precede the independent bond-effect label.
                if (row.grandSlot != null && slot.ce2Id == null) Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall)
                slot.ce2Id?.takeIf { it != 0L }?.let { CeLine(names.equips[it] ?: "#$it", slot.ce2LimitCount ?: 0, slot.ce2Lv ?: 0) }
                row.grandSlot?.let { grand ->
                    Text(stringResource(when (grand.bondEffect) {
                        FriendBondEffect.NpCharge -> R.string.friends_bond_np
                        FriendBondEffect.Base -> R.string.friends_bond_base
                        FriendBondEffect.None -> R.string.friends_bond_none
                    }), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("friends-bond-effect"))
                }
            }
            Text(if (friend.lastLogin > 0) loginText(agoParts(now - friend.lastLogin)) else "–", Modifier.weight(COLUMN_WEIGHTS[4]).padding(horizontal = 1.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        if (expanded) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp).testTag("friends-expanded")) {
            friend.friendCode?.let { code ->
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.friends_code, friendCodeText(code)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", code.filter { it.isDigit() }))) } },
                        modifier = Modifier.size(40.dp).testTag("friends-copy")) { Icon(Icons.Default.ContentCopy, stringResource(R.string.dashboard_copy), Modifier.size(18.dp)) }
                }
            }
            Text(stringResource(R.string.friends2_expanded, friend.lv, if (row.grandSlot != null) servantPickerName(names, slot.svtId) else classLabel(slot.classId),
                row.grandSlot?.seat?.toString() ?: row.deckIndex?.toString() ?: "–",
                if (friend.lastLogin > 0) SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(friend.lastLogin * 1000))
                else stringResource(R.string.friends_login_unknown)), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

/** One CE: its name (up to [nameLines] lines, tag and level on the first line's baseline), then the max tag and the level right after it on the same baseline. */
@Composable
private fun CeLine(name: String, limitCount: Int, lv: Int, nameLines: Int = 1) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("friends-ce-line")) {
        Text(name, Modifier.weight(1f, fill = false).alignByBaseline(), style = MaterialTheme.typography.bodySmall,
            maxLines = nameLines, overflow = TextOverflow.Ellipsis)
        if (limitCount == FriendList.MAX_LIMIT_COUNT) Text(stringResource(R.string.friends_ce_max),
            Modifier.alignByBaseline().background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1, softWrap = false)
        Text(stringResource(R.string.friends2_level_compact, lv), Modifier.alignByBaseline(), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, softWrap = false)
    }
}

@Composable
private fun loginText(parts: Pair<AgoUnit, Long>): String = stringResource(when (parts.first) {
    AgoUnit.Minutes -> R.string.friends2_login_min
    AgoUnit.Hours -> R.string.friends2_login_hour
    AgoUnit.Days -> R.string.friends2_login_day
}, parts.second.toInt())

@Composable
private fun classLabel(classId: Int): String = stringResource(when (classId) {
    1 -> R.string.p_support_class_saber
    2 -> R.string.p_support_class_archer
    3 -> R.string.p_support_class_lancer
    4 -> R.string.p_support_class_rider
    5 -> R.string.p_support_class_caster
    6 -> R.string.p_support_class_assassin
    7 -> R.string.p_support_class_berserker
    8 -> R.string.friends_class_extra
    else -> R.string.friends_class_all
})
