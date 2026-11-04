package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.util.grouped
import java.text.DateFormat
import java.util.Date

internal fun LazyListScope.gainsContent(gains: RunGains, names: GameNameMap) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.capture_gains_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.capture_gains_battles, gains.capturedBattles.toLong().grouped()))
            if (gains.missingBattles > 0) Text(
                stringResource(R.string.capture_gains_missing, gains.missingBattles.toLong().grouped()),
                color = MaterialTheme.colorScheme.error,
            )
            GainLine(stringResource(R.string.capture_qp), gains.qp)
            GainLine(stringResource(R.string.capture_gains_exp), gains.masterExp)
            Text(stringResource(R.string.capture_gains_items), style = MaterialTheme.typography.titleSmall)
        }
    }
    items(gains.items.sortedByDescending { it.gained }) { item ->
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { DropLine(item, names, showHoldings = true) }
    }
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.capture_gains_bond), style = MaterialTheme.typography.titleSmall)
            gains.bond.entries.sortedByDescending { it.value }.forEach { (id, value) -> GainLine(names.servant(id), value) }
            Text(stringResource(R.string.capture_gains_fruit), style = MaterialTheme.typography.titleSmall)
            RunGains.FRUIT_IDS.forEach { id ->
                GainLine(stringResource(when (id) {
                    100L -> R.string.capture_apples_gold
                    101L -> R.string.capture_apples_silver
                    104L -> R.string.capture_apples_bronze
                    else -> R.string.capture_apples_copper
                }), gains.fruitUsed[id], prefix = "")
            }
            BattleBreakdown(gains, names)
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
private fun DropLine(item: GainedItem, names: GameNameMap, showHoldings: Boolean) {
    GainLine(names.drop(item.type, item.objectId), item.gained)
    val holdings = item.holdings
    if (showHoldings && holdings != null) Text(
        stringResource(R.string.capture_gains_holdings, holdings.grouped()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BattleBreakdown(gains: RunGains, names: GameNameMap) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.capture_gains_collapse else R.string.capture_gains_breakdown))
    }
    if (expanded) gains.battles.forEachIndexed { index, battle ->
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.capture_gains_battle, (index + 1).toLong().grouped(),
                battle.questId?.toString() ?: stringResource(R.string.capture_gains_unknown)),
                style = MaterialTheme.typography.titleSmall)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(battle.time * 1000)))
            battle.items.sortedByDescending { it.gained }.forEach { DropLine(it, names, showHoldings = false) }
            GainLine(stringResource(R.string.capture_qp), battle.qp)
            GainLine(stringResource(R.string.capture_gains_exp), battle.masterExp)
            battle.bond.forEach { (id, value) -> GainLine(names.servant(id), value) }
        }
    }
}
