package io.github.fate_grand_automata.ui.account

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
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
@Config(sdk = [32])
class DashboardServantsTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }
    private val scheme = lightColorScheme(primary = Color.Blue, tertiary = Color(0xFF7A5900), onSurface = Color.Black, onSurfaceVariant = Color.Gray)

    @Test fun cellsMarkTenAsMaxedAndMissingOrZeroAppendsAsLocked() {
        assertEquals(ServantCell("10", CellKind.Maxed), skillCell(10))
        assertEquals(ServantCell("9", CellKind.Normal), skillCell(9))
        assertEquals(ServantCell("–", CellKind.Locked), skillCell(null))
        assertEquals(ServantCell("–", CellKind.Locked), appendCell(null))
        assertEquals(ServantCell("–", CellKind.Locked), appendCell(0))
        assertEquals(ServantCell("1", CellKind.Normal), appendCell(1))
        assertEquals(ServantCell("10", CellKind.Maxed), appendCell(10))
    }

    @Test fun maxedValuesAreGoldNotPrimary() {
        assertEquals(scheme.tertiary, CellKind.Maxed.color(scheme))
        assertNotEquals(scheme.primary, CellKind.Maxed.color(scheme))
        assertNotEquals(scheme.tertiary, CellKind.Normal.color(scheme))
        assertNotEquals(scheme.tertiary, CellKind.Locked.color(scheme))
        assertNotEquals(scheme.onSurface, CellKind.Locked.color(scheme))
    }

    @Test fun annotatedCellsColourOnlyTheMaxedNumber() {
        val text = annotatedCells(listOf(skillCell(10), skillCell(9), skillCell(null)), scheme)
        assertEquals("10/9/–", text.text)
        val gold = text.spanStyles.filter { it.item.color == scheme.tertiary }
        assertEquals(listOf(0 to 2), gold.map { it.start to it.end })
    }

    @Test fun classChipsShareDisplayedLabels() {
        val groups = classGroups(listOf("beast", "beastEresh", "saber")) { if (it.startsWith("beast")) "Beast" else it }
        assertEquals(listOf("saber", "Beast"), groups.keys.toList())
        assertEquals(setOf("beast", "beastEresh"), groups["Beast"])
        assertEquals("120/120", levelText(120, 120))
        assertEquals("–/120", levelText(null, 120))
    }

    @Test fun classChipsFollowTheGameOrderWithBeastsAndUnknownLast() {
        val raw = listOf("unknownB", "beast", "foreigner", "avenger", "saber", "alterEgo", "unBeastOlgaMarie", "shielder", "archer", "pretender", "moonCancer", "ruler", "berserker", "assassin", "caster", "rider", "lancer", "beastEresh", "unknownA")
        assertEquals(
            listOf("saber", "archer", "lancer", "rider", "caster", "assassin", "berserker", "shielder", "ruler", "alterEgo", "avenger", "moonCancer", "foreigner", "pretender"),
            classGroups(raw) { it }.keys.take(14),
        )
        assertEquals(listOf("beast", "beastEresh", "unBeastOlgaMarie", "unknownA", "unknownB"), classGroups(raw) { it }.keys.drop(14))
        val grouped = classGroups(listOf("beast", "saber", "beastEresh")) { if (it.startsWith("beast")) "Beast" else it }
        assertEquals(listOf("saber", "Beast"), grouped.keys.toList())
    }

    private fun rows() = listOf(
        DashboardServant(1, AtlasBasic(1, "Alpha", "normal", "saber", 5), 5, 0, 2, listOf(10, 10, 9), 90, 50, listOf(10, 0, null, null, null)),
        DashboardServant(2, AtlasBasic(2, "Beta", "normal", "caster", 4), 10, 0, 1, listOf(1, 1, 1), 100, 80, listOf(null, null, null, null, null)),
        DashboardServant(3, AtlasBasic(3, "Gamma", "normal", "saber", 3), 7, 0, 5, listOf(10, 10, 10), 120, 120, listOf(10, 10, 10, 10, 10)),
    )

    private fun show(width: Int, servants: List<DashboardServant> = rows()) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons {
            AccountDashboardContent(fixture.data.copy(servants = servants, inventory = null), fixture.names, "샘플", {}, {}, initialSection = 3)
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun order(): List<String> = listOf("Alpha", "Beta", "Gamma")
        .mapNotNull { n -> compose.onAllNodesWithText(n).fetchSemanticsNodes().firstOrNull()?.let { n to it.boundsInRoot.top } }
        .sortedBy { it.second }.map { it.first }

    @Test fun sortChipsReorderTheList() {
        show(1280)
        compose.waitUntil(10_000) { order() == listOf("Beta", "Gamma", "Alpha") }
        compose.onNodeWithTag("dashboard-sort:Level").performClick()
        compose.waitUntil(10_000) { order() == listOf("Gamma", "Beta", "Alpha") }
        compose.onNodeWithTag("dashboard-sort:Cost").performClick()
        compose.waitUntil(10_000) { order() == listOf("Alpha", "Beta", "Gamma") }
    }

    @Test fun classChipFiltersAndAllRestores() {
        show(1280)
        compose.onNodeWithTag("dashboard-class:all").assertIsSelected()
        compose.onNodeWithTag("dashboard-class:세이버").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Beta").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("Alpha").assertCountEquals(1)
        compose.onAllNodesWithText("Gamma").assertCountEquals(1)
        compose.onNodeWithTag("dashboard-class:all").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Beta").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun wideLayoutIsATableWithHeaderAndLockedAppendDashes() {
        show(1280)
        compose.onNodeWithTag("dashboard-servant-header").assertExists()
        listOf("이름", "Lv", "인연", "보구", "스킬", "어펜드").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("dashboard-servant-header"))).assertExists()
        }
        compose.onAllNodesWithTag("dashboard-servant-detail").assertCountEquals(0)
        compose.onAllNodesWithTag("dashboard-servant-appends").filter(hasText("10/–/–/–/–")).assertCountEquals(1)
        compose.onAllNodesWithTag("dashboard-servant-appends").filter(hasText("–/–/–/–/–")).assertCountEquals(1)
        compose.onAllNodesWithTag("dashboard-servant-skills").filter(hasText("10/10/9")).assertCountEquals(1)
        compose.onNodeWithTag("dashboard-servant-owned").assertTextContains("보유 3", substring = true)
        compose.onNodeWithTag("dashboard-servant-all-np").assertTextContains("전체 보합 8", substring = true)
    }

    @Test fun narrowLayoutHasThreeLineRowsWithTheSameNumbers() {
        show(360)
        compose.onNodeWithTag("dashboard-servant-header").assertDoesNotExist()
        compose.onAllNodesWithTag("dashboard-servant-detail").assertCountEquals(3)
        fun texts(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text } }
        assertTrue(texts("dashboard-servant-detail").toString(), "Lv 50/90 · 인연 5 · 보구 2" in texts("dashboard-servant-detail"))
        assertTrue(texts("dashboard-servant-levels").toString(), "스킬 10/10/9 · 어펜드 10/–/–/–/–" in texts("dashboard-servant-levels"))
        compose.onAllNodesWithTag("dashboard-servant-levels").assertCountEquals(3)
        texts("dashboard-servant-detail").plus(texts("dashboard-servant-levels")).forEach { assertFalse(it.trimEnd().endsWith("·")) }
    }

    @Test fun titleStaysOnOneLineAt360() {
        show(360)
        val layout = compose.onNodeWithTag("dashboard-title").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { f ->
                val out = mutableListOf<TextLayoutResult>(); f(out); out.first()
            }
        assertEquals(1, layout!!.lineCount)
        assertFalse(layout.hasVisualOverflow)
    }
}
