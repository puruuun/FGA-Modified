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

    private fun show(data: DashboardData = fixture.data, export: (String?) -> Unit = {}, initialSection: Int = 0) {
        compose.setContent {
            FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) {
                fixture.Icons { AccountDashboardContent(data, fixture.names, "Alias", {}, {},
                    battle = io.github.fate_grand_automata.battlelog.battleLogFixture("recent"),
                    userId = "dashboard-account", exportAccount = export, initialSection = initialSection) }
            } }
        }
    }
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
        compose.onNodeWithTag("dashboard-overview").assertExists()

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

    @Test fun sameSecondProfileChangeWithNewRevisionRebuildsDashboardData() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        CaptureTracker.clockSeconds = { 1_000L }
        CaptureTracker.init(context)
        CaptureTracker.delete()
        try {
            CaptureTracker.onGameResponse(
                "https://yfgo-game.netmarble.com/login/top",
                """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userGame":[{"userId":7001,"name":"Stored Master","lv":120,"qp":1000}]}}}""".toByteArray(),
                null,
            )
            val repo = io.github.fate_grand_automata.battlelog.BattleLogRepository(context)
            val namesCache = GameNames(java.io.File(context.filesDir, "capture/names-test-revision"), JpNamesFixture::download) { 1000 }
            val vm = AccountDashboardViewModel(repo, context, namesCache)

            compose.setContent {
                FGATheme {
                    CompositionLocalProvider(LocalNavigateBack provides {}) {
                        fixture.Icons {
                            AccountDashboardScreen("7001", {}, {}, vm = vm)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("120", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    !vm.account("7001").value.loading &&
                    vm.account("7001").value.data != null
            }
            val firstData = vm.account("7001").value.data!!
            val firstProfile = CaptureTracker.accounts.value.single { it.id == "7001" }
            assertEquals("120", firstData.profile["lv"])
            assertEquals(1000L, firstData.resources[1L])
            assertEquals(1_000L, firstProfile.lastUpdateTime)

            CaptureTracker.onGameResponse(
                "https://yfgo-game.netmarble.com/item/use",
                """{"cache":{"updated":{"userGame":[{"userId":7001,"lv":121,"qp":2500}]}}}""".toByteArray(),
                null,
            )
            val secondProfile = CaptureTracker.accounts.value.single { it.id == "7001" }
            assertEquals(firstProfile.lastUpdateTime, secondProfile.lastUpdateTime)
            assertTrue(secondProfile.revision > firstProfile.revision)

            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("121", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                    vm.account("7001").value.data !== firstData
            }
            val secondData = vm.account("7001").value.data!!
            assertEquals("121", secondData.profile["lv"])
            assertEquals(2500L, secondData.resources[1L])
            assertEquals(1_000L, secondData.lastUpdateTime)
        } finally {
            CaptureTracker.clockSeconds = { System.currentTimeMillis() / 1000 }
            CaptureTracker.delete()
        }
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
        show(fixture.data.copy(servants = rows, inventory = null), initialSection = 3)
        val collection = hasAnyAncestor(hasTestTag("dashboard-section:3"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val chips = ServantSort.entries.map { compose.onNodeWithTag("dashboard-sort:${it.name}") }
        chips[0].assertIsSelected()
        chips.map { it.getUnclippedBoundsInRoot() }.zipWithNext().forEach { (a,b) -> assertTrue(a.left < b.left) }
        chips[1].performClick().assertIsSelected()
        chips[2].performClick().assertIsSelected()
        compose.onAllNodes(hasSetTextAction() and collection).assertCountEquals(0)
        compose.onNodeWithText("Owned",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Bond ≥10",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Collection no.").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-class:all").assertIsSelected()
        compose.onAllNodesWithTag("dashboard-class:Beast").assertCountEquals(1)
        compose.onAllNodesWithTag("dashboard-class:unknownClass").assertCountEquals(1)
        compose.onNodeWithTag("dashboard-class:Beast").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6", substring = true).fetchSemanticsNodes().isEmpty() }
        (1..3).forEach { compose.onNodeWithText("Servant $it", substring = true).assertExists() }
        (4..6).forEach { compose.onNodeWithText("Servant $it", substring = true).assertDoesNotExist() }
        compose.onAllNodesWithText("Beast · ★5").assertCountEquals(3)
    }

    @Test
    @Config(sdk = [32], qualifiers = "ko-w840dp-h800dp-hdpi")
    fun unBeastClassShowsSeparatelyInRowAndFilterChips() {
        val rows = listOf("unBeastOlgaMarie", "beastIII", "saber").mapIndexed { index, cls ->
            val id = index.toLong() + 1
            DashboardServant(id, AtlasBasic(id, "서번트 $id", "normal", cls, 5), id, id, null, emptyList(), id)
        }
        show(fixture.data.copy(servants = rows, inventory = null), initialSection = 3)
        val collection = hasAnyAncestor(hasTestTag("dashboard-section:3"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("서번트 1", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("언비스트 · ★5").assertCountEquals(1)
        compose.onAllNodesWithText("비스트 · ★5").assertCountEquals(1)

        compose.onAllNodesWithTag("dashboard-class:언비스트").assertCountEquals(1)
        compose.onAllNodesWithTag("dashboard-class:비스트").assertCountEquals(1)

        compose.onNodeWithTag("dashboard-class:언비스트").performClick()
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
            compose.onAllNodesWithTag("items-group-header:Coins").fetchSemanticsNodes().isNotEmpty()
        }
        val eventBounds = compose.onNodeWithTag("items-group-header:Event").fetchSemanticsNode().boundsInRoot
        val coinsHeader = compose.onNodeWithTag("items-group-header:Coins")
        val coinsBounds = coinsHeader.fetchSemanticsNode().boundsInRoot
        val otherBounds = compose.onNodeWithTag("items-group-header:Other").fetchSemanticsNode().boundsInRoot
        assertTrue(eventBounds.bottom <= coinsBounds.top)
        assertTrue(coinsBounds.bottom <= otherBounds.top)
        coinsHeader.assertTextContains("2", substring = true)

        compose.onNodeWithText("Coin Alpha").assertDoesNotExist()
        compose.onNodeWithText("Coin Beta").assertDoesNotExist()

        coinsHeader.performScrollTo().performClick()
        compose.onNodeWithText("Coin Alpha").assertExists()
        compose.onNodeWithText("Coin Beta").assertExists()
        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithText("10").assertExists()

        coinsHeader.performClick()
        compose.onNodeWithText("Coin Alpha").assertDoesNotExist()
    }
}
