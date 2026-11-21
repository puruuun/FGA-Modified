package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class DashboardItemsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private val materials = (1..14L).map { DashboardItem(9100 + it, 100 + it, InventoryGroup.Materials) }
    private val items = materials + listOf(
        DashboardItem(6001, 5, InventoryGroup.Gems), DashboardItem(6102, 40, InventoryGroup.Gems), DashboardItem(6208, 7, InventoryGroup.Gems),
        DashboardItem(6008, 25, InventoryGroup.Gems),
        DashboardItem(7001, 12, InventoryGroup.Pieces), DashboardItem(7107, 3, InventoryGroup.Pieces),
        DashboardItem(9999, 2, InventoryGroup.Event), DashboardItem(1000101, 30, InventoryGroup.Coins),
    )
    private val names by lazy {
        fixture.names.copy(items = fixture.names.items + materials.associate { it.id to "재료 ${it.id - 9100}" } + mapOf(
            6001L to "검의 휘석", 6102L to "궁의 마석", 6208L to "엑스트라 비석", 6008L to "범용 휘석", 7001L to "검의 피스", 7107L to "광의 모뉴먼트",
            9999L to "이벤트 토큰", 1000101L to "코인 알파",
        ))
    }

    private fun show(widthDp: Int) {
        RuntimeEnvironment.setQualifiers("ko-w${widthDp}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons {
            AccountDashboardContent(fixture.data.copy(inventory = items), names, "샘플", {}, {}, initialSection = DashboardBlockId.Items)
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("items-group:Materials").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun group(g: InventoryGroup) = compose.onNodeWithTag("items-group:${g.name}")
    private fun header(g: InventoryGroup) = compose.onNodeWithTag("items-group-header:${g.name}")
    private fun openSearch() { compose.onNodeWithTag("items-search-open").performClick(); compose.waitForIdle() }
    private fun search(text: String) { openSearch(); compose.onNodeWithTag("items-search").performTextInput(text); compose.waitForIdle() }
    private fun rowsWithPrefix(prefix: String) = compose.onAllNodes(tagStartingWith(prefix)).fetchSemanticsNodes().size

    @Test fun searchOpensGroupsWithMatchesHidesTheOthersAndClearingRestoresTheOpenState() {
        show(1280)
        header(InventoryGroup.Event).assertExists()
        compose.onNodeWithTag("item-row:9999").assertDoesNotExist()
        compose.onNodeWithTag("item-row:9114").assertExists()

        search("이벤트")
        group(InventoryGroup.Event).assertExists()
        compose.onNodeWithTag("item-row:9999").assertExists()
        group(InventoryGroup.Materials).assertDoesNotExist()
        group(InventoryGroup.Gems).assertDoesNotExist()

        compose.onNodeWithTag("items-search-clear").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("item-row:9999").assertDoesNotExist()
        compose.onNodeWithTag("item-row:9114").assertExists()
        compose.onNodeWithTag("items-search-clear").assertDoesNotExist()
    }

    @Test fun theSearchFieldIsHiddenUntilTheIconIsTappedAndTheCloseIconHidesItAgain() {
        show(1280)
        compose.onNodeWithTag("items-search").assertDoesNotExist()
        compose.onNodeWithTag("items-search-open").assertExists().assertHasClickAction()
        compose.onNodeWithTag("items-search-open").assertContentDescriptionEquals("아이템 검색")
        openSearch()
        compose.onNodeWithTag("items-search").assertExists().assertIsFocused()
        compose.onNodeWithTag("items-search-open").assertDoesNotExist()
        compose.onNodeWithTag("items-search-clear").assertContentDescriptionEquals("검색어 지우기").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("items-search").assertDoesNotExist()
        compose.onNodeWithTag("items-search-open").assertExists()
    }

    @Test fun closingTheSearchWithATextDropsTheQueryAndRestoresEveryGroupsOpenState() {
        show(1280)
        search("이벤트")
        compose.onNodeWithTag("item-row:9999").assertExists()
        compose.onNodeWithTag("items-search-clear").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("item-row:9999").assertDoesNotExist()
        group(InventoryGroup.Materials).assertExists()
        group(InventoryGroup.Gems).assertExists()
        // Reopening starts with an empty field: the old query was dropped, not hidden.
        openSearch()
        assertEquals("", compose.onNodeWithTag("items-search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("items-no-match").assertDoesNotExist()
    }

    @Test fun materialsListInTheReverseOfTheCapturedOrder() {
        show(1280)
        val y = (14 downTo 3L).map { compose.onNodeWithTag("item-row:${9100 + it}").performScrollTo().getUnclippedBoundsInRoot().top.value }
        assertEquals(y.sorted(), y)
        compose.onNodeWithTag("item-row:9102").assertDoesNotExist()
    }

    @Test fun groupsTheUserToggledKeepTheirStateAcrossASearch() {
        show(1280)
        header(InventoryGroup.Coins).performScrollTo().performClick()
        compose.onNodeWithTag("item-row:1000101").assertExists()
        header(InventoryGroup.Materials).performScrollTo().performClick()
        compose.onNodeWithTag("item-row:9114").assertDoesNotExist()

        search("재료")
        compose.onNodeWithTag("item-row:9114").assertExists()
        group(InventoryGroup.Coins).assertDoesNotExist()

        compose.onNodeWithTag("items-search-clear").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("item-row:9114").assertDoesNotExist()
        compose.onNodeWithTag("item-row:1000101").assertExists()
    }

    @Test fun aQueryWithoutAnyMatchSaysSo() {
        show(1280)
        search("zzzz")
        compose.onNodeWithTag("items-no-match").assertExists()
        group(InventoryGroup.Materials).assertDoesNotExist()
    }

    @Test fun materialsShowTwelveThenTheRestBehindShowMore() {
        show(1280)
        assertEquals(12, rowsWithPrefix("item-row:91"))
        compose.onNodeWithTag("items-more:Materials").assertTextContains("2", substring = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(14, rowsWithPrefix("item-row:91"))
        compose.onNodeWithTag("items-more:Materials").assertDoesNotExist()
    }

    @Test fun groupHeadersCarryTheItemCount() {
        show(1280)
        header(InventoryGroup.Materials).assertTextContains("14", substring = true)
        header(InventoryGroup.Event).assertTextContains("1", substring = true)
    }

    @Test fun narrowTableFitsWithoutHorizontalOverflowIncludingTheSuffixEightColumn() {
        show(360)
        val table = compose.onNodeWithTag("item-table:Gems").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("table right ${table.right}", table.right.value <= 360f)
        (1..8).forEach { suffix ->
            val header = compose.onNodeWithTag("item-table-header:Gems:$suffix").getUnclippedBoundsInRoot()
            assertTrue("header $suffix right ${header.right}", header.right.value <= 360f && header.left >= table.left)
        }
        listOf(6001L, 6102L, 6208L, 6008L).forEach { id ->
            val cell = compose.onNodeWithTag("item-cell:$id").getUnclippedBoundsInRoot()
            assertTrue("cell $id right ${cell.right}", cell.right.value <= 360f)
        }
    }

    @Test fun missingCellsAreDashesAndTheSuffixEightColumnIsThere() {
        show(1280)
        compose.onNodeWithTag("item-table-header:Gems:8").assertExists()
        compose.onNodeWithTag("item-cell:6002").assertTextContains("–", substring = true)
        compose.onNodeWithTag("item-cell:6001").assertTextContains("5", substring = true)
        compose.onNodeWithTag("item-table:Pieces").assertExists()
    }
}

private fun tagStartingWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
}
