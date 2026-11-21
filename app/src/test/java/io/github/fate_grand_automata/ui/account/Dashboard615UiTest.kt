package io.github.fate_grand_automata.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

/** The dashboard additions of #615: in-progress block, login cells, parties, servant command codes and card boost. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class Dashboard615UiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val icons by lazy { DashboardUiFixture() }
    private val fixture by lazy { Round615Fixture() }
    private var primary = 0
    private var tertiary = 0

    private fun show(
        width: Int, data: DashboardData = fixture.data(), progress: DashboardProgress = fixture.progress, section: Int = 0,
    ) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { icons.Icons {
            val scheme = MaterialTheme.colorScheme
            SideEffect { primary = scheme.primary.toArgb(); tertiary = scheme.tertiary.toArgb() }
            AccountDashboardContent(data, fixture.names, "샘플", {}, {}, initialSection = section, userId = "r615", friendCount = 143, progress = progress)
        } } } }
        compose.waitForIdle()
    }

    private fun texts(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    private fun allTexts(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { text -> text.text }
    }
    /** The text of every text node below [tag], in tree order; the tag sits on a container, not on a merged text. */
    private fun under(tag: String) = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().joinToString("|") { it.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { text -> text.text } }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun waitForServants() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }

    // In-progress block

    @Test fun onNarrowTheBlockSitsBetweenTheApplesAndTheServants() {
        show(360)
        val fruits = bounds("overview-fruits")
        val block = bounds("overview-progress")
        val servants = bounds("overview-servants")
        assertTrue(fruits.bottom <= block.top)
        assertTrue(block.bottom <= servants.top)
        assertEquals(fruits.left.value, block.left.value, 0.5f)
    }

    @Test fun onWideTheBlockLeadsTheRightColumnAboveTheStorage() {
        show(1280)
        val block = bounds("overview-progress")
        val storage = bounds("overview-storage")
        val resources = bounds("overview-resources")
        assertEquals(block.left.value, storage.left.value, 0.5f)
        assertTrue(block.bottom <= storage.top)
        assertTrue(block.left > resources.left)
        assertEquals(resources.top.value, block.top.value, 0.5f)
    }

    @Test fun theWeeklyLineShowsTheSetDatesAndTheDoneCount() {
        show(1280)
        val weekly = fixture.progress.weekly!!
        val (from, to) = weeklyDates(weekly.start, weekly.end)
        assertEquals("주간 미션 · $from ~ $to · 5 / 7 완료", texts("overview-progress-weekly"))
    }

    @Test fun eachRunningEventIsALineWithPointsOverTheLastRewardAndABar() {
        show(1280)
        assertEquals("샘플 여름 이벤트 · 1,234,567 / 2,000,000", under("overview-progress-event:80500"))
        compose.onNodeWithTag("overview-progress-bar:80500").assertExists()
        assertTrue(under("overview-progress-event:80501").contains("3,000,000 / 3,000,000"))
    }

    @Test fun anEventWithoutPointRewardsHasNoBarAndNoTotal() {
        val open = DashboardProgress(null, listOf(EventProgress(7, "샘플 무보상", 1234, null, 0, Long.MAX_VALUE)))
        show(1280, progress = open)
        assertEquals("샘플 무보상 · 포인트 1,234", under("overview-progress-event:7"))
        compose.onNodeWithTag("overview-progress-bar:7").assertDoesNotExist()
    }

    @Test fun theBarAndValueAreGoldOnlyWhenThePointsReachTheLastReward() {
        show(1280)
        fun fill(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let { it.getPixel(6, it.height / 2) }
        assertNotEquals(primary, tertiary)
        assertEquals(primary, fill("overview-progress-bar:80500"))
        assertEquals(tertiary, fill("overview-progress-bar:80501"))
    }

    @Test fun eventsThatAreNotRunningAreLeftOutAndAnEmptyBlockIsHidden() {
        val now = System.currentTimeMillis() / 1000
        val notRunning = DashboardProgress(null, listOf(
            EventProgress(1, "끝남", 1, 10, now - 100, now - 1), EventProgress(2, "예정", 1, 10, now + 100, now + 200),
        ))
        show(1280, progress = notRunning)
        compose.onNodeWithTag("overview-progress").assertDoesNotExist()
    }

    @Test fun withoutAnyLineTheBlockIsHiddenOnNarrowToo() {
        show(360, progress = DashboardProgress())
        compose.onNodeWithTag("overview-progress").assertDoesNotExist()
        compose.onNodeWithTag("overview-servants").assertExists()
    }

    @Test fun aWeeklyLineAloneOrEventsAloneStillShowTheBlock() {
        show(1280, progress = fixture.progress.copy(events = emptyList()))
        compose.onNodeWithTag("overview-progress-weekly").assertExists()
        compose.onNodeWithTag("overview-progress-event:80500").assertDoesNotExist()
    }

    // Login cells

    @Test fun theLoginCellsShowTheCountsAndTheDaysLeft() {
        show(1280)
        assertEquals("누적 로그인|2,774일", under("overview-account:loginTotal"))
        assertEquals("연속 로그인|1,672일", under("overview-account:loginSeq"))
        assertEquals("다음 누적 보상|2,800일 (26일 후)", under("overview-account:loginNext"))
    }

    @Test fun aMissingLoginTableOrAReachedMilestoneHidesItsCells() {
        show(1280, data = fixture.data().copy(login = DashboardLogin(total = 2800, seq = null, nextRewardDay = 2800)))
        compose.onNodeWithTag("overview-account:loginTotal").assertExists()
        compose.onNodeWithTag("overview-account:loginSeq").assertDoesNotExist()
        compose.onNodeWithTag("overview-account:loginNext").assertDoesNotExist()
    }

    @Test fun noLoginDataMeansNoLoginCells() {
        show(1280, data = fixture.data().copy(login = null))
        listOf("loginTotal", "loginSeq", "loginNext").forEach { compose.onNodeWithTag("overview-account:$it").assertDoesNotExist() }
    }

    // Formations

    @Test fun partiesListTheirServantsTheSupportSlotAndTheMysticCode() {
        show(360, section = DashboardBlockId.SupportFormation)
        assertEquals("파티 1 · 주력 파티", allTexts("dashboard-party-title")[0])
        assertEquals("파티 2", allTexts("dashboard-party-title")[1])
        assertEquals("파티 5 · 농장", allTexts("dashboard-party-title")[2])
        compose.onNodeWithTag("dashboard-party:3").assertDoesNotExist()
        val first = hasAnyAncestor(hasTestTag("dashboard-party:1"))
        assertEquals(5, compose.onAllNodes(hasTestTag("dashboard-party-row") and first).fetchSemanticsNodes().size)
        compose.onAllNodes(hasTestTag("dashboard-party-support") and first).assertCountEquals(1)
        compose.onAllNodes(hasText("서포트") and hasAnyAncestor(hasTestTag("dashboard-party-support"))).assertCountEquals(2)
        compose.onNode(hasText("샘플 서번트 10") and first).assertExists()
        compose.onNode(hasText("샘플 예장 A", substring = true) and first).assertExists()
        compose.onNode(hasText("마술예장: 샘플 전투복") and first).assertExists()
        compose.onAllNodes(hasTestTag("dashboard-party-mystic") and hasAnyAncestor(hasTestTag("dashboard-party:5"))).assertCountEquals(0)
    }

    @Test fun thePartyGroupLeadsTheSupportFormationAndTheSupportDecksShowTheirNames() {
        show(1280, section = DashboardBlockId.SupportFormation)
        assertTrue(bounds("dashboard-section:${DashboardBlockId.Parties}").bottom <= bounds("dashboard-section:${DashboardBlockId.SupportFormation}").top)
        compose.onNode(hasText("편성 1 · 샘플 서포트")).assertExists()
        compose.onNode(hasText("편성 2")).assertExists()
    }

    @Test fun noPartiesMeansNoPartyGroup() {
        show(360, data = fixture.data().copy(parties = emptyList()), section = DashboardBlockId.SupportFormation)
        compose.onNodeWithTag("dashboard-section:${DashboardBlockId.Parties}").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:${DashboardBlockId.SupportFormation}").assertExists()
    }

    // Servants

    @Test fun theWideTableHasTwoMoreColumnsWhenSomeServantHasCodesOrBoost() {
        show(1280, section = 3)
        waitForServants()
        compose.onNode(hasText("코드") and hasAnyAncestor(hasTestTag("dashboard-servant-header"))).assertExists()
        compose.onNode(hasText("카드 강화") and hasAnyAncestor(hasTestTag("dashboard-servant-header"))).assertExists()
        // Servant 10 has the highest bond rank and leads the list.
        assertEquals("2 / 5", allTexts("dashboard-servant-codes")[0])
        assertEquals("+1,000 (2)", allTexts("dashboard-servant-boost")[0])
        // A servant with a row but nothing enhanced shows its codes only.
        assertTrue(allTexts("dashboard-servant-codes").containsAll(listOf("5 / 5", "0 / 3")))
        assertTrue(allTexts("dashboard-servant-boost").contains("+500 (1)"))
        assertTrue(allTexts("dashboard-servant-codes").any { it.isEmpty() })
    }

    @Test fun withoutAnyCodesOrBoostTheTableKeepsItsColumns() {
        val plain = fixture.data().copy(servants = fixture.data().servants!!.map { it.copy(commandCodes = null, cardBoost = null) })
        show(1280, data = plain, section = 3)
        waitForServants()
        compose.onNode(hasText("코드") and hasAnyAncestor(hasTestTag("dashboard-servant-header"))).assertDoesNotExist()
        compose.onNodeWithTag("dashboard-servant-codes").assertDoesNotExist()
    }

    @Test fun narrowRowsGetAFourthLineWithTheirCodesAndBoostOnlyWhenTheyHaveAny() {
        show(360, section = 3)
        waitForServants()
        val extras = allTexts("dashboard-servant-extras")
        assertEquals(listOf("커맨드 코드 2 / 5 · 카드 강화 +1,000 (2장)", "커맨드 코드 5 / 5", "카드 강화 +500 (1장)", "커맨드 코드 0 / 3"), extras)
        assertTrue(allTexts("dashboard-servant-levels").none { it.contains("커맨드 코드") || it.contains("카드 강화") })
        // The fifth servant down the list has neither and keeps three lines.
        compose.onNodeWithTag("dashboard-servant-list").performScrollToIndex(5)
        compose.waitForIdle()
        assertTrue(allTexts("dashboard-servant-levels").isNotEmpty())
        assertTrue(allTexts("dashboard-servant-extras").isEmpty())
    }

    @Test fun aTableTooNarrowForTheColumnsPutsThemOnTheClassLine() {
        show(1000, section = 3)
        waitForServants()
        compose.onNode(hasText("코드") and hasAnyAncestor(hasTestTag("dashboard-servant-header"))).assertDoesNotExist()
        val lines = allTexts("dashboard-servant-subline")
        assertTrue(lines[0], lines[0].contains("커맨드 코드 2 / 5") && lines[0].contains("카드 강화 +1,000 (2장)"))
    }
}
