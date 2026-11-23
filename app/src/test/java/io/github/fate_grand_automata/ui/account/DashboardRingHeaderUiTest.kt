package io.github.fate_grand_automata.ui.account

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The ring AP header, the one bar style and the account block of the Overview (#625). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32])
class DashboardRingHeaderUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { Round615Fixture() }

    private fun game(json: String) = fixture.data().copy(game = Json.parseToJsonElement(json).jsonObject)
    private fun recovering(carry: Int = 12) =
        game("""{"actMax":147,"actRecoverAt":${System.currentTimeMillis() / 1000 + 3 * 3600},"carryOverActPoint":$carry}""")

    private fun show(width: Int, data: DashboardData, displayName: String? = null, section: Int = 0) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-hdpi")
        compose.setContent { FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.case.fixture.Icons {
            AccountDashboardContent(data, fixture.names, displayName, {}, {}, battle = battleLogFixture("recent"), initialSection = section,
                userId = "ring", friendCount = 24, progress = fixture.progress)
        } } } }
        compose.waitForIdle()
    }

    private fun named(name: String) = fixture.data().copy(profile = fixture.data().profile + ("name" to name))
    private fun box(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private val hasClockText = SemanticsMatcher("shows a HH:mm time") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("""\d{2}:\d{2}""").matches(it.text) }
    }

    private fun layoutOf(matcher: SemanticsMatcher): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    // The rail is 240dp with 24dp padding, so the level and the server chip leave about 100dp for the name: twelve wide
    // characters end in an ellipsis there, and what must hold is one line with the level intact beside it.
    private fun assertNameOneLineAndLevelWhole(name: String) {
        show(1280, named(name))
        assertEquals(name, 1, layoutOf(hasTestTag("header-name")).lineCount)
        val level = layoutOf(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")))
        assertEquals(name, 1, level.lineCount)
        val levelText = compose.onNode(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("$name $levelText ${level.size}", !level.isLineEllipsized(0) &&
            levelText.right - levelText.left >= with(level.layoutInput.density) { (level.getLineRight(0) - level.getLineLeft(0)).toDp() } - 0.5.dp)
        val pane = box("dashboard-nav-pane"); val nameBox = box("header-name"); val levelBox = box("header-level")
        assertTrue("name $nameBox level $levelBox pane $pane", nameBox.right <= levelBox.left && levelBox.right <= pane.right)
        compose.onNode(hasText("KR") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).assertExists()
    }

    @Test fun aTwelveCharacterKoreanNameStaysOnOneLineWithTheLevelWhole() = assertNameOneLineAndLevelWhole("가나다라마바사아자차카타")
    @Test fun aTwelveCharacterJapaneseNameStaysOnOneLineWithTheLevelWhole() = assertNameOneLineAndLevelWhole("あいうえおかきくけこさし")
    @Test fun twelveWideLatinCharactersStayOnOneLineWithTheLevelWhole() = assertNameOneLineAndLevelWhole("WWWWWWWWWWWW")

    @Test fun theLevelAndTheServerSitOnTheNamesLine() {
        show(1280, named("샘플 마스터"))
        val name = box("header-name"); val level = box("header-level")
        val centre = (level.top + level.bottom) / 2
        assertTrue("name $name level $level", centre >= name.top && centre <= name.bottom && level.left >= name.right)
        compose.onNode(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).assertExists()
    }

    @Test fun theRingIsCentredInThePaneAtOneHundredTwelveDp() {
        show(1280, recovering())
        val pane = box("dashboard-nav-pane"); val ring = box("header-ap-ring")
        assertEquals(112f, (ring.right - ring.left).value, 0.5f)
        assertEquals("pane $pane ring $ring", (pane.left + pane.right).value / 2, (ring.left + ring.right).value / 2, 2f)
    }

    @Test fun theFullTextIsOneCentredLineBelowTheRing() {
        show(1280, recovering(carry = 12))
        val pane = box("dashboard-nav-pane"); val ring = box("header-ap-ring")
        val label = compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val time = compose.onNode(hasClockText and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("ring $ring label $label", label.top >= ring.bottom)
        assertEquals("label $label time $time", (label.top + label.bottom).value, (time.top + time.bottom).value, 4f)
        assertEquals((pane.left + pane.right).value / 2, (label.left + time.right).value / 2, 2f)
        val carry = box("header-ap-carry")
        assertTrue("carry $carry label $label", carry.top >= label.bottom)
        assertEquals((pane.left + pane.right).value / 2, (carry.left + carry.right).value / 2, 2f)
    }

    @Test fun aFullApSaysOnlyFullCentredBelowTheRing() {
        show(1280, game("""{"actMax":147,"actRecoverAt":1,"carryOverActPoint":0}"""))
        val pane = box("dashboard-nav-pane"); val ring = box("header-ap-ring")
        val label = compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(label.top >= ring.bottom)
        assertEquals((pane.left + pane.right).value / 2, (label.left + label.right).value / 2, 2f)
    }

    @Test fun theUpdateTimeIsCentredBelowEverything() {
        show(1280, recovering(carry = 12))
        val pane = box("dashboard-nav-pane"); val updated = box("dashboard-updated"); val carry = box("header-ap-carry")
        assertTrue("carry $carry updated $updated", updated.top >= carry.bottom)
        assertEquals((pane.left + pane.right).value / 2, (updated.left + updated.right).value / 2, 2f)
    }

    @Test fun theMaxIsInsideTheRingOnWideAndNotOnNarrow() {
        show(1280, recovering())
        compose.onNode(hasText("/ 147") and hasAnyAncestor(hasTestTag("header-ap-ring")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertContentDescriptionEquals("AP ${currentAp()} / 147")
    }

    @Test fun theMaxIsNotInsideTheNarrowRing() {
        show(360, recovering())
        compose.onNodeWithTag("header-ap-max", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap-current", useUnmergedTree = true).assertExists()
        assertTrue(texts("header-ap-detail"), Regex("""AP \d+ / 147 · \d{2}:\d{2} 가득 참 · 이월 AP 12""").matches(texts("header-ap-detail")))
    }

    @Test fun theCarryOverLineIsHiddenAtZeroOnWideAndNarrow() {
        show(1280, recovering(carry = 0))
        compose.onNodeWithTag("header-ap-carry", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theNarrowSummaryHasNoCarryOverPartAtZero() {
        show(360, recovering(carry = 0))
        assertFalse(texts("header-ap-detail"), texts("header-ap-detail").contains("이월"))
    }

    @Test fun theUpdateTimeFollowsTheApRowWithoutALineBetween() {
        show(1280, recovering())
        val ap = box("header-ap"); val updated = box("dashboard-updated")
        assertTrue("ap $ap updated $updated", updated.top - ap.bottom < 12.dp)
    }

    @Test fun aLineSitsBetweenTheLevelAndTheApRingOnWide() {
        show(1280, recovering())
        val level = box("header-level"); val line = box("header-divider"); val ap = box("header-ap")
        assertTrue("level $level line $line ap $ap", line.top >= level.bottom && line.bottom <= ap.top)
    }

    @Test fun theCarryOverLineShowsOnWideWhenAboveZero() {
        show(1280, recovering(carry = 12))
        compose.onNodeWithTag("header-ap-carry", useUnmergedTree = true).assertTextEquals("이월 AP 12")
    }

    private fun assertNoRing(width: Int) {
        show(width, fixture.data().copy(game = null))
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-name", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("header-level", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dashboard-updated", useUnmergedTree = true).assertExists()
    }

    @Test fun withoutApDataThereIsNoRingOnWide() = assertNoRing(1280)
    @Test fun withoutApDataThereIsNoRingOnNarrow() = assertNoRing(360)

    @Test fun aFullApRingIsClosedAndSaysOnlyFullOnWide() {
        show(1280, game("""{"actMax":147,"actRecoverAt":1,"carryOverActPoint":0}"""))
        assertEquals(1f, apFraction(147, 147), 0f)
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertContentDescriptionEquals("AP 147 / 147")
        compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("header-ap-detail", useUnmergedTree = true).onChildren().assertCountEquals(1)
    }

    @Test fun theRingFractionIsClampedAndAZeroMaxIsEmpty() {
        assertEquals(0f, apFraction(5, 0), 0f)
        assertEquals(1f, apFraction(200, 147), 0f)
        assertEquals(0f, apFraction(-3, 147), 0f)
        assertEquals(0.5f, apFraction(50, 100), 0f)
    }

    @Test fun everyOverviewBarIsTheEightDpSegmentedBar() {
        show(1280, fixture.data())
        listOf("overview-progress-bar:80500", "overview-progress-bar:80501", "overview-rarity-bar:5", "overview-storage-bar").forEach {
            val b = compose.onNodeWithTag(it).performScrollTo().getUnclippedBoundsInRoot()
            assertEquals(it, 8f, (b.bottom - b.top).value, 0.5f)
        }
    }

    @Test fun theRarityBarHasNoGapBetweenItsFilledAndEmptyParts() {
        show(1280, fixture.data())
        val bar = compose.onNodeWithTag("overview-rarity-bar:5").performScrollTo().captureToImage().asAndroidBitmap()
        val y = bar.height / 2
        val track = bar.getPixel(bar.width - 8, y)
        // M3 draws a gap of its own between the parts; a segmented bar has the fill run straight into the track.
        val changes = (8 until bar.width - 8).count { bar.getPixel(it, y) != bar.getPixel(it - 1, y) }
        assertEquals(1, changes)
        assertNotEquals(track, bar.getPixel(8, y))
    }

    @Test fun theWideOverviewPutsTheAccountBlockInTheRightColumnAfterTheRecentRecords() {
        show(1280, fixture.data())
        val account = compose.onNodeWithTag("overview-account").performScrollTo().getUnclippedBoundsInRoot()
        val recent = compose.onNodeWithTag("overview-recent").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("dashboard-page").getUnclippedBoundsInRoot()
        assertTrue("account $account page $page", account.left.value > (page.left.value + page.right.value) / 2)
        assertTrue(account.top >= recent.bottom)
    }

    @Test fun theNarrowOverviewKeepsTheAccountBlockLastInOneColumn() {
        show(360, fixture.data())
        val account = compose.onNodeWithTag("overview-account").performScrollTo().getUnclippedBoundsInRoot()
        val storage = compose.onNodeWithTag("overview-storage").getUnclippedBoundsInRoot()
        val recent = compose.onNodeWithTag("overview-recent").getUnclippedBoundsInRoot()
        assertEquals(storage.left, account.left)
        assertTrue(account.top >= recent.bottom)
    }

    private fun currentAp(): String {
        val node = compose.onNodeWithTag("header-ap-current", useUnmergedTree = true).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
    }

    private fun texts(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
}
