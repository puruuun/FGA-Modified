package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.DashboardEquip
import io.github.fate_grand_automata.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class DashboardMasonryUiTest(private val width: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp")
        fun widths() = listOf(360, 839, 840, 1280)
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun knownHeightsGoToTheShorterColumnWithLeftTies() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        // Includes equal-height ties and cards shorter/taller than the previous card.
        val heights = listOf(100, 200, 50, 26, 300, 20, 60, 30, 90)
        val expected = if (width >= 840) listOf(0 to 0, 1 to 0, 0 to 112, 0 to 174,
            0 to 212, 1 to 212, 1 to 244, 1 to 316, 1 to 358)
        else listOf(0, 112, 324, 386, 424, 736, 768, 840, 882).map { 0 to it }
        compose.setContent {
            DashboardMasonry(width >= 840, Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                heights.forEachIndexed { index, height -> Box(Modifier.height(height.dp).testTag("card:$index")) }
            }
        }
        heights.forEachIndexed { index, height ->
            compose.onAllNodesWithTag("card:$index").assertCountEquals(1)
            val bounds = compose.onNodeWithTag("card:$index").getUnclippedBoundsInRoot()
            val (column, top) = expected[index]
            assertEquals(top.toFloat(), bounds.top.value, 0.1f)
            assertEquals(height.toFloat(), (bounds.bottom - bounds.top).value, 0.1f)
            if (width >= 840) {
                val stack = compose.onNodeWithTag("dashboard-stack:$column").getUnclippedBoundsInRoot()
                assertEquals(stack.left, bounds.left)
                assertEquals(stack.right - stack.left, bounds.right - bounds.left)
            } else assertEquals(0f, bounds.left.value, 0.1f)
        }
        if (width >= 840) repeat(2) { column ->
            val stack = compose.onNodeWithTag("dashboard-stack:$column").getUnclippedBoundsInRoot()
            assertEquals(listOf(512f, 448f)[column], (stack.bottom - stack.top).value, 0.1f)
        }
    }

    @Test fun everyDashboardCardOnceInReadingOrder() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        val case = FormationCase(grand = true)
        val data = case.fixture.data.copy(formations = case.data.formations, equips = listOf(DashboardEquip(1, 10, true)))
        compose.setContent { FGATheme {
            case.fixture.Icons { AccountDashboardContent(data, case.names, "샘플", {}, {}, battle = battleLogFixture("recent")) }
        } }
        val order = listOf(0, 1, 2, 3, 4, 7, 8, 5, 6)
        val tops = order.mapIndexed { index, id ->
            val card = compose.onAllNodesWithTag("dashboard-section:$id").assertCountEquals(1)[0]
            assertEquals(index.toFloat(), card.fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
            card.getUnclippedBoundsInRoot()
        }
        tops.zipWithNext().forEach { (a, b) ->
            assertTrue(a.top <= b.top)
            if (width < 840) { assertEquals(a.left, b.left); assertEquals(12f, (b.top - a.bottom).value, 0.1f) }
        }
    }

    @Test fun initialFormationSectionIsBroughtIntoView() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        val case = FormationCase(grand = true)
        compose.setContent { FGATheme {
            case.fixture.Icons { AccountDashboardContent(case.fixture.data.copy(formations = case.data.formations),
                case.names, "샘플", {}, {}, initialSection = 8) }
        } }
        compose.onNodeWithText("관위 편성").assertIsDisplayed()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardMasonryScreenshotTest(private val width: Int, private val height: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}")
        fun sizes() = listOf(arrayOf<Any>(360, 3000), arrayOf<Any>(840, 1800), arrayOf<Any>(1280, 1600))
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun allCards() {
        val case = FormationCase(grand = true)
        compose.shoot("dashboard-masonry", "AllCards", width, height, locale = "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 서번트 1", substring = true).fetchSemanticsNodes().size >= 3 }
        }) {
            case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(case.fixture.data.copy(formations = case.data.formations,
                    equips = listOf(DashboardEquip(1, 10, true))), case.names, "샘플 별명", {}, {},
                    battle = battleLogFixture("recent"), server = "JP")
            } }
        }
    }

    @Test fun longCeNameShowsEightCharactersAndLevelFollowsWithoutGap() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h1400dp-hdpi")
        val case = FormationCase(grand = true)
        val longName = "아주 긴 개념예장 이름을 줄여도 레벨과 최대 표시는 남습니다"
        compose.setContent { FGATheme { case.fixture.Icons {
            AccountDashboardContent(case.data, case.names.copy(equips = case.names.equips.mapValues { longName }),
                "샘플", {}, {}, initialSection = 7)
        } } }
        val names = compose.onAllNodes(hasAnyAncestor(hasTestTag("formation-ce-line")) and hasText(longName), useUnmergedTree = true)
        assertTrue(names.fetchSemanticsNodes().isNotEmpty())
        names.fetchSemanticsNodes().indices.forEach { index ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            names[index].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            assertTrue("visible chars ${layout.getLineEnd(0, visibleEnd = true)}", layout.getLineEnd(0, visibleEnd = true) >= 8)
        }
        val lines = compose.onAllNodesWithTag("formation-ce-line", useUnmergedTree = true)
        lines.fetchSemanticsNodes().indices.forEach { index ->
            val line = lines[index]
            val name = line.onChildren()[0].getUnclippedBoundsInRoot()
            val level = line.onChildren()[1].getUnclippedBoundsInRoot()
            assertEquals(name.right.value, level.left.value, 2f)
        }
    }

    @Test fun longCeNames() {
        val case = FormationCase(grand = true)
        compose.shoot("dashboard-masonry", "FormationRows", width, 1400, locale = "ko") {
            case.fixture.Icons {
                AccountDashboardContent(case.data, case.names.copy(equips = case.names.equips.mapValues {
                    "아주 긴 개념예장 이름을 줄여도 레벨과 최대 표시는 남습니다 ${it.value}"
                }), "샘플 별명", {}, {}, initialSection = 7, server = "JP")
            }
        }
    }
}
