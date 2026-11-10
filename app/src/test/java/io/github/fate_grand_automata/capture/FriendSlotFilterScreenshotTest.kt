package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.more.FriendManagerContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class FriendSlotFilterScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun shot(width: Int, height: Int, menu: Boolean) = compose.shoot("friend-slot-filters",
        if (menu) "SlotMenu" else "SlotFilters", width, height, locale = "ko", density = "mdpi", beforeCapture = {
            compose.onNodeWithTag("friends-filter").performClick()
            compose.onNodeWithTag("friends-servant").performClick()
            compose.onNodeWithTag("friends-pick-1").performClick()
            compose.onNodeWithTag("friends-pick-2").performClick()
            compose.onNodeWithTag("friends-picker-done").performClick()
            compose.onNodeWithTag("friends-skill-1").performScrollTo().performClick()
            compose.onNodeWithTag("friends-skill-1-Ten").performClick()
            compose.onNodeWithTag("friends-append-2").performScrollTo().performClick()
            compose.onNodeWithTag("friends-append-2-NotTen").performClick()
            compose.onNode(hasText("어펜드2 10 아님") and isSelected() and
                hasAnyAncestor(hasTestTag("friends-filter-panel"))).performScrollTo().assertIsDisplayed()
            if (menu) compose.onNodeWithTag("friends-append-2").performClick()
        }) { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) }

    @Test fun filters360() = shot(360, 800, false)
    @Test fun filters800x360() = shot(800, 360, false)
    @Test fun filters1280() = shot(1280, 800, false)
    @Test fun menu360() = shot(360, 800, true)
    @Test fun menu800x360() = shot(800, 360, true)
    @Test fun menu1280() = shot(1280, 800, true)
}
