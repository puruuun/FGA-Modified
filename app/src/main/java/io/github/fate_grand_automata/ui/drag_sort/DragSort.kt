package io.github.fate_grand_automata.ui.drag_sort

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

val DragSortTileHeight = 48.dp
val DragSortTileMaxWidth = 64.dp
val DragSortTileGap = 6.dp
private val DragSortTileShape = RoundedCornerShape(8.dp)

data class DragSortItemStyle(
    val backgroundColor: Color,
    val contentColor: Color,
    val text: String
)

/**
 * A horizontal row of labelled tiles that the user reorders by dragging one sideways. Dragging
 * writes the new order straight into [items].
 *
 * [key] has to be stable per item, since it is what identifies an item across a move. It does
 * not have to be unique - see [uniqueKeys].
 *
 * Dragging starts on a long press. A plain swipe has to stay free for the row's own scrolling,
 * or items that start off-screen can never be brought into view to be dragged at all. The tile
 * being dragged shrinks and vibrates, since the long press is otherwise invisible.
 */
@Composable
fun <T> DragSort(
    items: SnapshotStateList<T>,
    key: (T) -> Any,
    style: @Composable (T) -> DragSortItemStyle,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    val haptics = LocalHapticFeedback.current

    val itemKeys = items.uniqueKeys(key)
    val panelColor = MaterialTheme.colorScheme.surfaceContainer

    BoxWithConstraints(modifier = modifier) {
        // Equal widths that share the row; the cap keeps a few tiles from stretching across a wide screen.
        // Rounded down to whole pixels: tiles that each round up would add up to more than the row has.
        val tileWidth = with(LocalDensity.current) {
            ((maxWidth - DragSortTileGap * (items.size - 1)) / items.size.coerceAtLeast(1))
                .coerceAtMost(DragSortTileMaxWidth)
                .toPx().toInt().toDp()
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(DragSortTileGap)
        ) {
            itemsIndexed(items, key = { index, _ -> itemKeys[index] }) { index, item ->
                ReorderableItem(reorderableState, key = itemKeys[index]) { isDragging ->
                    val itemStyle = style(item)
                    val inset by animateColorAsState(if (isDragging) panelColor else Color.Transparent)

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(tileWidth, DragSortTileHeight)
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                }
                            )
                            .clip(DragSortTileShape)
                            .background(itemStyle.backgroundColor)
                            /*
                             * Painted in the panel colour so the tile reads as shrinking rather than
                             * gaining an outline. The cue has to stay within the tile's own bounds:
                             * the dragged tile is not raised above its neighbours, so a shadow or a
                             * scale would be painted over by the next tile.
                             */
                            .border(2.dp, inset, DragSortTileShape)
                    ) {
                        Text(
                            text = itemStyle.text,
                            color = itemStyle.contentColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * Numbers repeats of the same key apart, so that two equal items still get one key each.
 *
 * A duplicate key crashes [LazyRow], and nothing upstream rules duplicates out: a battle config
 * is free to hold nine identical cards or repeat a team slot. Positional keys are not an option
 * instead, because a key that changes when the list is reordered ends the drag that reordered it.
 */
private fun <T> List<T>.uniqueKeys(key: (T) -> Any): List<String> {
    val occurrences = mutableMapOf<Any, Int>()

    return map { item ->
        val itemKey = key(item)
        val occurrence = occurrences.getOrElse(itemKey) { 0 }
        occurrences[itemKey] = occurrence + 1

        "$itemKey#$occurrence"
    }
}
