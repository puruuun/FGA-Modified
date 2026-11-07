package io.github.fate_grand_automata.ui.more

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
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
fun FriendManagerScreen(vm: FriendManagerViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val current by vm.currentId.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    FriendManagerContent(null, names, accounts = accounts, currentId = current)
}

@Composable
internal fun FriendManagerContent(roster: FriendRoster?, names: GameNameMap, initialNow: Long? = null,
    accounts: List<FriendManagerAccount> = emptyList(), currentId: String? = null) {
    var now by remember { mutableLongStateOf(initialNow ?: (System.currentTimeMillis() / 1000)) }
    LaunchedEffect(initialNow) {
        if (initialNow == null) while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) }
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = chosen?.takeIf { id -> accounts.any { it.account.id == id } } ?: defaultFriendAccount(currentId, accounts)
    val selected = accounts.firstOrNull { it.account.id == selectedId }
    val activeRoster = selected?.roster ?: roster
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            if (activeRoster == null) {
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
        Text(stringResource(R.string.friends2_account_detail, item.account.summary.level, item.roster.friends.size,
            loginText(agoParts(now - item.roster.updatedAt))), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FriendListBody(roster: FriendRoster, names: GameNameMap, now: Long, accountPicker: @Composable () -> Unit) {
    var event by rememberSaveable { mutableStateOf(false) }
    var servant by rememberSaveable { mutableStateOf<Long?>(null) }
    var ce by rememberSaveable { mutableStateOf<Long?>(null) }
    var maxLb by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(FriendLogin.All) }
    var loginAmount by rememberSaveable { mutableIntStateOf(FRIEND_INACTIVE_DEFAULT) }
    var loginUnit by rememberSaveable { mutableStateOf(LoginUnit.Days) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val filter = FriendFilter(event, servant, ce, maxLb, login, loginAmount, loginUnit)
    val change: (FriendFilter) -> Unit = { event = it.event; servant = it.servantId; ce = it.ceId; maxLb = it.maxLimitBreak; login = it.login
        loginAmount = it.loginAmount; loginUnit = it.loginUnit }
    var shown by remember { mutableStateOf(emptyList<FriendRow>()) }
    var servantCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var ceCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    LaunchedEffect(roster, filter) {
        val result = withContext(Dispatchers.Default) {
            val rows = FriendList.rows(roster, event)
            Triple(FriendList.select(roster, filter, now), FriendList.servantCounts(rows), FriendList.ceCounts(rows))
        }
        shown = result.first; servantCounts = result.second; ceCounts = result.third
    }
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(R.string.friends_title))
        accountPicker()
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { sheet = true }, modifier = Modifier.testTag("friends-filter"),
                    leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.friends2_filters)) })
                AssistChip(onClick = { sheet = true }, label = { Text(stringResource(if (event) R.string.friends_deck_event else R.string.friends_deck_main)) })
                servant?.let { RemovableFilter(names.servants[it] ?: "#$it") { servant = null } }
                ce?.let { RemovableFilter(names.equips[it] ?: "#$it") { ce = null } }
                if (maxLb) RemovableFilter(stringResource(R.string.friends_max_lb)) { maxLb = false }
                if (login == FriendLogin.Inactive) RemovableFilter(stringResource(R.string.friends2_login_chip, loginAmount, stringResource(loginUnit.label()))) { login = FriendLogin.All }
            }
            if (shown.isEmpty()) Text(stringResource(R.string.friends_no_match), Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("friends-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                stickyHeader { TableHeader() }
                items(shown, key = { "${it.friend.userId}:${it.slot.deckId}:${it.slot.classId}" }) { FriendTableRow(it, names, now) }
            }
        }
    }
    if (sheet) FriendFilterSheet(filter, servantCounts, ceCounts, names, shown.size,
        onChange = change, onReset = { change(FriendFilter()) }, onDismiss = { sheet = false })
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

@Composable
internal fun FriendFilterSheet(filter: FriendFilter, servantCounts: Map<Long, Int>, ceCounts: Map<Long, Int>,
    names: GameNameMap, shown: Int, onChange: (FriendFilter) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    var picker by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (picker != null) {
            val ce = picker == "ce"
            FriendPicker(ce, if (ce) ceCounts else servantCounts, names, onDismiss = { picker = null }) { id ->
                onChange(if (ce) filter.copy(ceId = id) else filter.copy(servantId = id)); picker = null
            }
        } else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("friends-filter-sheet")) {
            Text(stringResource(R.string.friends2_filters), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.friends2_deck), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { index, event ->
                        SegmentedButton(selected = filter.event == event,
                            onClick = { if (filter.event != event) onChange(filter.copy(event = event, servantId = null, ceId = null)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag(if (event) "friends-deck-event" else "friends-deck-main")) {
                            Text(stringResource(if (event) R.string.friends_deck_event else R.string.friends_deck_main))
                        }
                    }
                }
                Text(stringResource(R.string.friends2_servant), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { picker = "servant" }, modifier = Modifier.fillMaxWidth().testTag("friends-servant")) {
                    Text(filter.servantId?.let { names.servants[it] ?: "#$it" } ?: stringResource(R.string.friends_all_servants))
                }
                Text(stringResource(R.string.friends2_ce), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { picker = "ce" }, modifier = Modifier.fillMaxWidth().testTag("friends-ce")) {
                    Text(filter.ceId?.let { names.equips[it] ?: "#$it" } ?: stringResource(R.string.friends_all_ces))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.friends_max_lb), Modifier.weight(1f))
                    Switch(filter.maxLimitBreak, { onChange(filter.copy(maxLimitBreak = it)) }, Modifier.testTag("friends-max-lb"))
                }
                Text(stringResource(R.string.friends2_last_login), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().clickable { onChange(filter.copy(login = FriendLogin.All)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(filter.login == FriendLogin.All, { onChange(filter.copy(login = FriendLogin.All)) }, Modifier.testTag("friends-login-all"))
                    Text(stringResource(R.string.friends_login_all))
                }
                Row(Modifier.fillMaxWidth().clickable { onChange(filter.copy(login = FriendLogin.Inactive)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(filter.login == FriendLogin.Inactive, { onChange(filter.copy(login = FriendLogin.Inactive)) }, Modifier.testTag("friends-login-inactive"))
                    Text(stringResource(R.string.friends2_login_inactive))
                }
                if (filter.login == FriendLogin.Inactive) InactiveInput(filter, onChange)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.friends2_reset)) }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).testTag("friends-apply")) { Text(stringResource(R.string.friends2_show_slots, shown)) }
            }
        }
    }
}

