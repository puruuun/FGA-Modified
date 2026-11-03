package io.github.fate_grand_automata.ui.main

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey

/*
 * The pure half of the battle config list-detail layout: which window sizes get two panes and the
 * back stack edits that go with them. Nothing here touches Compose or Navigation 3 UI, so it is
 * unit-tested directly (ListDetailTest).
 */

/** Narrowest window that gets the Medium layout (capped, centred screens, two-column config). */
val MediumMinWidth = 600.dp

/** Narrowest window that gets list-detail panes. */
val WideMinWidth = 840.dp

/**
 * Shortest window that gets list-detail panes. A phone in landscape is wide enough but not tall
 * enough, and two panes would leave the detail no room, so it stays on one pane.
 */
val WideMinHeight = 480.dp

/** Width of the battle config list pane in list-detail mode. */
val ListPaneWidth = 360.dp

enum class WindowTier { Compact, Medium, Wide }

fun windowTier(width: Dp, height: Dp): WindowTier = when {
    width >= WideMinWidth && height >= WideMinHeight -> WindowTier.Wide
    width >= MediumMinWidth -> WindowTier.Medium
    else -> WindowTier.Compact
}

/** How the battle config flow is laid out for the current back stack and window. */
sealed interface PaneDecision {
    /** The default: the top entry fills the window. */
    data object Single : PaneDecision

    /**
     * The battle config list on the left and [detail] on the right. [detail] is null when nothing
     * is open yet, and the right pane shows a hint.
     */
    data class TwoPane(val detail: Route.BattleConfig?) : PaneDecision
}

/**
 * Two panes apply in the Wide tier when the stack holds [Route.BattleConfigList] and everything
 * above it is a [Route.BattleConfig]. The right pane shows the topmost entry, so a sub-screen
 * such as Card Priority replaces the config it belongs to, as it does on a phone.
 */
fun decidePanes(width: Dp, height: Dp, stack: List<NavKey>): PaneDecision {
    if (windowTier(width, height) != WindowTier.Wide) return PaneDecision.Single

    val listIndex = stack.indexOfLast { it is Route.BattleConfigList }
    if (listIndex < 0) return PaneDecision.Single

    val above = stack.subList(listIndex + 1, stack.size)
    if (above.any { it !is Route.BattleConfig }) return PaneDecision.Single

    return PaneDecision.TwoPane(above.lastOrNull() as Route.BattleConfig?)
}

/**
 * The stack after opening config [id] from the list: whatever was above the list (a config and its
 * sub-screens) is dropped and the config goes on top. On a phone nothing is above the list when it
 * is visible, so this is a plain push there.
 */
fun openFromList(stack: List<NavKey>, id: String): List<NavKey> {
    val listIndex = stack.indexOfLast { it is Route.BattleConfigList }
    check(listIndex >= 0) {
        "Route.BattleConfigList not found below $id on the back stack"
    }

    return stack.subList(0, listIndex + 1) + Route.BattleConfigItem(id)
}

/**
 * The config the list should highlight: the first [Route.BattleConfigItem] above the list, which
 * stays the same while its sub-screens are open. Null when nothing is open.
 */
fun selectedConfigId(stack: List<NavKey>): String? {
    val listIndex = stack.indexOfLast { it is Route.BattleConfigList }
    if (listIndex < 0) return null

    return stack
        .subList(listIndex + 1, stack.size)
        .firstOrNull { it is Route.BattleConfigItem }
        ?.let { (it as Route.BattleConfigItem).id }
}

/**
 * The stack after deleting the configs [deletedIds] from the list: if the open config is among
 * them, everything above the list goes too. Otherwise the stack is unchanged.
 */
fun afterDelete(stack: List<NavKey>, deletedIds: Set<String>): List<NavKey> {
    val open = selectedConfigId(stack) ?: return stack
    if (open !in deletedIds) return stack

    val listIndex = stack.indexOfLast { it is Route.BattleConfigList }
    return stack.subList(0, listIndex + 1).toList()
}

/**
 * Makes this list equal to [target] with the fewest changes: the common start is kept, so the
 * entries there (and their saved state) are not recreated.
 */
fun <T> MutableList<T>.replaceWith(target: List<T>) {
    var common = 0
    while (common < size && common < target.size && this[common] == target[common]) common++

    while (size > common) removeAt(size - 1)
    addAll(target.subList(common, target.size))
}
