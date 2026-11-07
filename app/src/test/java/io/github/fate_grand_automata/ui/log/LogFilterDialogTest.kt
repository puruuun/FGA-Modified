package io.github.fate_grand_automata.ui.log

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.AtlasBasic
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w1280dp-h800dp-hdpi")
class LogFilterDialogTest {
    private val rule = createComposeRule()
    @get:Rule val rules = screenshotRules(rule)

    private val names = GameNameMap(svtData = (1L..20L).associateWith { AtlasBasic(it, if (it % 2 == 0L) "Apple $it" else "Banana $it", "normal") })
    private val keys = (1L..20L).map { "${LogResource.SVT}:$it" }.toSet()

    private fun show(selected: String? = null) = rule.setContent {
        FGATheme(darkTheme = false) {
            LogFilterDialog(LogTimeFilter.All, selected, null, null, 200, 0, 0, keys, names, {}, { _, _, _, _, _ -> })
        }
    }

    @Test fun dialogIsWiderThanThePlatformDefaultAtLargeWidths() {
        show()
        val width = rule.onNodeWithTag("log-filter-dialog").fetchSemanticsNode().boundsInRoot.width
        val density = rule.density.density
        // About 90% of 1280 dp is capped at 900 dp; the platform default would be around 560 dp.
        assertEquals(900f, width / density, 2f)
    }

    @Test fun resourceChipsWrapHorizontally() {
        show()
        val first = rule.onNodeWithTag("log-filter-resource:-1:10").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("log-filter-resource:-1:12").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertTrue(second.left >= first.right)
    }

    @Test fun searchNarrowsTheListWhileAllAndTheSelectedChipStay() {
        show(selected = "${LogResource.SVT}:1")
        rule.onNodeWithTag("log-filter-search").performTextInput("apple")
        rule.onNodeWithText("Apple 2").assertExists()
        rule.onNodeWithText("Banana 3").assertDoesNotExist()
        rule.onNodeWithText("Banana 1").assertExists()
        rule.onNodeWithText("All").assertExists()
    }

    @Test fun blankQueryShowsEverything() {
        show()
        rule.onNodeWithTag("log-filter-search").performTextInput("apple")
        rule.onNodeWithText("Banana 3").assertDoesNotExist()
        rule.onNodeWithTag("log-filter-search").performTextClearance()
        rule.onNodeWithTag("log-filter-search").performTextInput("   ")
        rule.onNodeWithText("Banana 3").assertExists()
        rule.onNodeWithText("Apple 2").assertExists()
    }
}
