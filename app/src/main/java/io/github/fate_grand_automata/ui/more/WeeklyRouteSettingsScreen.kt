package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.prefs.remember
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-server exclusions; chapter switches do not destroy the individual quest choices. */
@Composable
fun WeeklyRouteSettingsScreen(prefs: PrefsCore) {
    val context = LocalContext.current
    var server by remember { mutableStateOf("JP") }
    var data by remember { mutableStateOf<AtlasRouteRepository.Dataset?>(null) }
    var reading by remember { mutableStateOf(true) }
    val expanded = remember(server) { mutableStateMapOf<Int, Boolean>() }
    var disabled by prefs.weeklyRoute.disabled.remember()
    LaunchedEffect(server) {
        data = null
        reading = true
        data = withContext(Dispatchers.IO) { AtlasRouteRepository(File(context.cacheDir, "atlas-weekly")).cached(server) }
        reading = false
    }
    fun setEnabled(key: String, value: Boolean) { disabled = if (value) disabled - key else disabled + key }
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(R.string.weekly_route_settings))
        Row {
            for (region in listOf("JP", "KR")) {
                TextButton(onClick = { server = region }) {
                    RadioButton(selected = server == region, onClick = { server = region })
                    Text(region)
                }
            }
        }
        if (reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!reading && data == null) Text(stringResource(R.string.weekly_route_settings_no_data), Modifier.padding(16.dp))
        val chapters = data?.quests.orEmpty().filter { it.warId !in WeeklyRouteSolver.EXCLUDED_WARS }.sortedWith(WeeklyRouteSolver.order).groupBy { it.warId }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
            chapters.forEach { (war, quests) ->
                val chapterKey = WeeklyCandidateSettings.chapterKey(server, war)
                val enabled = chapterKey !in disabled
                item(key = "war:$war") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enabled, onCheckedChange = { setEnabled(chapterKey, it) })
                        TextButton(onClick = { expanded[war] = expanded[war] != true }, modifier = Modifier.weight(1f)) {
                            Text("${if (expanded[war] == true) "−" else "+"} ${quests.first().chapter.replace('\n', ' ')}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (expanded[war] == true) items(quests, key = { "quest:${it.id}" }) { quest ->
                    val key = WeeklyCandidateSettings.questKey(server, quest.id)
                    Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = key !in disabled, enabled = enabled, onCheckedChange = { setEnabled(key, it) })
                        Text("${quest.spot} / ${quest.name} · AP ${quest.ap}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text(stringResource(R.string.weekly_route_unsupported_chapter), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
