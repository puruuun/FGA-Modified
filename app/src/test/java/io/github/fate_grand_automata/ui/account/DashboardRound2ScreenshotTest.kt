package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
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
 * Before and after shots of round 2 (#610). Only the public content composable and test tags are used, so the same
 * source runs on `origin/main`, where the new tags are simply absent.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardRound2ScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_dark={2}")
        fun sizes() = listOf(1280 to 800, 360 to 800).flatMap { (w, h) -> listOf(false, true).map { arrayOf<Any>(w, h, it) } }
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val case by lazy { FormationCase(grand = true) }
    private val fixture by lazy { Round2Fixture() }

    private fun shot(name: String, initialSection: Int, interact: () -> Unit = {}) =
        compose.shoot("dashboard-610", name, width, height, dark, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard-section:3").fetchSemanticsNodes().isEmpty() }
            interact()
            compose.waitForIdle()
        }) {
            case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.allSections(case), fixture.uiNames(case), "샘플 별명", {}, {}, battle = battleLogFixture("recent"),
                    outside = null, initialSection = initialSection, userId = "synthetic", friendCount = 24)
            } }
        }

    @Test fun overview() = shot("Overview", 0)
    @Test fun servantsAll() = shot("ServantsAll", 3)
    @Test fun servantsOneClass() = shot("ServantsSaber", 3) { compose.onNodeWithTag("dashboard-class:세이버").performClick() }
    @Test fun formations() = shot("Formations", 7)
    @Test fun items() = shot("Items", 4)
    @Test fun itemsSearchOpen() = shot("ItemsSearch", 4) {
        // On origin/main the field is always there and there is no icon, so this is the plain Items page.
        if (compose.onAllNodesWithTag("items-search-open").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("items-search-open").performClick()
    }
}
