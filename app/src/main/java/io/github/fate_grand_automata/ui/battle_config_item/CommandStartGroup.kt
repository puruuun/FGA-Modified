package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private enum class StartColour(val labelRes: Int?, val short: String?) {
    Any(R.string.command_start_cond_any, null),
    B(null, "B"),
    A(null, "A"),
    Q(null, "Q")
}

private const val MAX_COUNT = 5

@Composable
private fun chipColours(colour: StartColour): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (colour) {
        StartColour.B -> if (dark) Color(0xFF8E3B3B) to Color(0xFFFFDAD6) else Color(0xFFFFDAD6) to Color(0xFF8C1D18)
        StartColour.A -> if (dark) Color(0xFF2F4F86) to Color(0xFFD6E3FF) else Color(0xFFD6E3FF) to Color(0xFF1B3F7A)
        StartColour.Q -> if (dark) Color(0xFF2E6B3A) to Color(0xFFCFEFD3) else Color(0xFFCFEFD3) to Color(0xFF1B5E2A)
        StartColour.Any -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun colourLabel(colour: StartColour) = colour.labelRes?.let { stringResource(it) } ?: colour.short.orEmpty()

@Composable
private fun CommandStartConditionRows(
    any: Pref<Int>, buster: Pref<Int>, arts: Pref<Int>, quick: Pref<Int>, combineMode: Pref<CommandStartCombineMode>
) {
    val values = mapOf(
        StartColour.Any to any.remember(),
        StartColour.B to buster.remember(),
        StartColour.A to arts.remember(),
        StartColour.Q to quick.remember()
    )
    var combine by combineMode.remember()
    val used = StartColour.entries.filter { values.getValue(it).value > 0 }
    val unused = StartColour.entries.filter { it !in used }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.command_start_conditions),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            if (used.size >= 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = combine == CommandStartCombineMode.And,
                        onClick = { combine = CommandStartCombineMode.And },
                        label = { Text(stringResource(R.string.command_start_combine_and)) }
                    )
                    FilterChip(
                        selected = combine == CommandStartCombineMode.Or,
                        onClick = { combine = CommandStartCombineMode.Or },
                        label = { Text(stringResource(R.string.command_start_combine_or)) }
                    )
                }
            }
        }
        for (colour in used) {
            val state = values.getValue(colour)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var menu by androidx.compose.runtime.remember { mutableStateOf(false) }
                val (bg, fg) = chipColours(colour)
                Box {
                    AssistChip(
                        onClick = { if (unused.isNotEmpty()) menu = true },
                        label = { Text(colourLabel(colour), color = fg) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = bg)
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (option in unused) {
                            DropdownMenuItem(
                                text = { Text(colourLabel(option)) },
                                onClick = {
                                    menu = false
                                    val value = state.value
                                    state.value = 0
                                    values.getValue(option).value = value
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { state.value = (state.value - 1).coerceAtLeast(1) },
                    enabled = state.value > 1,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(36.dp)
                ) { Text("−") }
                Text(
                    state.value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                OutlinedButton(
                    onClick = { state.value = (state.value + 1).coerceAtMost(MAX_COUNT) },
                    enabled = state.value < MAX_COUNT,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(36.dp)
                ) { Text("+") }
                if (used.size > 1) {
                    IconButton(onClick = { state.value = 0 }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.command_start_remove_condition))
                    }
                }
            }
        }
        if (unused.isNotEmpty()) {
            TextButton(onClick = {
                val colour = unused.first()
                values.getValue(colour).value = if (colour == StartColour.Any && used.isEmpty()) 2 else 1
            }) {
                Text(stringResource(R.string.command_start_add_condition))
            }
        }
        if (used.isNotEmpty()) {
            val joiner = stringResource(
                if (combine == CommandStartCombineMode.And) R.string.command_start_join_and else R.string.command_start_join_or
            )
            val parts = used.map {
                val n = values.getValue(it).value
                when (it) {
                    StartColour.Any -> stringResource(R.string.command_start_sum_any, n)
                    StartColour.B -> stringResource(R.string.command_start_sum_buster, n)
                    StartColour.A -> stringResource(R.string.command_start_sum_arts, n)
                    StartColour.Q -> stringResource(R.string.command_start_sum_quick, n)
                }
            }
            Text(
                stringResource(R.string.command_start_summary, parts.joinToString(joiner)),
                style = MaterialTheme.typography.bodySmall
            )
        }
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
                CommandStartConditionRows(
                    config.commandStartCardCount, config.commandStartBuster, config.commandStartArts,
                    config.commandStartQuick, config.commandStartCombineMode
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
                    CommandStartConditionRows(
                        config.secondaryStartCardCount, config.secondaryStartBuster, config.secondaryStartArts,
                        config.secondaryStartQuick, config.secondaryStartCombineMode
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
