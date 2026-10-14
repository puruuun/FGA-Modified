package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BondPrefsCore
import io.github.fate_grand_automata.ui.prefs.Preference
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
            var watched by config.stopCharacters.remember()
            if (stop) {
                PreferenceGroupHeader(title = stringResource(R.string.p_bond_stop_characters))
                for (character in 1..5) {
                    fun setWatched(value: Boolean) {
                        watched = if (value) watched + character else watched - character
                    }
                    Preference(
                        title = stringResource(R.string.p_bond_position, character),
                        onClick = { setWatched(character !in watched) }
                    ) {
                        Checkbox(checked = character in watched, onCheckedChange = ::setWatched)
                    }
                }
            }
            Preference(
                title = stringResource(R.string.p_bond_servers),
                summary = stringResource(R.string.p_bond_summary)
            )
        }
    }
}
