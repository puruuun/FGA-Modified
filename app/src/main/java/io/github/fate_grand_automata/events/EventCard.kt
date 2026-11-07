package io.github.fate_grand_automata.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.events.*
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.log.LogIconLoader
import io.github.fate_grand_automata.ui.log.LocalLogIconLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EventCard(data: EventData, now: Long, openEvent: (Long) -> Unit) {
    val running = data.running(now)
    if (!running.visible) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("events-card")) {
        Text(stringResource(R.string.events_title), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        running.limited.forEach { event ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable { openEvent(event.id) }.testTag("event-${event.id}")) {
                data.banners[event.id]?.let { EventBanner(event.id, it) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(event.name, style = MaterialTheme.typography.titleSmall)
                    val (unit, value) = remainingParts(event.endedAt - now)
                    Text(stringResource(if (unit == RemainingUnit.Days) R.string.events_days_left else R.string.events_hours_left, value),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(eventEndText(event.endedAt), style = MaterialTheme.typography.bodySmall)
                    data.bonuses[event.id]?.takeIf { it.isNotEmpty() }?.let { equips ->
                        Text(stringResource(R.string.events_bonus_count, equips.size), style = MaterialTheme.typography.bodyMedium)
                        BonusTags(equips.flatMap { it.kinds }.toSet())
                    }
                }
            }
        }
        if (running.campaigns.isNotEmpty()) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp).testTag("events-campaigns"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (running.campaigns.size == 1) running.campaigns.first().name
                    else stringResource(R.string.events_campaigns, running.campaigns.size, running.campaigns.first().name),
                    style = MaterialTheme.typography.bodyMedium)
                if (expanded) running.campaigns.forEach { campaign ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(campaign.name, style = MaterialTheme.typography.bodyMedium)
                        Text(eventEndText(campaign.endedAt), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventBanner(id: Long, url: String) {
    val context = LocalContext.current
    var file by remember(id, url) { mutableStateOf<File?>(null) }
    var ratio by remember(id, url) { mutableFloatStateOf(3f) }
    var loaded by remember(id, url) { mutableStateOf(false) }
    var failed by remember(id, url) { mutableStateOf(false) }
    LaunchedEffect(id, url) { file = LogIconLoader.files(context, "events").resolve(id, url) }
    if (file != null && !failed) AsyncImage(model = file!!.toURI().toString(),
        imageLoader = LocalLogIconLoader.current ?: LogIconLoader.get(context),
        contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio).testTag(if (loaded) "event-banner-loaded" else "event-banner-loading"),
        onSuccess = { state ->
            val image = state.result.image
            if (image.width > 0 && image.height > 0) ratio = image.width.toFloat() / image.height
            loaded = true
        }, onError = { failed = true })
}

@Composable
internal fun BonusTags(kinds: Set<BonusKind>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BonusKind.entries.filter { it in kinds }.forEach { kind ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(when (kind) {
                    BonusKind.Drop -> R.string.events_bonus_drop
                    BonusKind.Point -> R.string.events_bonus_point
                    BonusKind.Bond -> R.string.events_bonus_bond
                    BonusKind.Encounter -> R.string.events_bonus_encounter
                    BonusKind.Other -> R.string.events_bonus_other
                }), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun eventEndText(seconds: Long): String {
    val format = SimpleDateFormat(stringResource(R.string.events_end_pattern), Locale.getDefault())
    return stringResource(R.string.events_ends_at, format.format(Date(seconds * 1000)))
}

@Composable
fun EventBonusScreen(id: Long, vm: EventViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.loadNames(id) }
    EventBonusContent(data.events.orEmpty().find { it.id == id }?.name ?: "#$id", data.bonuses[id].orEmpty(), names)
}

@Composable
internal fun EventBonusContent(name: String, equips: List<BonusEquip>, names: GameNameMap) {
    val sorted = remember(equips, names.equips) { equips.sortedWith(compareBy<BonusEquip> { names.equips[it.id] ?: "#${it.id}" }.thenBy { it.id }) }
    CappedWidth {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Heading(stringResource(R.string.events_bonus_title)) }
            item { Text(name, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
            items(sorted, key = { it.id }) { equip ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("event-bonus-row"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(names.equips[equip.id] ?: "#${equip.id}")
                    BonusTags(equip.kinds)
                }
                HorizontalDivider()
            }
        }
    }
}
