package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.prefs.remember

@Composable
fun PartySelection(
    config: BattleConfigCore,
    modifier: Modifier = Modifier
) {
    var party by config.party.remember()

    val dialog = FgaDialog()

    dialog.build(
        color = MaterialTheme.colorScheme.background
    ) {
        title(stringResource(R.string.p_battle_config_party))

        PartySelectionDialogContent(
            selected = party,
            onSelectedChange = {
                party = it
                dialog.hide()
            }
        )

        buttons(
            showCancel = false,
            okLabel = stringResource(R.string.clear).uppercase(),
            onSubmit = { party = -1 }
        )
    }

    ConfigSummaryCell(
        label = stringResource(R.string.p_battle_config_party),
        onClick = { dialog.show() },
        modifier = modifier
    ) {
        ConfigSummaryValue(
            if (party == -1)
                stringResource(R.string.battle_config_party_default)
            else (party + 1).toString()
        )
    }
}

@Composable
private fun PartySelectionItem(
    text: String,
    isSelected: Boolean,
    onSelectedChange: () -> Unit
) {
    // The visible circle stays 40dp; the 48dp box around it is the touch target.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(
                selected = isSelected,
                onClick = onSelectedChange,
                role = Role.RadioButton
            )
            .testTag("$PartyItemTagPrefix$text")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
        ) {
            Text(
                text,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

const val PartyItemTagPrefix = "partyItem:"

@Composable
fun PartySelectionDialogContent(
    selected: Int,
    onSelectedChange: (Int) -> Unit
) {
    Column {
        val partyRange = 0..14

        partyRange
            .chunked(5)
            .forEach { chunk ->
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    chunk.forEach {
                        PartySelectionItem(
                            text = "${it + 1}",
                            isSelected = selected == it,
                            onSelectedChange = { onSelectedChange(it) }
                        )
                    }
                }
            }
    }
}