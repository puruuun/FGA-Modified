package io.github.fate_grand_automata.ui.main

import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.assertFailsWith
import assertk.assertions.isNull
import kotlin.test.Test

class ListDetailTest {
    private val a = Route.BattleConfigItem("a")
    private val b = Route.BattleConfigItem("b")
    private val cardPriorityA = Route.CardPriority("a", 1)

    private val listStack: List<NavKey> = listOf(Route.Home, Route.BattleConfigList)
    private val openA: List<NavKey> = listStack + a

    private fun decide(width: Int, height: Int, stack: List<NavKey>) =
        decidePanes(width.dp, height.dp, stack)

    // --- pane decision ---

    @Test
    fun `phone portrait keeps a single pane`() {
        assertThat(decide(360, 800, openA)).isEqualTo(PaneDecision.Single)
        assertThat(decide(360, 800, listStack)).isEqualTo(PaneDecision.Single)
    }

    @Test
    fun `just below the wide width keeps a single pane`() {
        assertThat(decide(839, 600, openA)).isEqualTo(PaneDecision.Single)
    }

    @Test
    fun `phone landscape keeps a single pane because it is too short`() {
        assertThat(decide(900, 400, openA)).isEqualTo(PaneDecision.Single)
        assertThat(decide(900, 479, listStack)).isEqualTo(PaneDecision.Single)
    }

    @Test
    fun `wide window with only the list shows two panes with no detail`() {
        assertThat(decide(1067, 600, listStack)).isEqualTo(PaneDecision.TwoPane(null))
    }

    @Test
    fun `wide window shows the open config on the right`() {
        assertThat(decide(1067, 600, openA)).isEqualTo(PaneDecision.TwoPane(a))
        assertThat(decide(840, 480, openA)).isEqualTo(PaneDecision.TwoPane(a))
    }

    @Test
    fun `a sub screen replaces the config on the right`() {
        assertThat(decide(1067, 600, openA + cardPriorityA)).isEqualTo(PaneDecision.TwoPane(cardPriorityA))
        assertThat(decide(1067, 600, openA + Route.Spam("a"))).isEqualTo(PaneDecision.TwoPane(Route.Spam("a")))
        assertThat(decide(1067, 600, openA + Route.PreferredSupport("a")))
            .isEqualTo(PaneDecision.TwoPane(Route.PreferredSupport("a")))
    }

    @Test
    fun `other flows stay single pane even on a wide window`() {
        assertThat(decide(1067, 600, listOf(Route.Home, Route.MoreOptions, Route.FineTune)))
            .isEqualTo(PaneDecision.Single)
        assertThat(decide(1067, 600, listOf(Route.Home))).isEqualTo(PaneDecision.Single)
    }

    @Test
    fun `a stack with something else above the list stays single pane`() {
        assertThat(decide(1067, 600, openA + Route.License)).isEqualTo(PaneDecision.Single)
    }

    @Test
    fun `window tiers`() {
        assertThat(windowTier(360.dp, 800.dp)).isEqualTo(WindowTier.Compact)
        assertThat(windowTier(600.dp, 800.dp)).isEqualTo(WindowTier.Medium)
        assertThat(windowTier(900.dp, 400.dp)).isEqualTo(WindowTier.Medium)
        assertThat(windowTier(840.dp, 480.dp)).isEqualTo(WindowTier.Wide)
    }

    // --- openFromList ---

    @Test
    fun `opening from the list with a config and sub screen open replaces them`() {
        val stack = openA + cardPriorityA

        assertThat(openFromList(stack, "b")).isEqualTo(listStack + b)
    }

    @Test
    fun `opening from the bare list pushes the config`() {
        assertThat(openFromList(listStack, "a")).isEqualTo(openA)
    }

    @Test
    fun `opening without a list on the stack is an error`() {
        assertFailsWith<IllegalStateException> { openFromList(listOf(Route.Home), "a") }
    }

    // --- selected id ---

    @Test
    fun `nothing selected when nothing is open`() {
        assertThat(selectedConfigId(listStack)).isNull()
        assertThat(selectedConfigId(listOf(Route.Home))).isNull()
    }

    @Test
    fun `the open config is selected, also while a sub screen is open`() {
        assertThat(selectedConfigId(openA)).isEqualTo("a")
        assertThat(selectedConfigId(openA + cardPriorityA)).isEqualTo("a")
    }

    // --- afterDelete ---

    @Test
    fun `deleting the open config closes it and its sub screens`() {
        assertThat(afterDelete(openA, setOf("a"))).isEqualTo(listStack)
        assertThat(afterDelete(openA + cardPriorityA, setOf("x", "a"))).isEqualTo(listStack)
    }

    @Test
    fun `deleting other configs leaves the stack alone`() {
        assertThat(afterDelete(openA, setOf("b", "c"))).isEqualTo(openA)
        assertThat(afterDelete(listStack, setOf("a"))).isEqualTo(listStack)
    }

    // --- replaceWith ---

    @Test
    fun `replaceWith keeps the common start and applies the rest`() {
        val stack = (openA + cardPriorityA).toMutableList()

        stack.replaceWith(listStack + b)

        assertThat(stack.toList()).isEqualTo(listStack + b)
    }
}
