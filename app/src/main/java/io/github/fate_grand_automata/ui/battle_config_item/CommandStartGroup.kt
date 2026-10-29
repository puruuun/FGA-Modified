package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.ui.prefs.DropdownPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.StepperPreference
import io.github.fate_grand_automata.ui.prefs.remember

@Composable
private fun CommandStartColours(buster: Pref<Int>, arts: Pref<Int>, quick: Pref<Int>, combineMode: Pref<CommandStartCombineMode>) {
    combineMode.DropdownPreference(
        title = stringResource(R.string.p_command_start_combine_mode),
        entries = mapOf(
            CommandStartCombineMode.And to stringResource(R.string.command_start_combine_and),
            CommandStartCombineMode.Or to stringResource(R.string.command_start_combine_or)
        )
    )
    for ((pref, title) in listOf(
        buster to R.string.p_command_start_buster,
        arts to R.string.p_command_start_arts,
        quick to R.string.p_command_start_quick
    )) {
        pref.StepperPreference(title = stringResource(title), valueRange = 0..5)
    }
}

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
            var mode by config.commandStartMode.remember()
            val enabled = mode == CommandStartMode.CardCount
            fun setEnabled(value: Boolean) {
                mode = if (value) CommandStartMode.CardCount else CommandStartMode.Basic
            }
            Preference(
                title = stringResource(R.string.command_start_mode_card_count),
                summary = stringResource(if (enabled) R.string.config_state_on else R.string.command_start_mode_basic),
                onClick = { setEnabled(!enabled) }
            ) {
                Switch(checked = enabled, onCheckedChange = ::setEnabled)
            }
            if (enabled) {
                config.commandStartServant.DropdownPreference(
                    title = stringResource(R.string.command_start_first_servant),
                    entries = (1..3).associateWith {
                        stringResource(R.string.command_start_servant_slot, it)
                    }
                )
                config.commandStartCardCount.StepperPreference(
                    title = stringResource(R.string.p_command_start_card_count),
                    valueRange = 1..5
                )
                CommandStartColours(
                    config.commandStartBuster, config.commandStartArts, config.commandStartQuick, config.commandStartCombineMode
                )

                var secondEnabled by config.secondaryStartEnabled.remember()
                var secondTarget by config.secondaryStartServant.remember()
                DropdownPreference(
                    title = stringResource(R.string.command_start_second_servant),
                    value = if (secondEnabled) secondTarget else 0,
                    entries = mapOf(0 to stringResource(R.string.command_start_second_disabled)) +
                        (1..3).associateWith { stringResource(R.string.command_start_servant_slot, it) },
                    onValueChange = {
                        if (it == 0) {
                            secondEnabled = false
                        } else {
                            secondTarget = it
                            secondEnabled = true
                        }
                    }
                )
                if (secondEnabled) {
                    config.secondaryStartCardCount.StepperPreference(
                        title = stringResource(R.string.p_command_start_card_count),
                        valueRange = 1..5
                    )
                    CommandStartColours(
                        config.secondaryStartBuster, config.secondaryStartArts, config.secondaryStartQuick, config.secondaryStartCombineMode
                    )
                    val first by config.commandStartServant.remember()
                    if (first == secondTarget) {
                        PreferenceError(stringResource(R.string.command_start_duplicate_targets))
                    }
                }

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
