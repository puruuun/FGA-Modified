package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BondPrefsCore
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember

@Composable
fun BondGroup(config: BondPrefsCore) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column {
            PreferenceGroupHeader(title = stringResource(R.string.p_bond))
            config.track.SwitchPreference(title = stringResource(R.string.p_bond_track))
            config.stopOnMax.SwitchPreference(title = stringResource(R.string.p_bond_stop))
            val stop by config.stopOnMax.remember()
            var ignored by config.ignoredCharacters.remember()
            if (stop) {
                PreferenceGroupHeader(title = stringResource(R.string.p_bond_ignore_characters))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    for (character in 1..5) {
                        Column(
                            modifier = Modifier.weight(1f).toggleable(
                                value = character in ignored,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    ignored = if (checked) ignored + character else ignored - character
                                }
                            ).padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.p_bond_position, character),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                            Checkbox(checked = character in ignored, onCheckedChange = null)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
