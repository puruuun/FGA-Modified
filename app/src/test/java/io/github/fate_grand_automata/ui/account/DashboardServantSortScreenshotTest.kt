package io.github.fate_grand_automata.ui.account

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Servants section with the sortable header (wide) and the sort chip row (narrow), for the owner's review of #754. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardServantSortScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun shot(name: String, width: Int, height: Int, tap: List<String> = emptyList()) =
        compose.shoot("dashboard-sort-754", name, width, height, locale = "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready", useUnmergedTree = true).fetchSemanticsNodes().size >= 5 }
            tap.forEach { compose.onNodeWithTag(it).performClick(); compose.waitForIdle() }
        }) {
            fixture.Icons { AccountDashboardContent(fixture.data, fixture.names, "샘플 별명", {}, {}, initialSection = 3) }
        }

    @Test fun wideDefault() = shot("WideDefault", 1280, 800)
    @Test fun wideSkillsDescending() = shot("WideSkills", 1280, 800, listOf("dashboard-sort:Skills"))
    @Test fun wideNameAscendingNearTheMinimum() = shot("WideName960", 960, 800, listOf("dashboard-sort:Name"))
    @Test fun narrowDefault() = shot("NarrowDefault", 360, 800)
    @Test fun narrowLevelAscending() = shot("NarrowLevelAscending", 360, 800, listOf("dashboard-sort:Level", "dashboard-sort:Level"))
}
