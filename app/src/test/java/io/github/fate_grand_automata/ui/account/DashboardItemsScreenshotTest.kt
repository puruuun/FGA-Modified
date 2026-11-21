package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/**
 * Items section shots (#594). Uses only the public content composable and test tags, so the same source also runs on
 * the layout before the Items rewrite, and the search step is skipped where the field does not exist.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardItemsScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}_dark={2}")
        fun sizes() = listOf(1280 to 800, 360 to 800, 1280 to 1900, 360 to 2600).flatMap { (w, h) -> listOf(false, true).map { arrayOf<Any>(w, h, it) } }
    }

    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private val classes = listOf("검", "궁", "창", "기", "술", "살", "광")
    private fun gems(base: Long, kind: String, counts: List<Long?>, extra: Long? = null): List<Pair<Long, Pair<String, Long>>> =
        counts.mapIndexedNotNull { i, c -> c?.let { base + i + 1 to ("${classes[i]}의 $kind" to it) } } +
            listOfNotNull(extra?.let { base + 8 to ("범용 $kind" to it) })

    private val specials = listOf(
        gems(6000, "휘석", listOf(5, 40, 120, null, 9, 10, 250), extra = 25),
        gems(6100, "마석", listOf(null, 33, 8, 410, null, 61, 12)),
        gems(6200, "비석", listOf(77, null, null, 2, 150, null, 19)),
        gems(7000, "피스", listOf(4, 30, 99, 12, null, 300, 9)),
        gems(7100, "모뉴먼트", listOf(null, null, 15, null, 8, 21, null)),
    ).flatten()
    private val materials = (1..18L).map { DashboardItem(9100 + it, 1000L - it * 37, InventoryGroup.Materials) }
    private val items: List<DashboardItem> = materials +
        specials.map { (id, v) -> DashboardItem(id, v.second, if (id < 7000) InventoryGroup.Gems else InventoryGroup.Pieces) } +
        listOf(
            DashboardItem(6999, 3, InventoryGroup.Grails), DashboardItem(7999, 14, InventoryGroup.Grails),
            DashboardItem(9901, 2, InventoryGroup.Event), DashboardItem(9902, 88, InventoryGroup.Event),
            DashboardItem(1000101, 30, InventoryGroup.Coins), DashboardItem(1000201, 10, InventoryGroup.Coins),
            DashboardItem(9990, 1, InventoryGroup.Other),
        )
    private val names by lazy {
        fixture.names.copy(items = fixture.names.items + materials.associate { it.id to "샘플 재료 ${it.id - 9100}" } +
            specials.associate { it.first to it.second.first } + mapOf(
            6999L to "성배", 7999L to "전승 결정", 9901L to "이벤트 토큰", 9902L to "이벤트 포인트", 1000101L to "코인 알파", 1000201L to "코인 베타", 9990L to "기타 물품",
        ))
    }

    private fun shot(name: String, query: String? = null) =
        compose.shoot("dashboard-594-items", name, width, height, dark, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-section:4").fetchSemanticsNodes().isNotEmpty() }
            if (query != null) runCatching {
                compose.onNodeWithTag("items-search").performTextInput(query)
                compose.waitForIdle()
            }
        }) {
            fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data.copy(inventory = items), names, "샘플 별명", {}, {}, initialSection = DashboardBlockId.Items,
                    userId = "synthetic", friendCount = 24)
            } }
        }

    @Test fun items() = shot("Items")
    @Test fun itemsSearch() = shot("ItemsSearch", query = "검의")
}
