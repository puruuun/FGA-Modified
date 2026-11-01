package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.prefs.remember

/** Per battle config: retreat from the quest when the battle reaches turn N (KR and JP only). */
@Composable
fun TurnLimitRetreat(
    modifier: Modifier = Modifier,
    config: BattleConfigCore,
    supported: Boolean
) {
    var enabled by config.turnLimitRetreatEnabled.remember()
    var turns by config.turnLimitRetreatTurns.remember()
    var stopAfterStreak by config.turnLimitRetreatStopAfterStreak.remember()

    val dialog = FgaDialog()

    dialog.build(
        color = MaterialTheme.colorScheme.background
    ) {
        var currentEnabled by remember(enabled) { mutableStateOf(enabled) }
        var currentTurns by remember(turns) { mutableStateOf(turns) }
        var currentStop by remember(stopAfterStreak) { mutableStateOf(stopAfterStreak) }

        Row {
            Box(modifier = Modifier.weight(1f).alignByBaseline()) {
                title(stringResource(R.string.p_battle_config_turn_limit_retreat))
            }

            TextButton(
                onClick = {
                    currentEnabled = config.turnLimitRetreatEnabled.defaultValue
                    currentTurns = config.turnLimitRetreatTurns.defaultValue
                    currentStop = config.turnLimitRetreatStopAfterStreak.defaultValue
                },
                modifier = Modifier.padding(16.dp, 5.dp).alignByBaseline()
            ) {
                Text(stringResource(id = R.string.reset).uppercase())
            }
        }

        message(
            text = stringResource(
                if (supported) R.string.p_battle_config_turn_limit_retreat_message
                else R.string.p_battle_config_turn_limit_retreat_unsupported
            )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = currentEnabled && supported,
                    onCheckedChange = { currentEnabled = it },
                    enabled = supported
                )
                Text(
                    stringResource(R.string.config_state_on).uppercase(),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            Stepper(
                value = currentTurns,
                onValueChange = { currentTurns = it },
                valueRange = 1..99,
                enabled = supported && currentEnabled
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = currentStop,
                    onCheckedChange = { currentStop = it },
                    enabled = supported && currentEnabled
                )
                Text(stringResource(R.string.p_battle_config_turn_limit_retreat_streak))
            }

            buttons(
                onSubmit = {
                    enabled = currentEnabled
                    turns = currentTurns
                    stopAfterStreak = currentStop
                },
                okLabel = stringResource(R.string.save),
            )
        }
    }

    ConfigSummaryCell(
        label = stringResource(R.string.p_battle_config_turn_limit_retreat_label),
        onClick = { dialog.show() },
        modifier = modifier
    ) {
        ConfigSummaryValue(
            if (enabled && supported) stringResource(R.string.turn_limit_retreat_turns_value, turns)
            else stringResource(R.string.config_state_off)
        )
    }
}
