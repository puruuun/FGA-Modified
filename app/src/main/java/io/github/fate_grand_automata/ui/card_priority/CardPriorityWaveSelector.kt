package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

@Composable
fun CardPriorityWaveSelector(
    items: SnapshotStateList<CardPriorityListItem>,
    selectedWave: Int,
    split: CardPrioritySplit,
    onSelectedWaveChange: (Int) -> Unit
) {
    PreventRtl {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            PrimaryScrollableTabRow(
                // The pager lags a removal by one animation, so its page can still point past the last tab.
                selectedTabIndex = selectedWave.coerceIn(0, items.lastIndex),
                edgePadding = 0.dp,
                divider = {},
                modifier = Modifier
                    .weight(1f)
            ) {
                items.forEachIndexed { index, _ ->
                    Tab(
                        selected = selectedWave == index,
                        onClick = { onSelectedWaveChange(index) },
                        text = { Text(stringResource(split.entryStringRes, index + 1)) },
                        modifier = Modifier.testTag("$WaveTabTagPrefix$index")
                    )
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
                                    // A new entry copies the previous entry's style and servant priority switch.
                                    mutableStateOf(items.last().style.value),
                                    mutableStateOf(items.last().useServantPriority.value)
                                )
                            )
                        }
                    },
                    modifier = Modifier.testTag(WaveAddTag)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add wave"
                    )
                }
            }
        }
    }
}
