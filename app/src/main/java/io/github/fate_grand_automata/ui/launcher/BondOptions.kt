package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R

@Composable
fun BondOptions(track: Boolean, stop: Boolean, ignored: Set<Int>, supported: Boolean,
                onTrack: (Boolean) -> Unit, onStop: (Boolean) -> Unit, onIgnored: (Set<Int>) -> Unit) {
    Column {
        Text(stringResource(R.string.p_bond), color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        BondCheckbox(track, supported, stringResource(R.string.p_bond_track), onTrack)
        BondCheckbox(stop, supported, stringResource(R.string.p_bond_stop), onStop)
        if (stop) {
            Text(stringResource(R.string.p_bond_ignore), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp))
            listOf(1..3, 4..6).forEach { positions ->
                Row(Modifier.fillMaxWidth()) {
                    positions.forEach { position ->
                        BondCheckbox(position in ignored, supported,
                            stringResource(R.string.p_bond_position, position),
                            { value -> onIgnored(if (value) ignored + position else ignored - position) }, Modifier.weight(1f))
                    }
                }
            }
        }
        Text(stringResource(if (supported) R.string.p_bond_summary else R.string.p_bond_servers),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun BondCheckbox(checked: Boolean, enabled: Boolean, text: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
