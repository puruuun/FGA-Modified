package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AgoUnit { Minutes, Hours, Days }

/** "N minutes / hours / days ago"; under a minute counts as one minute, and times in the future as now. */
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
    val roster by vm.roster.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    FriendManagerContent(roster, names)
}

@Composable
internal fun FriendManagerContent(roster: FriendRoster?, names: GameNameMap, initialNow: Long? = null) {
    var now by remember { mutableLongStateOf(initialNow ?: (System.currentTimeMillis() / 1000)) }
    LaunchedEffect(initialNow) {
        if (initialNow == null) while (true) { now = System.currentTimeMillis() / 1000; delay(60_000) }
    }
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.friends_title))
            if (roster == null) {
                Text(stringResource(R.string.friends_empty), Modifier.padding(16.dp).testTag("friends-empty"))
            } else FriendListBody(roster, names, now)
        }
    }
}

@Composable
private fun FriendListBody(roster: FriendRoster, names: GameNameMap, now: Long) {
    var event by rememberSaveable { mutableStateOf(false) }
    var servant by rememberSaveable { mutableStateOf<Long?>(null) }
    var ce by rememberSaveable { mutableStateOf<Long?>(null) }
    var maxLb by rememberSaveable { mutableStateOf(false) }
    var login by rememberSaveable { mutableStateOf(FriendLogin.All) }
    val filter = FriendFilter(event, servant, ce, maxLb, login)
    var shown by remember { mutableStateOf(emptyList<FriendRow>()) }
    var servantCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    var ceCounts by remember { mutableStateOf(emptyMap<Long, Int>()) }
    LaunchedEffect(roster, filter, now / 60) {
        val result = withContext(Dispatchers.Default) {
            val rows = FriendList.rows(roster, event)
            Triple(FriendList.select(roster, filter, now), FriendList.servantCounts(rows), FriendList.ceCounts(rows))
        }
        shown = result.first; servantCounts = result.second; ceCounts = result.third
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.friends_count, roster.friends.size), style = MaterialTheme.typography.titleMedium)
        if (roster.updatedAt > 0) {
            val (unit, value) = agoParts((now - roster.updatedAt).coerceAtLeast(0))
            Text(stringResource(R.string.friends_updated, agoText(unit, value)), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!event, { event = false; servant = null; ce = null }, { Text(stringResource(R.string.friends_deck_main)) },
                modifier = Modifier.testTag("friends-deck-main"))
            FilterChip(event, { event = true; servant = null; ce = null }, { Text(stringResource(R.string.friends_deck_event)) },
                modifier = Modifier.testTag("friends-deck-event"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(stringResource(R.string.friends_all_servants), servant, servantCounts, { names.servants[it] ?: "#$it" }, { servant = it }, "friends-servant")
            Picker(stringResource(R.string.friends_all_ces), ce, ceCounts, { names.equips[it] ?: "#$it" }, { ce = it }, "friends-ce")
            FilterChip(maxLb, { maxLb = !maxLb }, { Text(stringResource(R.string.friends_max_lb)) }, modifier = Modifier.testTag("friends-max-lb"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FriendLogin.entries.forEach { option ->
                FilterChip(login == option, { login = option }, {
                    Text(stringResource(when (option) {
                        FriendLogin.All -> R.string.friends_login_all
                        FriendLogin.Within7Days -> R.string.friends_login_week
                        FriendLogin.Over7Days -> R.string.friends_login_over
                    }))
                })
            }
        }
        if (shown.isEmpty()) Text(stringResource(R.string.friends_no_match), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("friends-list"), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { "${it.friend.userId}:${it.slot.deckId}:${it.slot.classId}" }) { FriendCard(it, names, now) }
        }
    }
}

@Composable
private fun Picker(allLabel: String, selected: Long?, counts: Map<Long, Int>, name: (Long) -> String,
    onSelect: (Long?) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    // Names are sorted for the menu only; the list order never depends on them.
    val options = remember(counts, name) { counts.entries.map { Triple(it.key, name(it.key), it.value) }.sortedWith(compareBy({ it.second }, { it.first })) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Text(selected?.let(name) ?: allLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { (id, label, count) ->
                DropdownMenuItem(text = { Text(stringResource(R.string.friends_option_count, label, count)) },
                    onClick = { onSelect(id); open = false })
            }
        }
    }
}

@Composable
private fun agoText(unit: AgoUnit, value: Long): String = stringResource(when (unit) {
    AgoUnit.Minutes -> R.string.friends_ago_minutes
    AgoUnit.Hours -> R.string.friends_ago_hours
    AgoUnit.Days -> R.string.friends_ago_days
}, value.toInt())

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendCard(row: FriendRow, names: GameNameMap, now: Long) {
    val friend = row.friend
    val slot = row.slot
    Card(Modifier.fillMaxWidth().testTag("friends-row")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(friend.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.dashboard_level_value, friend.lv), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Text(if (friend.lastLogin > 0) {
                    val (unit, value) = agoParts((now - friend.lastLogin).coerceAtLeast(0))
                    stringResource(R.string.friends_login, agoText(unit, value))
                } else stringResource(R.string.friends_login_unknown), style = MaterialTheme.typography.labelSmall)
            }
            friend.friendCode?.let { code ->
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                // Long-press copies the digits only, so the code can be pasted into the game's search field.
                Text(stringResource(R.string.friends_code, friendCodeText(code)), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("", code))) }
                    }).testTag("friends-code"))
            }
            Text(listOfNotNull(names.servants[slot.svtId] ?: "#${slot.svtId}", classLabel(slot.classId),
                row.deckIndex?.let { stringResource(R.string.friends_deck_number, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.friends_build, slot.lv, slot.np, slot.skills.joinToString("/"),
                slot.appends.joinToString(" ") { it?.toString() ?: "–" }), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (slot.ceId == 0L) stringResource(R.string.friends_ce_none) else names.equips[slot.ceId] ?: "#${slot.ceId}",
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (slot.ceId != 0L && slot.ceLimitCount == FriendList.MAX_LIMIT_COUNT) {
                    Text(stringResource(R.string.friends_ce_max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (slot.ceId != 0L) Text(stringResource(R.string.dashboard_level_value, slot.ceLv), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
