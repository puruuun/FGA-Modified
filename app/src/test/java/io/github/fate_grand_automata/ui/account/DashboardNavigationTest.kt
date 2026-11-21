package io.github.fate_grand_automata.ui.account

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
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
class DashboardNavigationTest(private val width: Int) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp")
        fun widths() = listOf(360, 839, 840, 1280)
    }

    private val wide get() = width >= 840
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val case by lazy { FormationCase(grand = true) }
    private val all by lazy { allSectionsData(case) }

    private fun show(
        data: DashboardData = all, initialSection: Int = 0, battle: io.github.fate_grand_automata.battlelog.BattleLogEntry? = null,
        openLogs: () -> Unit = {},
    ) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { case.fixture.Icons {
            AccountDashboardContent(data, case.names, "샘플", {}, openLogs, battle = battle, initialSection = initialSection, userId = "nav", friendCount = 143)
        } } } }
    }

    private fun nav(section: DashboardSection) = compose.onNodeWithTag("dashboard-nav:${section.name}")
    private fun select(section: DashboardSection) { nav(section).performScrollTo().performClick(); compose.waitForIdle() }

    @Test fun navigationFollowsTheBreakpoint() {
        show()
        val bounds = DashboardSection.entries.map { nav(it).assertExists().getUnclippedBoundsInRoot() }
        val header = compose.onNodeWithTag("dashboard-account-header").getUnclippedBoundsInRoot()
        if (wide) {
            val pane = compose.onNodeWithTag("dashboard-nav-pane").getUnclippedBoundsInRoot()
            assertEquals(240f, (pane.right - pane.left).value, 0.5f)
            bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom <= b.top); assertEquals(a.left, b.left) }
            assertTrue(header.left >= pane.left && header.right <= pane.right)
            assertTrue(header.bottom <= bounds.first().top)
            assertTrue(compose.onNodeWithTag("dashboard-page").getUnclippedBoundsInRoot().left >= pane.right)
        } else {
            compose.onNodeWithTag("dashboard-nav-pane").assertDoesNotExist()
            bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left + 0.5f.dp()); assertEquals(a.top, b.top) }
            assertTrue(header.bottom <= bounds.first().top)
        }
    }

    private fun Float.dp() = androidx.compose.ui.unit.Dp(this)

    @Test fun theDashboardOpensOnOverviewAndShowsOneSectionAtATime() {
        show()
        nav(DashboardSection.Overview).assertIsSelected()
        compose.onNodeWithTag("dashboard-overview").assertExists()
        listOf(3, 4, 5, 7, 8, 10, 12, 13, 14).forEach { compose.onNodeWithTag("dashboard-section:$it").assertDoesNotExist() }

        select(DashboardSection.Formations)
        nav(DashboardSection.Formations).assertIsSelected()
        nav(DashboardSection.Overview).assertIsNotSelected()
        compose.onNodeWithTag("dashboard-overview").assertDoesNotExist()
        listOf(7, 8, 5).forEach { compose.onNodeWithTag("dashboard-section:$it").assertExists() }
        compose.onNodeWithTag("dashboard-section:3").assertDoesNotExist()
    }

    @Test fun servantAndPresentBoxItemsShowTheirCounts() {
        show()
        nav(DashboardSection.Servants).assertTextContains("35", substring = true)
        nav(DashboardSection.PresentBox).assertTextContains("3", substring = true)
        nav(DashboardSection.Items).assertTextContains("아이템", substring = true)
        assertFalse(nav(DashboardSection.Items).fetchSemanticsNode().config[SemanticsProperties.Text].any { it.text.any(Char::isDigit) })
    }

    @Test fun initialSectionSelectsTheContainingSection() {
        val expected = mapOf(
            3 to DashboardSection.Servants, 4 to DashboardSection.Items, 5 to DashboardSection.Formations,
            7 to DashboardSection.Formations, 8 to DashboardSection.Formations, 10 to DashboardSection.PresentBox,
            12 to DashboardSection.Collection, 13 to DashboardSection.Collection, 14 to DashboardSection.Collection,
            0 to DashboardSection.Overview, 6 to DashboardSection.Overview,
        )
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        val id = mutableStateOf(3)
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { case.fixture.Icons {
            androidx.compose.runtime.key(id.value) { AccountDashboardContent(all, case.names, "샘플", {}, {}, initialSection = id.value) }
        } } } }
        expected.forEach { (block, section) ->
            compose.runOnIdle { id.value = block }
            compose.waitForIdle()
            nav(section).assertIsSelected()
            if (section != DashboardSection.Overview) compose.onNodeWithTag("dashboard-section:$block").assertExists()
            else compose.onNodeWithTag("dashboard-overview").assertExists()
        }
    }

    @Test fun initialGrandFormationIsBroughtIntoView() {
        show(initialSection = 8)
        nav(DashboardSection.Formations).assertIsSelected()
        compose.onNodeWithText("관위 편성").assertIsDisplayed()
    }

    @Test fun initialSummonsAreBroughtIntoView() {
        show(initialSection = 14)
        compose.onNodeWithText("소환 기록").assertIsDisplayed()
    }

    @Test fun everyMovedBlockStillCarriesItsTestTag() {
        show()
        mapOf(
            DashboardSection.Servants to listOf(3), DashboardSection.Items to listOf(4), DashboardSection.Formations to listOf(7, 8, 5),
            DashboardSection.Collection to listOf(12, 13, 14), DashboardSection.PresentBox to listOf(10),
        ).forEach { (section, blocks) ->
            select(section)
            blocks.forEach { compose.onNodeWithTag("dashboard-section:$it").assertExists() }
        }
        // Recent activity has no block of its own; it is part of Overview.
        compose.onNodeWithTag("dashboard-section:6").assertDoesNotExist()
    }

    @Test fun theChosenSectionSurvivesRecreation() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { case.fixture.Icons {
            AccountDashboardContent(all, case.names, "샘플", {}, {})
        } } } }
        select(DashboardSection.Items)
        compose.onNodeWithTag("dashboard-section:4").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        nav(DashboardSection.Items).assertIsSelected()
        compose.onNodeWithTag("dashboard-section:4").assertExists()
        compose.onNodeWithTag("dashboard-overview").assertDoesNotExist()
    }

    @Test fun aSectionThatLosesItsDataFallsBackToOverview() {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        val data = mutableStateOf(all)
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { case.fixture.Icons {
            AccountDashboardContent(data.value, case.names, "샘플", {}, {})
        } } } }
        select(DashboardSection.PresentBox)
        compose.onNodeWithTag("dashboard-section:10").assertExists()
        compose.runOnIdle { data.value = all.copy(presentBox = null) }
        compose.waitForIdle()
        nav(DashboardSection.PresentBox).assertDoesNotExist()
        nav(DashboardSection.Overview).assertIsSelected()
        compose.onNodeWithTag("dashboard-overview").assertExists()
    }

    @Test fun headerShowsNamesServerLevelFriendCodeAndRelativeUpdateTime() {
        val updated = System.currentTimeMillis() / 1000 - 5 * 60 - 20
        show(all.copy(lastUpdateTime = updated))
        val header = hasAnyAncestor(hasTestTag("dashboard-account-header"))
        compose.onNode(hasText("샘플 · 샘플 마스터") and header).assertExists()
        compose.onNode(hasText("KR") and header).assertExists()
        compose.onNode(hasText("Lv 160") and header).assertExists()
        compose.onNode(hasText("123456789") and header).assertExists()
        compose.onNodeWithTag("dashboard-updated").assertTextEquals("5분 전 갱신")
        val description = compose.onNodeWithTag("dashboard-updated").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.endsWith(date(updated)))
    }

    @Test fun friendCodeIsCopiedToTheClipboard() {
        show()
        compose.onNode(hasContentDescription("친구 코드 복사")).performClick()
        compose.waitForIdle()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("123456789", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test fun viewAllOpensTheLog() {
        var opened = 0
        show(battle = battleLogFixture("latest"), openLogs = { opened++ })
        compose.onNodeWithTag("overview-recent").assertExists()
        compose.onNodeWithText("전체 보기").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onAllNodes(hasText("전체 보기")).assertCountEquals(1)
    }

    @Test fun overviewHidesRecordsWhenThereAreNone() {
        show()
        compose.onNodeWithTag("overview-recent").assertDoesNotExist()
    }

    @Test fun overviewHeadlineNumbersFruitsResourcesAndStorage() {
        show()
        compose.onNodeWithTag("overview-ap").assertExists()
        compose.onNodeWithTag("overview-ap-bar").assertExists()
        compose.onNode(hasText("이월 AP 12") and hasAnyAncestor(hasTestTag("overview-ap"))).assertExists()
        compose.onNode(hasText("123") and hasAnyAncestor(hasTestTag("overview-quartz"))).assertExists()
        compose.onNode(hasText("무료 100 · 유료 23") and hasAnyAncestor(hasTestTag("overview-quartz"))).assertExists()
        compose.onNode(hasText("123,456,789") and hasAnyAncestor(hasTestTag("overview-qp"))).assertExists()

        val fruitX = listOf(100, 101, 104, 102).map { compose.onNodeWithTag("overview-fruit:$it").getUnclippedBoundsInRoot().left.value }
        assertEquals(fruitX.sorted(), fruitX)
        val resourceY = listOf(3, 18, 16, 103, 4001, 49).map { compose.onNodeWithTag("overview-resource:$it").getUnclippedBoundsInRoot().top.value }
        assertEquals(resourceY.sorted(), resourceY)
        // Quartz and QP are headline numbers, not rows of the resource list; fruit is not a row either.
        listOf(1, 2, 100, 101, 102, 104).forEach { compose.onNodeWithTag("overview-resource:$it").assertDoesNotExist() }

        compose.onNodeWithTag("overview-storage").assertExists()
        compose.onNodeWithTag("overview-storage-bar").assertExists()
        compose.onNode(hasText("143 / 100") and hasAnyAncestor(hasTestTag("overview-account:friends"))).assertExists()
        compose.onNode(hasText("123,456") and hasAnyAncestor(hasTestTag("overview-account:friendPoint"))).assertExists()
    }

    @Test fun overviewListsTwoColumnsOnWideAndOneOnNarrow() {
        show(all.copy(storage = all.storage))
        val resources = compose.onNodeWithTag("overview-fruits").getUnclippedBoundsInRoot()
        val storage = compose.onNodeWithTag("overview-storage").getUnclippedBoundsInRoot()
        if (wide) {
            assertTrue(resources.right <= storage.left)
            assertEquals(resources.top.value, storage.top.value, 40f)
        } else {
            assertEquals(resources.left, storage.left)
            assertTrue(resources.bottom <= storage.top)
        }
    }

    @Test fun overviewNeverInventsValuesForMissingData() {
        show(all.copy(resources = emptyMap(), teapots = null, storage = null, game = null, profile = emptyMap(), freePaid = null))
        listOf("overview-fruits", "overview-resources", "overview-storage", "overview-ap", "overview-quartz", "overview-qp")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        listOf("friendPoint", "exp", "cost", "friends", "created", "commandSpell").forEach { compose.onNodeWithTag("overview-account:$it").assertDoesNotExist() }
        compose.onNodeWithTag("overview-account:snapshot").assertExists()
    }

    @Test fun apShowsFullOrTheTimeItBecomesFull() {
        val game = { recoverAt: Long -> dashboardData(dashboardState("userGame" to """[{"actMax":144,"actRecoverAt":$recoverAt}]"""), case.names) }
        show(game(1))
        compose.onNode(hasText("144 / 144") and hasAnyAncestor(hasTestTag("overview-ap"))).assertExists()
        compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("overview-ap"))).assertExists()
    }

    @Test fun apShowsTheClockTimeWhenNotYetFull() {
        val recoverAt = System.currentTimeMillis() / 1000 + 3 * 3600
        show(dashboardData(dashboardState("userGame" to """[{"actMax":144,"actRecoverAt":$recoverAt}]"""), case.names))
        val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag("overview-ap")), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(texts.toString(), texts.any { Regex("""\d{2}:\d{2} 가득 참""").matches(it) })
    }

    @Test fun formationLongCeNamesKeepLevelAndMaxVisible() {
        val longName = "아주 긴 개념예장 이름을 줄여도 레벨과 최대 표시는 남습니다"
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h1400dp-hdpi")
        compose.setContent { FGATheme { case.fixture.Icons {
            AccountDashboardContent(case.data, case.names.copy(equips = case.names.equips.mapValues { longName }), "샘플", {}, {}, initialSection = 7)
        } } }
        val names = compose.onAllNodes(hasAnyAncestor(hasTestTag("formation-ce-line")) and hasText(longName), useUnmergedTree = true)
        assertTrue(names.fetchSemanticsNodes().isNotEmpty())
        names.fetchSemanticsNodes().indices.forEach { index ->
            val layouts = mutableListOf<TextLayoutResult>()
            names[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            assertTrue("visible chars ${layout.getLineEnd(0, visibleEnd = true)}", layout.getLineEnd(0, visibleEnd = true) >= 8)
        }
        val lines = compose.onAllNodesWithTag("formation-ce-line", useUnmergedTree = true)
        lines.fetchSemanticsNodes().indices.forEach { index ->
            val name = lines[index].onChildren()[0].getUnclippedBoundsInRoot()
            val level = lines[index].onChildren()[1].getUnclippedBoundsInRoot()
            assertEquals(name.right.value, level.left.value, 2f)
        }
    }
}
