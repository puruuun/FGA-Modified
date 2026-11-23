package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.dialog.multiChoiceList
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.drawable
import io.github.fate_grand_automata.util.stringRes

@Composable
fun Pref<Set<MaterialEnum>>.Materials(modifier: Modifier = Modifier) {
    var selected by remember()

    val dialog = FgaDialog()

    dialog.build {
        var current by remember(selected) { mutableStateOf(selected) }

        Row {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline()
            ) {
                title(stringResource(R.string.p_mats))
            }

            TextButton(
                onClick = { current = emptySet() },
                modifier = Modifier
                    .padding(16.dp, 5.dp)
                    .alignByBaseline(),
                enabled = current.isNotEmpty()
            ) {
                Text(stringResource(R.string.clear_all))
            }
        }

        multiChoiceList(
            selected = current,
            onSelectedChange = { current = it },
            prioritySelected = true,
            items = MaterialEnum.entries.toList()
        ) { mat ->
            Material(mat, size = 32.dp, padding = 0.dp)

            Text(
                stringResource(mat.stringRes),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        buttons(
            onSubmit = { selected = current }
        )
    }

    ConfigSummaryCell(
        label = stringResource(R.string.p_mats),
        onClick = { dialog.show() },
        modifier = modifier.heightIn(min = 55.dp)
    ) {
        MaterialsSummary(materials = selected.toList())
    }
}

@Composable
fun Material(mat: MaterialEnum, size: Dp = 20.dp, padding: Dp = 3.dp) {
    Image(
        painterResource(mat.drawable),
        contentDescription = stringResource(mat.stringRes),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(padding)
            .size(size)
            .clip(CircleShape)
            .border(0.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            .alpha(0.8f)
    )
}

@Composable
fun MaterialsSummary(materials: List<MaterialEnum>) {
    if (materials.isNotEmpty()) {
        LazyRow(
            contentPadding = PaddingValues(vertical = 5.dp),
            modifier = Modifier.height(40.dp)
        ) {
            items(materials) { mat ->
                Material(mat)
            }
        }
    } else {
        ConfigSummaryValue(stringResource(R.string.battle_config_materials_none))
    }
}