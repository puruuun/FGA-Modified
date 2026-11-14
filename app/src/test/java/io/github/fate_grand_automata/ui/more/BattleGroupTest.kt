package io.github.fate_grand_automata.ui.more

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class BattleGroupTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("battle-group-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    @Test fun missionPopupClearSecondsIsIndentedOneLineAndFollowsSwitch() {
        val prefs = prefs()
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    LazyColumn(modifier = Modifier.testTag("battle-group-list")) {
                        battleGroup(prefs)
                    }
                }
            }
        }

        val switchTitle = context.getString(R.string.p_mission_popup_clear)
        val seconds10 = context.getString(R.string.p_mission_popup_clear_seconds_value, 10)
        val seconds11 = context.getString(R.string.p_mission_popup_clear_seconds_value, 11)

        composeRule.onNodeWithTag("battle-group-list")
            .performScrollToNode(hasTestTag("mission-popup-clear-seconds"))

        val rowBounds = composeRule.onNodeWithTag("mission-popup-clear-seconds").fetchSemanticsNode().boundsInRoot
        val titleBounds = composeRule.onNodeWithTag("mission-popup-clear-seconds-title").fetchSemanticsNode().boundsInRoot
        val switchTitleBounds = composeRule.onNodeWithText(switchTitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val minusBounds = composeRule.onNodeWithText("-").fetchSemanticsNode().boundsInRoot
        val plusBounds = composeRule.onNodeWithText("+").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected title visible & indented: switch=$switchTitleBounds row=$rowBounds title=$titleBounds minus=$minusBounds plus=$plusBounds",
            titleBounds.width > 0f && titleBounds.height > 0f && titleBounds.left > switchTitleBounds.left,
        )
        assertEquals(16f * composeRule.density.density, titleBounds.left - switchTitleBounds.left, 2f)

        composeRule.onNodeWithTag("mission-popup-clear-seconds-title").assertIsDisplayed()
        composeRule.onNodeWithText(seconds10).assertIsDisplayed()

        // Switch is off by default: "+" must not change the value.
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText(seconds10).assertIsDisplayed()
        assertEquals(10, prefs.missionPopupClearSeconds.get())

        // Verify one-line layout next to the title (16 dp gap + 20 dp DeltaButton padding, not pushed to the right edge).
        assertEquals(36f * composeRule.density.density, minusBounds.left - titleBounds.right, 2f)
        assertTrue(plusBounds.top < titleBounds.bottom && plusBounds.bottom > titleBounds.top)

        // Toggle the switch on: "+" changes 10 -> 11.
        composeRule.onNodeWithTag("battle-group-list").performScrollToNode(hasText(switchTitle))
        composeRule.onNodeWithText(switchTitle).performClick()
        composeRule.onNodeWithTag("battle-group-list").performScrollToNode(hasTestTag("mission-popup-clear-seconds"))
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText(seconds11).assertIsDisplayed()
        assertEquals(11, prefs.missionPopupClearSeconds.get())
    }
}
