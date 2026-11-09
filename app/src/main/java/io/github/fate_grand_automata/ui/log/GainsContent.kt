package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.RunGains
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import io.github.fate_grand_automata.util.grouped
import java.text.DateFormat
import java.util.Date

/** [loadBattles] reads the per-battle rows of a stored entry; without it the rows already in [gains] are used. */
internal fun LazyListScope.gainsContent(gains: RunGains, names: GameNameMap, loadBattles: (suspend () -> List<BattleGains>?)? = null) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_gains_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.capture_gains_battles, gains.capturedBattles.toLong().grouped()))
            if (gains.missingBattles > 0) Text(
                stringResource(R.string.capture_gains_missing, gains.missingBattles.toLong().grouped()),
                color = MaterialTheme.colorScheme.error,
            )
            ResourceGroups(battleResources(gains), names)
            GainLine(stringResource(R.string.capture_gains_exp), gains.masterExp)
            if (gains.bond.isNotEmpty()) Text(stringResource(R.string.capture_gains_bond), style = MaterialTheme.typography.titleSmall)
            gains.bond.entries.sortedByDescending { it.value }.forEach { (id, value) -> GainLine(names.servant(id), value) }
            BattleBreakdown(gains, names, loadBattles)
        }
    }
}

@Composable
private fun GainLine(name: String, count: Long?, prefix: String = "+") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, Modifier.weight(1f))
        Text(count?.let { "$prefix${it.grouped()}" } ?: stringResource(R.string.capture_gains_unknown))
    }
}

@Composable
private fun BattleBreakdown(gains: RunGains, names: GameNameMap, loadBattles: (suspend () -> List<BattleGains>?)?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.capture_gains_collapse else R.string.capture_gains_breakdown))
    }
    if (!expanded) return
    // Read only while the breakdown is open and dropped with the screen (issue #280). Null = still loading.
    val loaded by produceState<Loaded?>(null, gains.storedBattleCount) {
        value = Loaded(if (loadBattles == null) gains.battles else loadBattles())
    }
    val battles = loaded?.battles
    if (loaded == null) CircularProgressIndicator(Modifier.padding(8.dp))
    else if (battles == null) Text(stringResource(R.string.capture_gains_unknown))
    else battles.forEachIndexed { index, battle ->
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.capture_gains_battle, (index + 1).toLong().grouped(),
                battle.questId?.toString() ?: stringResource(R.string.capture_gains_unknown)),
                style = MaterialTheme.typography.titleSmall)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(battle.time * 1000)))
            ResourceGroups(battleResources(RunGains(items = battle.items, qp = battle.qp)), names)
            GainLine(stringResource(R.string.capture_gains_exp), battle.masterExp)
            battle.bond.forEach { (id, value) -> GainLine(names.servant(id), value) }
        }
    }
}

private class Loaded(val battles: List<BattleGains>?)
