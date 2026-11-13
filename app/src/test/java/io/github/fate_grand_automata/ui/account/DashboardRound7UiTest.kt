package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w840dp-h800dp-hdpi")
class DashboardRound7UiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun show(data: DashboardData = fixture.data, export: (String?) -> Unit = {}) {
        compose.setContent {
            FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) {
                fixture.Icons { AccountDashboardContent(data, fixture.names, "Alias", {}, {},
                    battle = io.github.fate_grand_automata.battlelog.battleLogFixture("recent"),
                    userId = "dashboard-account", exportAccount = export) }
            } }
        }
    }
    private fun bounds(id: Int) = compose.onNodeWithTag("dashboard-section:$id").getUnclippedBoundsInRoot()
    @Test fun jpProfileChipUsesTheAccountServer() {
        compose.setContent { FGATheme {
            fixture.Icons { AccountDashboardContent(fixture.data, fixture.names, "JP sample", {}, {}, server = "JP") }
        } }
        compose.onNodeWithText("JP").assertIsDisplayed()
        compose.onNodeWithText("KR").assertDoesNotExist()
    }

    @Test fun loadingStateShowsIndicatorThenDataAndEmptyStateOnlyWithoutData() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val noDataText = context.getString(io.github.fate_grand_automata.R.string.dashboard_no_data)
        val loading = androidx.compose.runtime.mutableStateOf(true)
        val currentData = androidx.compose.runtime.mutableStateOf<DashboardData?>(null)
        compose.setContent {
            FGATheme {
                CompositionLocalProvider(LocalNavigateBack provides {}) {
                    fixture.Icons {
                        AccountDashboardContent(currentData.value, fixture.names, "Alias", {}, {}, loading = loading.value)
                    }
                }
            }
        }
        // While loading is true, the progress indicator is shown and "no stored data" is absent.
        compose.onNodeWithTag("dashboard-loading").assertIsDisplayed()
        compose.onNodeWithText(noDataText).assertDoesNotExist()

        // Once data arrives and loading finishes, the dashboard sections render without flashing "no stored data".
        compose.runOnIdle {
            currentData.value = fixture.data
            loading.value = false
        }
        compose.onNodeWithTag("dashboard-loading").assertDoesNotExist()
        compose.onNodeWithText(noDataText).assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:0").assertExists()

        // An account that genuinely has no stored data shows "no stored data" after loading finishes.
        compose.runOnIdle {
            currentData.value = null
            loading.value = false
        }
        compose.onNodeWithTag("dashboard-loading").assertDoesNotExist()
        compose.onNodeWithText(noDataText).assertIsDisplayed()
    }

    @Test fun viewModelStartsInLoadingStateAndResolvesAccountOrEmptyState() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val noDataText = context.getString(io.github.fate_grand_automata.R.string.dashboard_no_data)
        CaptureTracker.init(context)
        CaptureTracker.delete()
        try {
            CaptureTracker.onGameResponse(
                "https://yfgo-game.netmarble.com/login/top",
                """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userGame":[{"userId":7001,"name":"Stored Master","lv":120}]}}}""".toByteArray(),
                null,
            )
            val repo = io.github.fate_grand_automata.battlelog.BattleLogRepository(context)
            val namesCache = GameNames(java.io.File(context.filesDir, "capture/names-test"), JpNamesFixture::download) { 1000 }
            val vm = AccountDashboardViewModel(repo, context, namesCache)
            assertTrue(vm.account("7001").value.loading)

            val shownId = androidx.compose.runtime.mutableStateOf("7001")
            compose.setContent {
                FGATheme {
                    CompositionLocalProvider(LocalNavigateBack provides {}) {
                        fixture.Icons {
                            AccountDashboardScreen(shownId.value, {}, {}, vm = vm)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Stored Master", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(noDataText).assertDoesNotExist()

            // Switch to an account id with no stored data: shows "no stored data" once its load finishes.
            compose.runOnIdle { shownId.value = "missing-account" }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(noDataText).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            CaptureTracker.delete()
        }
    }
    private fun layout(width: Int) {
        RuntimeEnvironment.setQualifiers("en-w${width}dp-h800dp-hdpi")
        // A short profile beside a tall collection exposes row-pairing regressions.
        show(fixture.data.copy(profile = mapOf("name" to "Short"), equips = listOf(DashboardEquip(1,10,true))))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 서번트 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
        if (width >= 840) {
            val columns = (0..1).map { compose.onNodeWithTag("dashboard-stack:$it").getUnclippedBoundsInRoot() }
            val nextY = FloatArray(2) { columns[it].top.value }
            listOf(0,1,2,3,4,7,5,6).forEach { id ->
                compose.onAllNodesWithTag("dashboard-section:$id").assertCountEquals(1)
                val card = bounds(id)
                val column = if (nextY[0] <= nextY[1]) 0 else 1
                assertEquals(columns[column].left, card.left)
                assertEquals(nextY[column], card.top.value, 0.1f)
                nextY[column] = card.bottom.value + 12f
            }
            // Cards in either column share the same page scroll.
            val beforeLeft = bounds(0).top
            val beforeRight = bounds(1).top
            compose.onNodeWithTag("dashboard-page").performTouchInput { swipeUp() }
            assertTrue(bounds(0).top < beforeLeft)
            assertEquals((beforeLeft-bounds(0).top).value,(beforeRight-bounds(1).top).value,0.1f)
        } else {
            compose.onNodeWithTag("dashboard-stack:0").assertDoesNotExist()
            val cards = listOf(0,1,2,3,4,7,5,6).map(::bounds)
            cards.zipWithNext().forEach { (a,b) -> assertTrue(a.bottom<=b.top); assertEquals(a.left,b.left) }
        }
    }
    @Test fun masonryAt840() = layout(840)
    @Test fun masonryAt1280() = layout(1280)
    @Test fun singleColumnAt360KeepsCardOrder() = layout(360)

    @Test fun missingSectionsDropOutOfTheirStack() {
        show(fixture.data.copy(profile = emptyMap(),storage = null, equips = listOf(DashboardEquip(1,10,true))))
        compose.onNodeWithTag("dashboard-section:0").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:2").assertDoesNotExist()
        assertEquals(bounds(1).top,bounds(3).top)
        assertTrue(bounds(4).top >= minOf(bounds(1).bottom, bounds(3).bottom))
    }

    @Test fun exportUsesThisDashboardAccountAndSitsLeftOfSettings() {
        var exported: String? = null
        show(export = { exported = it })
        val export = compose.onNodeWithTag("dashboard-export")
        assertTrue(export.getUnclippedBoundsInRoot().right <= compose.onNodeWithTag("dashboard-settings").getUnclippedBoundsInRoot().left)
        export.performClick()
        assertEquals("dashboard-account",exported)
    }
    @Test @Config(sdk = [28]) fun exportIsHiddenBeforeAndroid10() {
        show()
        compose.onNodeWithTag("dashboard-export").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-settings").assertExists()
    }

    @Test fun sortChipsSearchSummaryAndDisplayedClassDeduplication() {
        val rows = listOf("beast","beastEresh","beastFuture","unknownClass","unknownClass","saber").mapIndexed { index, cls ->
            val id = index.toLong()+1
            DashboardServant(id, AtlasBasic(id,"Servant $id","normal",cls,5), id, id, null, emptyList(),id)
        }
        show(fixture.data.copy(servants = rows, inventory = null))
        val collection = hasAnyAncestor(hasTestTag("dashboard-section:3"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val chips = listOf("Bond","Cost","Lv").map { compose.onNode(hasText(it) and collection) }
        chips[0].assertIsSelected()
        chips.map { it.getUnclippedBoundsInRoot() }.zipWithNext().forEach { (a,b) -> assertTrue(a.left < b.left) }
        chips[1].performClick().assertIsSelected()
        chips[2].performClick().assertIsSelected()
        compose.onAllNodes(hasSetTextAction() and collection).assertCountEquals(0)
        compose.onNodeWithText("Owned",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Bond ≥10",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Collection no.").assertDoesNotExist()
        compose.onNode(hasText("All classes") and collection).performClick()
        compose.onAllNodesWithText("Beast").assertCountEquals(1)
        compose.onAllNodesWithText("unknownClass").assertCountEquals(1)
        compose.onNodeWithText("Beast").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6", substring = true).fetchSemanticsNodes().isEmpty() }
        (1..3).forEach { compose.onNodeWithText("Servant $it", substring = true).assertExists() }
        (4..6).forEach { compose.onNodeWithText("Servant $it", substring = true).assertDoesNotExist() }
        compose.onAllNodesWithText("Beast · ★5", substring = true).assertCountEquals(3)
    }

    @Test
    @Config(sdk = [32], qualifiers = "ko-w840dp-h800dp-hdpi")
    fun unBeastClassShowsSeparatelyInRowAndFilterMenu() {
        val rows = listOf("unBeastOlgaMarie", "beastIII", "saber").mapIndexed { index, cls ->
            val id = index.toLong() + 1
            DashboardServant(id, AtlasBasic(id, "서번트 $id", "normal", cls, 5), id, id, null, emptyList(), id)
        }
        show(fixture.data.copy(servants = rows, inventory = null))
        val collection = hasAnyAncestor(hasTestTag("dashboard-section:3"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("서번트 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(
            hasText("서번트 1", substring = true) and hasText("언비스트 · ★5", substring = true),
        ).assertExists()
        compose.onNode(
            hasText("서번트 2", substring = true) and hasText("  비스트 · ★5", substring = true),
        ).assertExists()

        compose.onNode(hasText("모든 클래스") and collection).performClick()
        compose.onAllNodesWithText("언비스트").assertCountEquals(1)
        compose.onAllNodesWithText("비스트").assertCountEquals(1)

        compose.onNodeWithText("언비스트").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("서번트 2", substring = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("서번트 1", substring = true).assertExists()
        compose.onNodeWithText("서번트 3", substring = true).assertDoesNotExist()
    }

    @Test fun coinsGroupShowsCountStartsCollapsedExpandsOnTapAndSitsBetweenEventAndOther() {
        val names = fixture.names.copy(
            items = fixture.names.items + mapOf(10010001L to "Coin Alpha", 10020001L to "Coin Beta"),
            itemData = fixture.names.itemData + mapOf(9000L to AtlasBasic(9000L, "Event Token", "eventItem")),
        )
        val state = dashboardState(
            "userItem" to """[{"itemId":6510,"num":60},{"itemId":9000,"num":12},{"itemId":999,"num":1}]""",
            "userSvtCoin" to """[{"svtId":100100,"num":30},{"svtId":100200,"num":10},{"svtId":100300,"num":0}]""",
        )
        val data = dashboardData(state, names)
        compose.setContent {
            FGATheme {
                CompositionLocalProvider(LocalNavigateBack provides {}) {
                    fixture.Icons {
                        AccountDashboardContent(data, names, "Alias", {}, {}, initialSection = 4)
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("+ Servant coins (2)").fetchSemanticsNodes().isNotEmpty()
        }
        val eventBounds = compose.onNodeWithText("+ Event items (1)").fetchSemanticsNode().boundsInRoot
        val coinsHeader = compose.onNodeWithText("+ Servant coins (2)")
        val coinsBounds = coinsHeader.fetchSemanticsNode().boundsInRoot
        val otherBounds = compose.onNodeWithText("+ Other (1)").fetchSemanticsNode().boundsInRoot
        assertTrue(eventBounds.bottom <= coinsBounds.top)
        assertTrue(coinsBounds.bottom <= otherBounds.top)

        compose.onNodeWithText("Coin Alpha").assertDoesNotExist()
        compose.onNodeWithText("Coin Beta").assertDoesNotExist()

        coinsHeader.performScrollTo().performClick()
        compose.onNodeWithText("− Servant coins (2)").assertExists()
        compose.onNodeWithText("Coin Alpha").assertExists()
        compose.onNodeWithText("Coin Beta").assertExists()
        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithText("10").assertExists()

        compose.onNodeWithText("− Servant coins (2)").performClick()
        compose.onNodeWithText("+ Servant coins (2)").assertExists()
        compose.onNodeWithText("Coin Alpha").assertDoesNotExist()
    }
}
