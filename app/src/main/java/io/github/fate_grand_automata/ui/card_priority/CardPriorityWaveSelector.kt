package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.ui.PreventRtl
import io.github.fate_grand_automata.util.entryStringRes

/** Wave or turn entries a profile can hold. */
const val CARD_PRIORITY_MAX_ENTRIES = 10

const val WaveAddTag = "waveAdd"
const val WaveRemoveTag = "waveRemove"
const val WaveTabTagPrefix = "waveTab:"
const val WaveTabRowTag = "waveTabRow"

/*
 * Tabs size to their label and the add button is a plain icon button, not a tab. Material's scrollable
 * tab row gives every child a 90dp minimum width, which pushed the "+" out of view at 360dp and left
 * it about 21dp away from the last tab.
 */
@Composable
fun CardPriorityWaveSelector(
    items: SnapshotStateList<CardPriorityListItem>,
    selectedWave: Int,
    split: CardPrioritySplit,
    onSelectedWaveChange: (Int) -> Unit
) {
    PreventRtl {
        val scrollState = rememberScrollState()
        var knownSize by remember { mutableStateOf(items.size) }

        // The row only grows when a wave is added; bring the new tab and the "+" into view.
        LaunchedEffect(items.size) {
            if (items.size > knownSize) {
                // Wait for the frame that lays out the new tab, or maxValue is still the old one.
                withFrameNanos { }
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            knownSize = items.size
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .testTag(WaveTabRowTag)
                    .horizontalScroll(scrollState)
                    .selectableGroup()
            ) {
                items.forEachIndexed { index, _ ->
                    // The pager lags a removal by one animation, so its page can still point past the last tab.
                    val selected = selectedWave.coerceIn(0, items.lastIndex) == index
                    val indicatorColor = MaterialTheme.colorScheme.primary

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .testTag("$WaveTabTagPrefix$index")
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelectedWaveChange(index) })
                            .drawBehind {
                                if (selected) {
                                    val height = 3.dp.toPx()
                                    drawRoundRect(
                                        color = indicatorColor,
                                        topLeft = Offset(12.dp.toPx(), size.height - height),
                                        size = Size(size.width - 24.dp.toPx(), height),
                                        cornerRadius = CornerRadius(height, height)
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            stringResource(split.entryStringRes, index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                AnimatedVisibility(
                    items.size < CARD_PRIORITY_MAX_ENTRIES,
                    enter = slideInHorizontally(initialOffsetX = { it * 2 }),
                    exit = slideOutHorizontally(targetOffsetX = { it * 2 })
                ) {
                    IconButton(
                        onClick = {
                            if (items.size < CARD_PRIORITY_MAX_ENTRIES) {
                                items.add(
                                    CardPriorityListItem(
                                        items[0].scores.toMutableStateList(),
                                        items[0].servantPriority.toMutableStateList(),
                                        items[0].servantCards.toMutableStateList(),
                                        mutableStateOf(false),
                                        mutableStateOf(BraveChainEnum.None),
                                        // A new entry copies the previous entry's style, servant priority switch and Chain Priority.
                                        mutableStateOf(items.last().style.value),
                                        mutableStateOf(items.last().useServantPriority.value),
                                        mutableStateOf(items.last().useChainPriority.value),
                                        items.last().chainPriority.toMutableStateList()
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag(WaveAddTag)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add wave",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(items.size > 1) {
                IconButton(
                    onClick = {
                        if (items.size > 1) {
                            if (selectedWave == items.lastIndex) {
                                onSelectedWaveChange(items.lastIndex - 1)
                            }
                            items.removeAt(items.lastIndex)
                        }
                    },
                    modifier = Modifier.testTag(WaveRemoveTag)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = "Remove wave",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
