package io.github.fate_grand_automata.ui.card_priority

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class CardPriorityRowTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun viewModel(name: String) = CardPriorityViewModel(
        PrefsCore(PrefMaker(context.getSharedPreferences(name, Context.MODE_PRIVATE)), context),
        name,
        profileIndex = 0
    )

    // boundsInRoot is clipped to the visible part of a scrolling parent, which would hide a node that is cut off.
    private fun bounds(tag: String): Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    @Test
    fun `the add button sits right after the last tab and follows it when a wave is added`() {
        val vm = viewModel("cp611_add")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        val lastTab = "$WaveTabTagPrefix${vm.cardPriorityItems.lastIndex}"
        val firstAddLeft = bounds(WaveAddTag).left
        assertTrue(firstAddLeft >= bounds(lastTab).right)
        // Closer than one tab width, so it is not parked at the screen edge.
        assertTrue(firstAddLeft - bounds(lastTab).right < bounds(lastTab).width)

        compose.onNodeWithTag(WaveAddTag).performClick()
        compose.waitForIdle()

        val newLastTab = "$WaveTabTagPrefix${vm.cardPriorityItems.lastIndex}"
        assertEquals(2, vm.cardPriorityItems.size)
        assertTrue(bounds(WaveAddTag).left >= bounds(newLastTab).right)
        assertTrue(bounds(WaveAddTag).left > firstAddLeft)
    }

    private fun px(dp: Int) = with(compose.density) { dp.dp.toPx() }

    private fun inside(tag: String) {
        val row = bounds(WaveTabRowTag)
        val node = bounds(tag)
        assertTrue("$tag $node outside $row", node.left >= row.left && node.right <= row.right)
    }

    @Test
    fun `at phone width three waves and the add button fit without scrolling`() {
        val vm = viewModel("cp611_fit")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        repeat(2) {
            compose.onNodeWithTag(WaveAddTag).performClick()
            compose.waitForIdle()
        }

        assertEquals(3, vm.cardPriorityItems.size)
        inside("${WaveTabTagPrefix}0")
        inside("${WaveTabTagPrefix}2")
        inside(WaveAddTag)
        assertTrue(bounds(WaveAddTag).left - bounds("${WaveTabTagPrefix}2").right <= px(8))
    }

    @Test
    fun `adding a wave beyond the visible width scrolls the new tab and the add button into view`() {
        val vm = viewModel("cp611_scroll")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        repeat(5) {
            compose.onNodeWithTag(WaveAddTag).performClick()
            compose.waitForIdle()
        }

        assertEquals(6, vm.cardPriorityItems.size)
        inside("${WaveTabTagPrefix}5")
        inside(WaveAddTag)
    }

    @Test
    fun `the add button is gone at the entry limit`() {
        val vm = viewModel("cp611_limit")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        repeat(CARD_PRIORITY_MAX_ENTRIES + 2) {
            if (compose.onAllNodesWithTag(WaveAddTag).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag(WaveAddTag).performClick()
            }
            compose.waitForIdle()
        }

        assertEquals(CARD_PRIORITY_MAX_ENTRIES, vm.cardPriorityItems.size)
        assertTrue(compose.onAllNodesWithTag(WaveAddTag).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the tiles carry no position numbers`() {
        val vm = viewModel("cp611_numbers")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        val numberOnly = SemanticsMatcher("text is only a number") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim().matches(Regex("\\d+")) } == true
        }
        compose.onAllNodes(numberOnly).assertCountEquals(0)

        vm.cardPriorityItems.first().scores.forEach {
            compose.onNodeWithText(it.toString()).assertExists()
        }
    }

    @Test
    fun `the hint and the direction labels keep their text`() {
        val vm = viewModel("cp611_text")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        assertEquals("길게 눌러 드래그하여 순서 변경", context.getString(R.string.card_priority_hint_move_item))
        compose.onNodeWithText("길게 눌러 드래그하여 순서 변경").assertExists()
        compose.onNodeWithText("높음").assertExists()
        compose.onNodeWithText("낮음").assertExists()
    }

    @Test
    fun `a long press and drag still reorders the tiles and stores the new order`() {
        val vm = viewModel("cp611_drag")
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        val before = vm.cardPriorityItems.first().scores.map { it.toString() }
        compose.onNodeWithText(before[0]).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(40.dp.toPx(), 0f), delayMillis = 50)
            moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 50)
            up()
        }
        compose.waitForIdle()

        val after = vm.cardPriorityItems.first().scores.map { it.toString() }
        assertEquals(before.sorted(), after.sorted())
        assertTrue("order did not change: $after", before != after)
    }
}
