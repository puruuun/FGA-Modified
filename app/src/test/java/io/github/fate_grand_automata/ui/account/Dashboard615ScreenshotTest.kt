package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After shots of #615: the Overview with the in-progress block, Formations with parties, and Servants with command codes
 * and card boost. The before shots come from the same file on `origin/main`, where the AFTER-ONLY lines are removed.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Dashboard615ScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_dark={2}")
        fun sizes() = listOf(1280 to 800, 360 to 800).flatMap { (w, h) -> listOf(false, true).map { arrayOf<Any>(w, h, it) } }
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { Round615Fixture() }

    private fun shot(name: String, section: String? = null, scrollTo: String? = null) =
        compose.shoot("dashboard-615", name, width, height, dark, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard-section:3").fetchSemanticsNodes().isEmpty() }
            // The tab is opened by its tag so the page starts at its top, the same on every revision.
            section?.let { compose.onNodeWithTag("dashboard-nav:$it").performClick(); compose.waitForIdle() }
            scrollTo?.let { compose.onNodeWithTag(it).performScrollTo() }
            compose.waitForIdle()
        }) {
            fixture.case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data(), fixture.names, "샘플 별명", {}, {}, userId = "synthetic",
                    friendCount = 24, progress = fixture.progress) // AFTER-ONLY: progress
            } }
        }

    @Test fun overview() = shot("Overview")
    // AFTER-ONLY: on narrow the block is below the first screen, so this view scrolls to it.
    @Test fun overviewProgress() = shot("OverviewProgress", scrollTo = "overview-progress")
    // AFTER-ONLY: the login cells sit in the account block at the bottom of the Overview.
    @Test fun overviewAccount() = shot("OverviewAccount", scrollTo = "overview-account")
    @Test fun formations() = shot("Formations", "Formations")
    @Test fun servants() = shot("Servants", "Servants")
}
