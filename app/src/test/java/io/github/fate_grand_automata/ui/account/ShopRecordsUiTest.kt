package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun shopRecordsFixture(names: Boolean = true): ShopRecords =
    shopRecords(readUserShop(SHOP_TABLE), if (names) shopFixtureNames() else ShopNames())

internal fun shopSource(state: ShopRecordsState, retry: () -> Unit = {}) = ShopRecordsSource({ flowOf(state) }, retry)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2400dp-hdpi")
class ShopRecordsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun show(source: ShopRecordsSource?, hasShops: Boolean = true) {
        compose.setContent {
            FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data.copy(hasShopRecords = hasShops), fixture.names, "Alias", {}, {},
                    initialSection = DashboardBlockId.ShopRecords, shopRecords = source)
            } }
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun theBlockSitsInTheRenamedTabWithThePrismEstimates() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)))
        compose.onNodeWithTag("dashboard-nav:PresentBox").assertTextContains("선물함·상점")
        waitFor("shop-event:80001")
        compose.onNodeWithText("상점 기록").assertExists()
        compose.onNodeWithTag("shop-records-summary").assertTextEquals("마나 프리즘 ≈ 340 · 레어 프리즘 ≈ 0 · 퓨어 프리즘 ≈ 5")
        compose.onNodeWithTag("shop-records-excluded").assertTextEquals("구매 수가 초기화된 적이 있는 1종은 추정에서 뺐습니다.")
        compose.onNodeWithTag("shop-event-progress:80001", useUnmergedTree = true).assertTextEquals("1종 완료 / 2종")
        compose.onNodeWithText("서유기 이벤트").assertExists()
    }

    @Test fun anEventExpandsToItsItemsWithDoneAndLimits() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("shop-event:80001")
        compose.onNodeWithTag("shop:80001001").assertDoesNotExist()
        compose.onNodeWithTag("shop-event-toggle:80001").performScrollTo().performClick()
        waitFor("shop:80001001")
        compose.onNodeWithTag("shop-count:80001001").assertTextEquals("1 / 1")
        compose.onNodeWithTag("shop-complete:80001001", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("shop-count:80001002").assertTextEquals("2 / 3")
        compose.onNodeWithTag("shop-complete:80001002", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shop-count:80001003").assertTextEquals("10")
        compose.onNodeWithText("복숭아 50").assertExists()
    }

    @Test fun prismChipsListItemsDirectly() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("shop-event:80001")
        compose.onNodeWithTag("shop-filter:Mana").performScrollTo().performClick()
        waitFor("shop:11000101")
        compose.onNodeWithTag("shop-event:80001").assertDoesNotExist()
        compose.onNodeWithTag("shop-count:11000101").assertTextEquals("1 / 1")
        compose.onNodeWithText("마나 프리즘 300").assertExists()
    }

    @Test fun anUnknownItemShowsItsNumberUnderOther() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("shop-event:80001")
        compose.onNodeWithTag("shop-filter:Other").performScrollTo().performClick()
        waitFor("shop:99999999")
        compose.onNodeWithText("#99999999").assertExists()
        compose.onNodeWithTag("shop-count:99999999").assertTextEquals("2")
    }

    @Test fun buyableOnlyHidesAClosedEventShop() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("shop-event:80001")
        compose.onNodeWithTag("shop-buyable").performScrollTo().performClick()
        waitGone("shop-event:80001")
        compose.onNodeWithTag("shop-event:80003").assertExists()
    }

    @Test fun missingNamesShowNumbersAndARetry() {
        var retries = 0
        show(shopSource(ShopRecordsState(shopRecordsFixture(names = false), RecordNameStatus.Missing)) { retries++ })
        waitFor("shop-names-missing")
        compose.onNodeWithTag("shop-records-empty").assertExists()
        compose.onNodeWithTag("shop-names-retry").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("shop-filter:Other").performScrollTo().performClick()
        waitFor("shop:80001001")
        compose.onNodeWithText("#80001001").assertExists()
    }

    @Test fun loadingThenRecords() {
        val flow = MutableStateFlow(ShopRecordsState())
        show(ShopRecordsSource({ flow }, {}))
        waitFor("shop-records-loading")
        flow.value = ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)
        waitFor("shop-event:80001")
        compose.onNodeWithTag("shop-records-loading").assertDoesNotExist()
    }

    @Test fun noBlockWithoutTheTable() {
        show(shopSource(ShopRecordsState(shopRecordsFixture(), RecordNameStatus.Ready)), hasShops = false)
        compose.onNodeWithTag("dashboard-section:${DashboardBlockId.ShopRecords}").assertDoesNotExist()
    }
}
