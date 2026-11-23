package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An account that has every section: servants, items, formations with Grand seats, collection and the present box. */
internal fun allSectionsData(case: FormationCase): DashboardData = case.fixture.data.copy(
    formations = case.data.formations,
    equips = listOf(DashboardEquip(1, 10, true)),
    presentBox = DashboardPresentBox(totalCount = 3, entries = listOf(DashboardPresent(2, 6510L, 4L))),
    commandCodes = DashboardCommandCodes(ownedIds = listOf(501L), total = 3),
    classBoards = listOf(DashboardClassBoard(1L, "세이버", null, acquired = 3, unlockedLocks = 1, squareTotal = 10, lockTotal = 2)),
    summons = DashboardSummons(
        friendPointTotal = 100L,
        otherSummons = listOf(DashboardSummon(10L, "픽업", "stone", null, null, 11L)),
        recentLog = listOf(DashboardSummonLog("2026-10-01", 10L, "픽업", 1)),
    ),
)

/**
 * Before and after shots of the dashboard (#594) use the same source: it only touches the public content composable and
 * test tags, so it also runs on the pre-navigation layout, where the tab-row shot is simply the same page.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardNavScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_dark={2}")
        fun sizes() = listOf(1280 to 800, 360 to 800).flatMap { (w, h) -> listOf(false, true).map { arrayOf<Any>(w, h, it) } }
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val case by lazy { FormationCase(grand = true) }

    private fun shot(name: String, initialSection: Int, beforeCapture: () -> Unit = {}) =
        compose.shoot("dashboard-594", name, width, height, dark, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard-section:3").fetchSemanticsNodes().isEmpty() }
            beforeCapture()
        }) {
            case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(allSectionsData(case), case.names, "샘플 별명", {}, {}, battle = battleLogFixture("recent"),
                    outside = null, initialSection = initialSection, userId = "synthetic", friendCount = 24)
            } }
        }

    @Test fun overview() = shot("Overview", 0)
    @Test fun formations() = shot("Formations", 7)
    @Test fun tabRowScrolledToTheLastTab() = shot("TabsScrolled", 10) {
        runCatching { compose.onNodeWithTag("dashboard-nav:PresentBox").performScrollTo() }
    }
}
