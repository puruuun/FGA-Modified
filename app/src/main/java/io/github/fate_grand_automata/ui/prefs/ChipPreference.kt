package io.github.fate_grand_automata.ui.prefs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.ui.VectorIcon
import io.github.fate_grand_automata.util.toggle

@Composable
fun ChipPreferenceItem(
    text: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    StatusWrapper(enabled) {
        FilterChip(
            selected = isSelected,
            onClick = onSelect,
            enabled = enabled,
            label = {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = if (isSelected) {
                {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else null,
            modifier = modifier
                .defaultMinSize(minWidth = 30.dp)
        )
    }
}

@Composable
fun <T> SingleSelectChip(
    title: String,
    selected: T,
    onSelectedChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    entries: Map<T, String> = emptyMap(),
    enabled: Boolean = true,
    singleRow: Boolean = false
) {
    Preference(
        title = { Text(title) },
        summary = {
            if (singleRow) {
                // The chips share the width evenly instead of wrapping, so a narrow
                // column or a long translation can't push one of them onto its own line.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entries.forEach { (key, value) ->
                        ChipPreferenceItem(
                            text = value,
                            isSelected = key == selected,
                            onSelect = { onSelectedChange(key) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    entries.forEach { (key, value) ->
                        ChipPreferenceItem(
                            text = value,
                            isSelected = key == selected,
                            onSelect = { onSelectedChange(key) },
                            enabled = enabled
                        )
                    }
                }
            }
        },
        icon = icon,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
fun <T> Pref<T>.SingleSelectChipPreference(
    title: String,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    entries: Map<T, String> = emptyMap(),
    enabled: Boolean = true,
    singleRow: Boolean = false
) {
    var selected by remember()

    SingleSelectChip(
        title = title,
        selected = selected,
        onSelectedChange = { selected = it },
        icon = icon,
        entries = entries,
        enabled = enabled,
        modifier = modifier,
        singleRow = singleRow
    )
}

@Composable
fun <T> MultiSelectChip(
    title: String,
    selected: Set<T>,
    onSelectedChange: (Set<T>) -> Unit,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    entries: Map<T, String> = emptyMap(),
    enabled: Boolean = true
) {
    Preference(
        title = { Text(title) },
        summary = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                entries.forEach { (key, value) ->
                    ChipPreferenceItem(
                        text = value,
                        isSelected = key in selected,
                        onSelect = { onSelectedChange(selected.toggle(key)) }
                    )
                }
            }
        },
        icon = icon,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
fun <T> Pref<Set<T>>.MultiSelectChipPreference(
    title: String,
    modifier: Modifier = Modifier,
    icon: VectorIcon? = null,
    entries: Map<T, String> = emptyMap(),
    enabled: Boolean = true
) {
    var selected by remember()

    MultiSelectChip(
        title = title,
        selected = selected,
        onSelectedChange = { selected = it },
        icon = icon,
        entries = entries,
        enabled = enabled,
        modifier = modifier
    )
}