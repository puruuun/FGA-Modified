package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 2 of the account dashboard (#610): header AP, rail style, Overview blocks, Servants top line. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class DashboardRound2UiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val icons by lazy { DashboardUiFixture() }
    private val fixture by lazy { Round2Fixture() }

    private fun show(width: Int, data: DashboardData = fixture.data, names: GameNameMap = fixture.names, section: Int = 0) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { icons.Icons {
            AccountDashboardContent(data, names, "샘플", {}, {}, initialSection = section, userId = "r2", friendCount = 143)
        } } } }
        compose.waitForIdle()
    }

    private fun withGame(json: String) = fixture.data.copy(game = Json.parseToJsonElement(json).jsonObject)
    private fun texts(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    private fun under(tag: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    @Test fun theSelectedRailItemHasAPrimaryBarAndNoContainerFill() {
        show(1280)
        compose.onNodeWithTag("dashboard-nav-bar:Overview", useUnmergedTree = true).assertExists()
        DashboardSection.entries.drop(1).forEach { compose.onNodeWithTag("dashboard-nav-bar:${it.name}", useUnmergedTree = true).assertDoesNotExist() }
        val selected = compose.onNodeWithTag("dashboard-nav:Overview").captureToImage().asAndroidBitmap()
        val other = compose.onNodeWithTag("dashboard-nav:Items").captureToImage().asAndroidBitmap()
        // The far right of an item is the container: without a pill it is the pane colour in both items.
        assertEquals(other.getPixel(other.width - 6, other.height / 2), selected.getPixel(selected.width - 6, selected.height / 2))
        assertNotEquals(other.getPixel(1, other.height / 2), selected.getPixel(1, selected.height / 2))
        val bar = compose.onNodeWithTag("dashboard-nav-bar:Overview", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(3f, (bar.right - bar.left).value, 0.5f)
        dashboardSections(fixture.data).forEach {
            val item = compose.onNodeWithTag("dashboard-nav:${it.name}").getUnclippedBoundsInRoot()
            assertTrue("$it ${item.bottom - item.top}", (item.bottom - item.top).value >= 47.99f)
        }
    }

    @Test fun theBarFollowsTheSelection() {
        show(1280)
        compose.onNodeWithTag("dashboard-nav:Servants").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("dashboard-nav-bar:Servants", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dashboard-nav-bar:Overview", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theHeaderTextAndTheRailItemTextStartAtTheSameX() {
        show(1280)
        val header = compose.onNodeWithTag("dashboard-account-header").getUnclippedBoundsInRoot().left
        val item = compose.onNode(hasText("개요") and hasAnyAncestor(hasTestTag("dashboard-nav:Overview")), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().left
        assertEquals(header.value, item.value, 1f)
        assertEquals(24f, header.value, 1f)
    }

    private fun assertApInHeader(width: Int, section: Int) {
        show(width, withGame("""{"actMax":144,"actRecoverAt":1,"carryOverActPoint":12}"""), section = section)
        val inHeader = hasAnyAncestor(hasTestTag("dashboard-account-header"))
        if (width >= 840) {
            compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertContentDescriptionEquals("AP 144 / 144")
            compose.onNode(hasText("144") and inHeader and hasTestTag("header-ap-current"), useUnmergedTree = true).assertExists()
            compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).assertExists()
        } else {
            compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertContentDescriptionEquals("AP 144 / 144")
            compose.onNode(hasText("144") and inHeader and hasAnyAncestor(hasTestTag("header-ap-ring")), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("header-ap-detail").assertTextEquals("AP 144 / 144 · 가득 참")
        }
    }

    @Test fun apIsInTheHeaderOnWideOnOverview() = assertApInHeader(1280, 0)
    @Test fun apIsInTheHeaderOnNarrowOnOverview() = assertApInHeader(360, 0)
    @Test fun apStaysInTheHeaderWhenAnotherSectionIsOpenOnWide() = assertApInHeader(1280, 4)
    @Test fun apStaysInTheHeaderWhenAnotherSectionIsOpenOnNarrow() = assertApInHeader(360, 4)

    @Test fun theWideApDetailShowsTheLabelAndTheClockTime() {
        val recoverAt = System.currentTimeMillis() / 1000 + 3 * 3600
        show(1280, withGame("""{"actMax":144,"actRecoverAt":$recoverAt,"carryOverActPoint":7}"""))
        compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).assertExists()
        val clock = compose.onAllNodes(hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(clock.toString(), clock.any { Regex("""\d{2}:\d{2}""").matches(it) })
    }

    @Test fun overviewHasNoHeadlineRow() {
        show(1280)
        listOf("overview-ap", "overview-quartz", "overview-qp").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test fun theServantBlockOnOverviewShowsOwnedOverTotalNpSumsAndOneRowPerRarity() {
        show(1280)
        assertEquals(40, fixture.data.servants!!.size)
        under("overview-servants-owned", "40 / 70").assertExists()
        val star5 = (1L..12).filter(fixture::owns).sumOf { fixture.np(it) }
        under("overview-servants-star5-np", star5.toString()).assertExists()
        under("overview-servants-all-np", (1L..60).filter(fixture::owns).sumOf { fixture.np(it) }.toString()).assertExists()
        (1..5).forEach { compose.onNodeWithTag("overview-rarity:$it").assertExists(); compose.onNodeWithTag("overview-rarity-bar:$it").assertExists() }
        compose.onNodeWithTag("overview-rarity:6").assertDoesNotExist()
        // Star 5 are ids 1..12 of which 3, 6, 9 and 12 are not owned; star 1 are ids 55..70 of which 55, 56, 58 and 59 are owned.
        under("overview-rarity:5", "8 / 12").assertExists()
        under("overview-rarity:1", "4 / 16").assertExists()
    }

    @Test fun withoutTheServerListTheServantBlockHasNoTotalsAndNoBars() {
        val unknown = Round2Fixture(withCatalog = false)
        show(1280, unknown.data, unknown.names)
        under("overview-servants-owned", "40").assertExists()
        (1..5).forEach { compose.onNodeWithTag("overview-rarity-bar:$it").assertDoesNotExist() }
        compose.onNode(hasText("/", substring = true) and hasAnyAncestor(hasTestTag("overview-servants"))).assertDoesNotExist()
    }

    @Test fun everyResourceRowIsThereWithZeroForTheMissingOnesOnceTheItemListWasCaptured() {
        show(1280)
        listOf("4001", "49", TEAPOT_ROW).forEach { key -> under("overview-resource:$key", "0").assertExists() }
        under("overview-resource:$TEAPOT_ROW", "티포트").assertExists()
    }

    @Test fun withoutTheItemListNoZeroRowsAreInvented() {
        val noItems = fixture.data.copy(itemsCaptured = false, teapots = null, inventory = null,
            resources = fixture.data.resources.filterKeys { it in setOf(1L, 2L, 3L, 18L) })
        show(1280, noItems)
        listOf("4001", "49", "16", "103", TEAPOT_ROW).forEach { compose.onNodeWithTag("overview-resource:$it").assertDoesNotExist() }
        listOf("2", "3", "18", "1").forEach { compose.onNodeWithTag("overview-resource:$it").assertExists() }
    }

    @Test fun theServantsTopLineFollowsTheClassChip() {
        show(1280, section = 3)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-servant-owned").assertTextContains("보유 40 / 70 (57%)", substring = true)
        val allNp = (1L..60).filter(fixture::owns).sumOf { fixture.np(it) }
        compose.onNodeWithTag("dashboard-servant-all-np").assertTextContains("전체 보합 $allNp", substring = true)

        compose.onNodeWithTag("dashboard-class:세이버").performClick(); compose.waitForIdle()
        // Saber is every id divisible by 7: ten on the server, of which 7, 14, 28, 35, 49 and 56 are owned.
        compose.onNodeWithTag("dashboard-servant-owned").assertTextContains("보유 6 / 10 (60%)", substring = true)
        compose.onNodeWithTag("dashboard-servant-all-np").assertTextContains("전체 보합 " + listOf(7L, 14, 28, 35, 49, 56).sumOf { fixture.np(it) }, substring = true)
        compose.onNodeWithTag("dashboard-servant-star5-np").assertTextContains("★5 보합 " + fixture.np(7), substring = true)

        compose.onNodeWithTag("dashboard-class:all").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("dashboard-servant-owned").assertTextContains("보유 40 / 70 (57%)", substring = true)
    }

    @Test fun theServantsTopLineWithoutTheServerListShowsOnlyOwnedAndNp() {
        val unknown = Round2Fixture(withCatalog = false)
        show(1280, unknown.data, unknown.names, section = 3)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
        val line = texts("dashboard-servant-owned")
        assertTrue(line, line.startsWith("보유 40") && "/" !in line && "%" !in line)
    }

    @Test fun theServantsTopLinePartsStayOnOneLineEachOnNarrow() {
        show(360, section = 3)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
        listOf("dashboard-servant-owned", "dashboard-servant-star5-np", "dashboard-servant-all-np").forEach { tag ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(tag, 1, layouts.single().lineCount)
        }
    }
}
