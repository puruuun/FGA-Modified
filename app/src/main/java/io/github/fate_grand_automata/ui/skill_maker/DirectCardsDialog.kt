package io.github.fate_grand_automata.ui.skill_maker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.models.DirectCardSequence

@Composable
internal fun directCardLabel(choice: Char): String = when (choice) {
    'b' -> stringResource(R.string.skill_maker_buster)
    'a' -> stringResource(R.string.skill_maker_arts)
    'q' -> stringResource(R.string.skill_maker_quick)
    else -> stringResource(R.string.skill_maker_direct_np, choice.digitToInt() - 3)
}

@Composable
internal fun DirectCardsSummary(sequence: String) {
    val parsed = DirectCardSequence.parse(sequence)
    Text(parsed.code.map { directCardLabel(it) }.joinToString(" → ") +
        if (parsed.fallback) " (${stringResource(R.string.skill_maker_direct_cards_fallback_summary)})" else "")
}

@Composable
internal fun DirectCardsDialog(
    initialSequence: String,
    initiallyEnabled: Boolean,
    onApply: (Boolean, String) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = DirectCardSequence.parse(initialSequence)
    var sequence by rememberSaveable { mutableStateOf(initial.code) }
    var fallback by rememberSaveable { mutableStateOf(initial.fallback) }
    fun commandSequence() = (if (fallback) "?" else "") + sequence
    var enabled by rememberSaveable { mutableStateOf(initiallyEnabled) }
    val valid = runCatching { DirectCardSequence.parse(sequence) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_maker_direct_cards)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.skill_maker_direct_cards_use), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fallback, onCheckedChange = { fallback = it }, enabled = enabled)
                    Text(stringResource(R.string.skill_maker_direct_cards_fallback))
                }
                repeat(3) { index ->
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.skill_maker_direct_cards_slot, index + 1), modifier = Modifier.padding(end = 12.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { expanded = true },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(directCardLabel(sequence[index])) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                "baq456".forEach { choice ->
                                    val duplicateNp = choice in "456" && sequence.withIndex().any { it.index != index && it.value == choice }
                                    DropdownMenuItem(
                                        text = { Text(directCardLabel(choice)) },
                                        enabled = !duplicateNp,
                                        onClick = {
                                            sequence = sequence.replaceRange(index, index + 1, choice.toString())
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (enabled) DirectCardsSummary(commandSequence())
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(enabled, commandSequence()) }, enabled = !enabled || valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) } }
    )
}
