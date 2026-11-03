package io.github.fate_grand_automata.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import io.github.fate_grand_automata.ui.ListDetailPanes
import io.github.fate_grand_automata.ui.LocalNavigateBack

/**
 * Shows the battle config list and the screen on top of it side by side when [decidePanes] says
 * so, and returns null otherwise, which leaves the single pane strategy to the next in line.
 *
 * Navigation 3 hands over the entries of the back stack from the bottom, one per key, so the keys
 * are read from [backStack] by position.
 *
 * [leaveList] is what the back arrow of the list pane does. In list-detail mode the list is not the
 * top of the stack, so the default arrow would only close the open config; this one leaves the
 * whole flow, as the arrow of the list does on a phone.
 */
class ListDetailSceneStrategy(
    private val width: Dp,
    private val height: Dp,
    private val backStack: List<NavKey>,
    private val leaveList: () -> Unit
) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>
    ): Scene<NavKey>? {
        if (entries.isEmpty() || entries.size > backStack.size) return null

        val stack = backStack.subList(0, entries.size)
        val decision = decidePanes(width, height, stack) as? PaneDecision.TwoPane ?: return null

        val listIndex = stack.indexOfLast { it is Route.BattleConfigList }

        return ListDetailScene(
            listEntry = entries[listIndex],
            detailEntry = if (decision.detail != null) entries.last() else null,
            previousEntries = entries.dropLast(1),
            leaveList = leaveList
        )
    }
}

private class ListDetailScene(
    private val listEntry: NavEntry<NavKey>,
    private val detailEntry: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val leaveList: () -> Unit
) : Scene<NavKey> {
    /**
     * Stays the same while the screen above the list changes, so Navigation 3 does not slide the
     * whole screen. Only the right pane crossfades.
     */
    override val key: Any = ListDetailSceneKey

    override val entries: List<NavEntry<NavKey>> = listOfNotNull(listEntry, detailEntry)

    override val content: @Composable () -> Unit = {
        ListDetailPanes(
            list = {
                CompositionLocalProvider(LocalNavigateBack provides leaveList) {
                    listEntry.Content()
                }
            },
            detail = detailEntry?.let { entry -> { entry.Content() } },
            detailKey = detailEntry?.contentKey
        )
    }
}

private data object ListDetailSceneKey
