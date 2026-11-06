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
    private fun layout(width: Int) {
        RuntimeEnvironment.setQualifiers("en-w${width}dp-h800dp-hdpi")
        // A short profile beside a tall collection exposes row-pairing regressions.
        show(fixture.data.copy(profile = mapOf("name" to "Short"), equips = listOf(DashboardEquip(1,10,true))))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 서번트 1").fetchSemanticsNodes().isNotEmpty() }
        if (width >= 840) {
            val left = listOf(0,1,2,5,6).map(::bounds)
            val right = listOf(3,4).map(::bounds)
            assertEquals(left.first().top, right.first().top)
            left.zipWithNext().forEach { (a,b) -> assertTrue(a.bottom <= b.top); assertEquals(a.left,b.left) }
            assertTrue(right[0].bottom <= right[1].top)
            assertEquals(right[0].left,right[1].left)
            assertTrue(left[0].right < right[0].left)
            assertTrue("Resources follow the short profile, without waiting for the collection", left[1].top < right[0].bottom)
            assertEquals(12f, (left[1].top-left[0].bottom).value, 0.1f)
            // Both stacks move by the same page scroll distance.
            val beforeLeft = bounds(0).top
            val beforeRight = bounds(3).top
            compose.onNodeWithTag("dashboard-page").performTouchInput { swipeUp() }
            assertTrue(bounds(0).top < beforeLeft)
            assertEquals((beforeLeft-bounds(0).top).value,(beforeRight-bounds(3).top).value,0.1f)
        } else {
            compose.onNodeWithTag("dashboard-stack:0").assertDoesNotExist()
            val cards = (0..6).map(::bounds)
            cards.zipWithNext().forEach { (a,b) -> assertTrue(a.bottom<=b.top); assertEquals(a.left,b.left) }
        }
    }
    @Test fun independentStacksAt840() = layout(840)
    @Test fun independentStacksAt1280() = layout(1280)
    @Test fun singleColumnAt360KeepsExistingOrder() = layout(360)

    @Test fun missingSectionsDropOutOfTheirStack() {
        show(fixture.data.copy(profile = emptyMap(),storage = null, equips = listOf(DashboardEquip(1,10,true))))
        compose.onNodeWithTag("dashboard-section:0").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:2").assertDoesNotExist()
        assertEquals(bounds(1).top,bounds(3).top)
        assertEquals(12f,(bounds(5).top-bounds(1).bottom).value,0.1f)
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Servant 6").fetchSemanticsNodes().isEmpty() }
        (1..3).forEach { compose.onNodeWithText("Servant $it").assertExists() }
        (4..6).forEach { compose.onNodeWithText("Servant $it").assertDoesNotExist() }
        compose.onAllNodesWithText("Beast · ★5 · Cost 16").assertCountEquals(3)
    }
}
