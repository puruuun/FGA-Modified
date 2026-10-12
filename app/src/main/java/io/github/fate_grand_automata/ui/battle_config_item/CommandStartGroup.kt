package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.ui.prefs.DropdownPreference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.StepperPreference
import io.github.fate_grand_automata.ui.prefs.remember

@Composable
fun CommandStartGroup(config: BattleConfigCore) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column {
            PreferenceGroupHeader(title = stringResource(R.string.p_command_start))
            config.commandStartMode.DropdownPreference(
                title = stringResource(R.string.p_command_start_mode),
                entries = mapOf(
                    CommandStartMode.Basic to stringResource(R.string.command_start_mode_basic),
                    CommandStartMode.CardCount to stringResource(R.string.command_start_mode_card_count)
                )
            )

            val mode by config.commandStartMode.remember()
            if (mode == CommandStartMode.CardCount) {
                Text(
                    stringResource(R.string.command_start_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                config.commandStartServant.DropdownPreference(
                    title = stringResource(R.string.p_command_start_servant),
                    entries = (1..3).associateWith {
                        stringResource(R.string.command_start_servant_slot, it)
                    }
                )
                config.commandStartCardCount.StepperPreference(
                    title = stringResource(R.string.p_command_start_card_count),
                    valueRange = 1..5
                )
                val unlimited = stringResource(R.string.command_start_unlimited)
                val turns = stringResource(R.string.command_start_turns)
                config.commandStartMaxTurns.StepperPreference(
                    title = stringResource(R.string.p_command_start_max_turns),
                    valueRange = 0..99,
                    valueRepresentation = { if (it == 0) unlimited else "$it $turns" }
                )
            }
        }
    }
}
