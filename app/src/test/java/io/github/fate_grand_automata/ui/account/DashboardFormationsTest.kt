package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic account with own support decks and, optionally, Grand seats. */
internal class FormationCase(grand: Boolean, formations: Boolean = true) {
    val fixture = DashboardUiFixture()
    val names = fixture.names.copy(
        servants = mapOf(1L to "샘플 서번트 1", 2L to "샘플 서번트 2", 3L to "샘플 서번트 3", 4L to "샘플 서번트 4"),
        equips = mapOf(9000L to "샘플 예장 A", 9001L to "샘플 예장 B", 9002L to "샘플 예장 C"),
    )

    private fun leader(deck: Int, cls: Int, svt: Int, grandSvt: Int = 0, ce2: Boolean = false) =
        """{"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"grandSvt":$grandSvt,"lv":${80 + svt},"treasureDeviceLv":${1 + svt},
            "skillLv1":10,"skillLv2":9,"skillLv3":8,"appendPassiveSkill":[{"skillId":3001000,"skillLv":5},{"skillId":3002000,"skillLv":4}],
            "equipTarget1":{"svtId":9000,"limitCount":4,"lv":100}${if (ce2) ""","equipTarget2":{"svtId":9001,"limitCount":1,"lv":40}""" else ""}}"""

    private fun seat(id: Int, svt: Int, bond: String) =
        """{"grandGraphId":$id,"svtId":$svt,"lv":120,"treasureDeviceLv":5,"skillLv1":10,"skillLv2":10,"skillLv3":10,
            "appendPassiveSkill":[{"skillId":3001000,"skillLv":5},{"skillId":3034000,"skillLv":3}],
            "equipTarget1":{"svtId":9000,"limitCount":4,"lv":100},"equipTarget3":{"svtId":9002,"limitCount":2,"lv":60}$bond}"""

    private val tables = buildList {
        add("userGame" to """[{"name":"샘플 마스터","lv":160,"friendCode":"123456789","fixMainSupportDeckIds":[101,102],"fixEventSupportDeckIds":[201]}]""")
        if (formations) add("userSvtLeader" to listOf(
            leader(101, 0, 1), leader(101, 1, 2, grandSvt = 1), leader(101, 2, 3), leader(101, 8, 4, ce2 = true),
            leader(102, 1, 3), leader(201, 5, 4),
        ).joinToString(",", "[", "]"))
        if (grand) add("userSvtGrand" to listOf(
            seat(10001, 1, ""","equipTarget2":{"svtId":9300000},"script":{"equipTarget2SkillChange":1}"""),
            seat(10002, 2, ""","equipTarget2":{"svtId":9300000}"""),
            seat(10003, 3, ""),
        ).joinToString(",", "[", "]"))
    }

