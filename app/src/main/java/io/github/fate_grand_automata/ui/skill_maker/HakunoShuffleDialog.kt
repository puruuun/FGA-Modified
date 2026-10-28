package io.github.fate_grand_automata.ui.skill_maker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.FieldSlot

@Composable
private fun ShuffleNumber(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value.toString()) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            range.forEach { number ->
                DropdownMenuItem(text = { Text(number.toString()) }, onClick = { onChange(number); expanded = false })
            }
        }
    }
}

@Composable
private fun ShuffleColour(value: CardTypeEnum?, onChange: (CardTypeEnum?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        null to stringResource(R.string.hakuno_shuffle_colour_all),
        CardTypeEnum.Buster to stringResource(R.string.skill_maker_buster),
        CardTypeEnum.Arts to stringResource(R.string.skill_maker_arts),
        CardTypeEnum.Quick to stringResource(R.string.skill_maker_quick)
    )
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(when (value) {
                CardTypeEnum.Buster -> "B"
                CardTypeEnum.Arts -> "A"
                CardTypeEnum.Quick -> "Q"
                else -> stringResource(R.string.hakuno_shuffle_colour_all)
            })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (colour, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onChange(colour); expanded = false })
            }
        }
    }
}

@Composable
internal fun HakunoShuffleDialog(
    onApply: (Int, Int, List<AutoSkillAction.ShuffleCondition>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var min by rememberSaveable { mutableIntStateOf(0) }
    var max by rememberSaveable { mutableIntStateOf(10) }
    var rows by rememberSaveable { mutableStateOf(listOf("13")) }
    var stop by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hakuno_shuffle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.hakuno_shuffle_confirmation_off))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hakuno_shuffle_min))
                    ShuffleNumber(min, 0..max) { min = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hakuno_shuffle_max))
                    ShuffleNumber(max, 1..30) { max = it; min = min.coerceAtMost(it) }
                }
                Text(stringResource(R.string.hakuno_shuffle_conditions))
                rows.forEachIndexed { index, row ->
                    val condition = AutoSkillAction.ShuffleCondition.fromCode(row)
                    fun update(value: AutoSkillAction.ShuffleCondition) {
                        rows = rows.toMutableList().apply { set(index, value.toCommandString()) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.hakuno_shuffle_field_slot))
                            ShuffleNumber(condition.slot.position, 1..3) { value ->
                                update(condition.copy(slot = FieldSlot.list[value - 1]))
                            }
                        }
                        ShuffleColour(condition.type) { update(condition.copy(type = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.hakuno_shuffle_at_least))
                            ShuffleNumber(condition.count, 1..5) { value -> update(condition.copy(count = value)) }
                        }
                    }
                    TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }, enabled = rows.size > 1) {
                        Text(stringResource(R.string.hakuno_shuffle_remove))
                    }
                }
                TextButton(onClick = { rows = rows + "13" }) { Text(stringResource(R.string.hakuno_shuffle_add)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = stop, onCheckedChange = { stop = it })
                    Text(stringResource(R.string.hakuno_shuffle_stop))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(min, max, rows.map { AutoSkillAction.ShuffleCondition.fromCode(it) }, stop)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
