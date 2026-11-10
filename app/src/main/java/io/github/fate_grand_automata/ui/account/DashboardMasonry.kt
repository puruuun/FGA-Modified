package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/**
 * Measures cards once, in reading order, and places each in the shorter column (left on ties).
 * The stack tags describe the occupied column bounds, rather than fixed groups of cards.
 * Children remain in source order for semantics and initial-section bringIntoView.
 */
@Composable
internal fun DashboardMasonry(wide: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val columns = if (wide) 2 else 1
    Layout(modifier = modifier.semantics { isTraversalGroup = true }, content = {
        content()
        if (wide) repeat(columns) { Box(Modifier.testTag("dashboard-stack:$it")) }
    }) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val columnWidth = (width - gap * (columns - 1)) / columns
        val heights = IntArray(columns)
        val cardCount = measurables.size - if (wide) columns else 0
        val placements = measurables.take(cardCount).map { measurable ->
            val column = heights.indices.minBy { heights[it] }
            val card = measurable.measure(Constraints.fixedWidth(columnWidth))
            val y = heights[column]
            heights[column] += card.height + gap
            Triple(card, column, y)
        }
        val occupied = heights.map { (it - gap).coerceAtLeast(0) }
        val stacks = if (wide) measurables.takeLast(columns).mapIndexed { column, measurable ->
            measurable.measure(Constraints.fixed(columnWidth, occupied[column]))
        } else emptyList()
        layout(width, constraints.constrainHeight(occupied.maxOrNull() ?: 0)) {
            stacks.forEachIndexed { column, stack -> stack.placeRelative(column * (columnWidth + gap), 0) }
            placements.forEach { (card, column, y) -> card.placeRelative(column * (columnWidth + gap), y) }
        }
    }
}
