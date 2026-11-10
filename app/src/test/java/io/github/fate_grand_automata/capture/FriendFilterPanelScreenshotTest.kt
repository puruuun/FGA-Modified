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
class FriendFilterPanelScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun shot(width: Int, height: Int) = compose.shoot("friend-filter-side", "FilterPanel", width, height, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        compose.onNodeWithTag("friends-ce").performScrollTo().performClick()
        compose.onNodeWithText("샘플 예장 하나 (2)").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        compose.onNodeWithTag("friends-level-90").performScrollTo().performClick()
        compose.onNodeWithTag("friends-deck-main").performClick()
    }) { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) }

    /** Scrolled to the last-login row, with the unit dropdown open to see that it is not clipped. */
    private fun login(width: Int, height: Int) = compose.shoot("friend-filter-side", "FilterPanelLogin", width, height, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        compose.onNodeWithTag("friends-login-amount").performScrollTo()
        compose.onNodeWithTag("friends-login-unit").performClick()
    }) { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) }

    @Test fun login360() = login(360, 800)
    @Test fun login800x360() = login(800, 360)
    @Test fun login1280() = login(1280, 800)

    @Test fun panel360() = shot(360, 800)
    @Test fun panel800x360() = shot(800, 360)
    @Test fun panel1280() = shot(1280, 800)
}