    /** A short dashboard keeps the new sections in the picture. */
    val data = dashboardData(dashboardState(*tables.toTypedArray()), names).let {
        it.copy(profile = fixture.data.profile, servants = null, inventory = null, equips = null, storage = null)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2000dp-hdpi")
class DashboardFormationsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun show(case: FormationCase, data: DashboardData = case.data) = compose.setContent {
        FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) {
            case.fixture.Icons { AccountDashboardContent(data, case.names, "별명", {}, {}, userId = "formations") }
        } }
    }

    private fun rows(texts: Int = 0) = compose.onAllNodesWithTag("formation-row").fetchSemanticsNodes().size + texts

    @Test fun supportSectionListsTheNormalDeckAndSwitchesDeckAndKind() {
        show(FormationCase(grand = false))
        compose.onNodeWithTag("dashboard-section:7").assertExists()
        compose.onNodeWithText("서포트 편성").assertExists()
        compose.onNodeWithTag("formation-time", useUnmergedTree = true).assertDoesNotExist()
        // Deck 1 (id 101): four class slots, one of them with the Grand badge and one with two CEs.
        assertEquals(4, rows())
        assertEquals(1, compose.onAllNodesWithTag("formation-grand-badge").fetchSemanticsNodes().size)
        compose.onAllNodesWithText("관위").assertCountEquals(1)
        compose.onAllNodesWithText("샘플 서번트 4").assertCountEquals(1)
        compose.onAllNodesWithText("샘플 예장 B", substring = true).assertCountEquals(1)
        compose.onAllNodesWithTag("formation-appends").assertCountEquals(0)
        compose.onNodeWithText("10/9/8").assertDoesNotExist()
        compose.onNodeWithText("5/4/-/-/-").assertDoesNotExist()
        compose.onNodeWithText("스킬").assertDoesNotExist()
        compose.onNodeWithText("어펜드").assertDoesNotExist()
        compose.onNodeWithText("Lv81 · 보2 ·", substring = true).assertExists()
        // Deck 2 (id 102) has one slot.
        compose.onNodeWithText("편성 2").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, rows())
        // The event kind lists its own deck only.
        compose.onNodeWithTag("formation-kind-event").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, rows())
        compose.onAllNodesWithText("편성 1").assertCountEquals(1)
        compose.onAllNodesWithText("편성 2").assertCountEquals(0)
        compose.onAllNodesWithText("캐스터", substring = true).assertCountEquals(1)
    }

    @Test fun grandSectionIsHiddenWithoutGrandRowsAndShownInSeatOrderWithBondLabels() {
        val krShaped = FormationCase(grand = false)
        show(krShaped)
        compose.onNodeWithTag("dashboard-section:8").assertDoesNotExist()
        compose.onAllNodesWithTag("formation-bond-effect").assertCountEquals(0)
    }

    @Test fun grandSectionShowsEverySeatWithItsBondEffect() {
        show(FormationCase(grand = true))
        compose.onNodeWithTag("dashboard-section:8").assertExists()
        val labels = compose.onAllNodesWithTag("formation-bond-effect")
        labels.assertCountEquals(3)
        assertEquals(listOf("인연: NP 차지", "인연: 기본 효과", "인연: 없음"),
            (0..2).map { labels[it].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { t -> t.text } })
        val seats = (1..3).map { compose.onNodeWithTag("formation-seat:$it").getUnclippedBoundsInRoot() }
        seats.zipWithNext().forEach { (a,b) -> assertTrue(a.bottom < b.top) }
        compose.onAllNodesWithTag("formation-time", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("데이터 기준", substring = true).assertDoesNotExist()
        val grand = hasAnyAncestor(hasTestTag("dashboard-section:8"))
        compose.onAllNodes(hasTestTag("formation-ce-line") and grand).assertCountEquals(6)
        compose.onNodeWithText("10/10/10").assertDoesNotExist()
    }

    @Test fun missingFormationDataShowsTheNoDataMessage() {
        val case = FormationCase(grand = false, formations = false)
        assertNull(case.data.formations)
        show(case)
        compose.onNodeWithTag("dashboard-section:7").assertExists()
        compose.onNodeWithTag("formation-none", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("편성 정보 없음").assertExists()
        compose.onNodeWithTag("dashboard-section:8").assertDoesNotExist()
    }

    @Test fun ceNamesEllipsizeOnOneLineWhileLevelAndMaxRemainVisible() {
        val case = FormationCase(grand = true)
        val longName = "아주 긴 개념예장 이름을 반복해서 한 줄보다 길게 표시하는 테스트"
        compose.setContent { FGATheme { case.fixture.Icons {
            AccountDashboardContent(case.data, case.names.copy(equips = case.names.equips.mapValues { longName }),
                "샘플", {}, {})
        } } }
        val ceText = hasAnyAncestor(hasTestTag("formation-ce-line")) and hasText(longName)
        val names = compose.onAllNodes(ceText, useUnmergedTree = true)
        assertEquals(11, names.fetchSemanticsNodes().size) // support 5, Grand CE1/CE3 x 3
        for (index in names.fetchSemanticsNodes().indices) {
            val layouts = mutableListOf<TextLayoutResult>()
            names[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
        val lines = compose.onAllNodesWithTag("formation-ce-line")
        lines.fetchSemanticsNodes().indices.forEach { index ->
            val bounds = lines[index].getUnclippedBoundsInRoot()
            val texts = lines[index].onChildren().filter(hasText(" · Lv", substring = true) or hasText(" · 최대"))
            texts.fetchSemanticsNodes().indices.forEach { i ->
                val textBounds = texts[i].getUnclippedBoundsInRoot()
                assertTrue(textBounds.left >= bounds.left && textBounds.right <= bounds.right)
            }
        }
        compose.onAllNodesWithText(" · 최대", useUnmergedTree = true).assertCountEquals(7)
        compose.onAllNodesWithText(" · Lv40", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(" · Lv60", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test fun emptyCesHaveOneLineIncludingBothEmptyGrandEquipmentSlots() {
        val case = FormationCase(grand = true)
        val formations = case.data.formations!!
        val empty = formations.copy(main = formations.main.take(1).map { deck ->
            deck.copy(slots = deck.slots.take(1).map { it.copy(ceId = 0, ce2Id = null) })
        }, grand = formations.grand.take(1).map { it.copy(
            ce1 = io.github.fate_grand_automata.scripts.capture.FriendCe(),
            ce3 = io.github.fate_grand_automata.scripts.capture.FriendCe()) })
        show(case, case.data.copy(formations = empty))
        val none = compose.onAllNodesWithText("예장 없음")
        none.assertCountEquals(3)
        repeat(3) { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            none[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
        }
        compose.onAllNodesWithText(" · 최대").assertCountEquals(0)
        // Only the servant face uses the Atlas icon store, including the Grand seat.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().size == 2 }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardFormationsScreenshotTest(private val width: Int, private val height: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}")
        fun sizes() = listOf(arrayOf<Any>(360, 1900), arrayOf<Any>(840, 1500))
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun shot(name: String, case: FormationCase) = compose.shoot("dashboard-formations", name, width, height, locale = "ko") {
        CompositionLocalProvider(LocalNavigateBack provides {}) {
            case.fixture.Icons { AccountDashboardContent(case.data, case.names, "샘플 별명", {}, {}, userId = "formations") }
        }
    }

    @Test fun bothSections() = shot("BothSections", FormationCase(grand = true))
    @Test fun krShapedWithoutGrand() = shot("KrWithoutGrand", FormationCase(grand = false))
    @Test fun noFormationData() = shot("NoFormationData", FormationCase(grand = false, formations = false))
}