@Composable
private fun InactiveInput(filter: FriendFilter, onChange: (FriendFilter) -> Unit) {
    var text by remember { mutableStateOf(filter.loginAmount.toString()) }
    var menu by remember { mutableStateOf(false) }
    FlowRow(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { value ->
            val digits = value.filter { it.isDigit() }.take(3)
            text = digits
            digits.toIntOrNull()?.takeIf { it in FRIEND_INACTIVE_MIN..FRIEND_INACTIVE_MAX }?.let { onChange(filter.copy(loginAmount = it)) }
        }, Modifier.width(88.dp).testTag("friends-login-amount"), singleLine = true,
            label = { Text(stringResource(R.string.friends2_login_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("friends-login-unit")) {
                Text(stringResource(filter.loginUnit.label()))
            }
            DropdownMenu(menu, { menu = false }) {
                LoginUnit.entries.forEach { unit ->
                    DropdownMenuItem(text = { Text(stringResource(unit.label())) }, modifier = Modifier.testTag("friends-unit-${unit.name}"),
                        onClick = { onChange(filter.copy(loginUnit = unit)); menu = false })
                }
            }
        }
        Text(stringResource(R.string.friends2_login_or_more))
    }
}

@Composable
private fun FriendPicker(ce: Boolean, counts: Map<Long, Int>, names: GameNameMap, onDismiss: () -> Unit, choose: (Long?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(emptyList<FriendOption>()) }
    LaunchedEffect(ce, counts, names, query) {
        options = withContext(Dispatchers.Default) {
            if (ce) friendCeOptions(counts, names.equips) else friendServantOptions(counts, names.servants, query)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (ce) R.string.friends2_ce else R.string.friends2_servant), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
        if (!ce) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.friends2_search)) }, modifier = Modifier.fillMaxWidth().testTag("friends-search"))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { TextButton(onClick = { choose(null) }) { Text(stringResource(if (ce) R.string.friends_all_ces else R.string.friends_all_servants)) } }
            if (ce && options.firstOrNull()?.id?.let(::isBondCe) == true) item {
                Text(stringResource(R.string.friends2_bond_bonus), style = MaterialTheme.typography.titleSmall)
            }
            itemsIndexed(options, key = { _, option -> option.id }) { index, option ->
                if (ce && index > 0 && isBondCe(options[index - 1].id) && !isBondCe(option.id)) HorizontalDivider()
                TextButton(onClick = { choose(option.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.friends_option_count, option.name, option.count), Modifier.fillMaxWidth())
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
        // Every cell is at most two lines, so a row never grows beyond two lines.
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(COLUMN_WEIGHTS[0]).padding(end = 2.dp)) {
                Text(names.servants[slot.svtId] ?: "#${slot.svtId}", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.friends2_name_line, slot.lv, slot.np, friend.name), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Numbers are never ellipsized: they do not wrap and are clipped only past the column edge.
            Text(slot.skills.joinToString("/"), Modifier.weight(COLUMN_WEIGHTS[1]).padding(horizontal = 1.dp), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text((0..2).joinToString("/") { slot.appends.getOrNull(it)?.toString() ?: "–" } + "\n" +
                (3..4).joinToString("/") { slot.appends.getOrNull(it)?.toString() ?: "–" }, Modifier.weight(COLUMN_WEIGHTS[2]).padding(horizontal = 1.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, softWrap = false, overflow = TextOverflow.Clip)
            Column(Modifier.weight(COLUMN_WEIGHTS[3]).padding(horizontal = 1.dp)) {
                if (slot.ceId == 0L) Text(stringResource(R.string.friends_ce_none), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else CeLine(names.equips[slot.ceId] ?: "#${slot.ceId}", slot.ceLimitCount, slot.ceLv)
                // Grand Duel supports carry a second CE on its own line, so a cell is at most two lines.
                slot.ce2Id?.takeIf { it != 0L }?.let { CeLine(names.equips[it] ?: "#$it", slot.ce2LimitCount ?: 0, slot.ce2Lv ?: 0) }
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
            Text(stringResource(R.string.friends2_expanded, friend.lv, classLabel(slot.classId), row.deckIndex?.toString() ?: "–",
                if (friend.lastLogin > 0) SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(friend.lastLogin * 1000))
                else stringResource(R.string.friends_login_unknown)), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

/** One CE: its name, then the max tag and the level right after it on the same baseline. */
@Composable
private fun CeLine(name: String, limitCount: Int, lv: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("friends-ce-line")) {
        Text(name, Modifier.weight(1f, fill = false).alignByBaseline(), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
