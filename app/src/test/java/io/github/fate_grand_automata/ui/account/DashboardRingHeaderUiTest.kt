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

    private fun assertOneLineWithoutEllipsis(name: String) {
        show(1280, named(name))
        val layout = layoutOf(hasTestTag("header-name"))
        assertEquals(name, 1, layout.lineCount)
        assertFalse(name, layout.hasVisualOverflow)
        assertEquals(name, name.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test fun aTwelveCharacterKoreanNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("가나다라마바사아자차카타")
    @Test fun aTwelveCharacterJapaneseNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("あいうえおかきくけこさし")
    @Test fun twelveWideLatinCharactersFitOneLineOnTheRail() = assertOneLineWithoutEllipsis("WWWWWWWWWWWW")

    private fun assertNoLevelOrServerInTheHeader(width: Int) {
        show(width, named("샘플 마스터"))
        compose.onNodeWithTag("header-level", useUnmergedTree = true).assertDoesNotExist()
        val inHeader = hasAnyAncestor(hasTestTag("dashboard-account-header"))
        compose.onAllNodes(hasText("Lv", substring = true) and inHeader, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasText("KR") and inHeader, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theWideHeaderHasNoLevelOrServer() = assertNoLevelOrServerInTheHeader(1280)
    @Test fun theNarrowHeaderHasNoLevelOrServer() = assertNoLevelOrServerInTheHeader(360)

    @Test fun theWideNameTakesTheFullLine() {
        show(1280, named("샘플 마스터"))
        val pane = box("dashboard-nav-pane"); val name = box("header-name")
        assertEquals("pane $pane name $name", (pane.right - pane.left).value - 48f, (name.right - name.left).value, 1f)
    }

    private fun assertAccountBlockHasLevelAndServer(width: Int) {
        show(width, fixture.data())
        compose.onNode(hasText("160") and hasAnyAncestor(hasTestTag("overview-account:level")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("레벨") and hasAnyAncestor(hasTestTag("overview-account:level")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("서버") and hasAnyAncestor(hasTestTag("overview-account:server")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("KR") and hasAnyAncestor(hasTestTag("overview-account:server")), useUnmergedTree = true).assertExists()
    }

    @Test fun theWideAccountBlockHasTheLevelAndTheServer() = assertAccountBlockHasLevelAndServer(1280)
    @Test fun theNarrowAccountBlockHasTheLevelAndTheServer() = assertAccountBlockHasLevelAndServer(360)

    @Test fun theLevelAndTheServerComeFirstInTheAccountBlock() {
        show(1280, fixture.data())
        val level = box("overview-account:level"); val server = box("overview-account:server"); val code = box("overview-account:friendCode")
        assertTrue("level $level server $server code $code", level.top <= code.top && server.top <= code.top && level.left < server.left)
    }

    @Test fun withoutALevelTheAccountBlockLeavesOutTheLevelField() {
        show(1280, fixture.data().copy(profile = fixture.data().profile - "lv"))
        compose.onNodeWithTag("overview-account:level", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("overview-account:server", useUnmergedTree = true).assertExists()
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
        val pane = box("dashboard-nav-pane"); val updated = box("dashboard-updated"); val ap = box("header-ap")
        assertTrue("ap $ap updated $updated", updated.top >= ap.bottom)
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
        assertTrue(texts("header-ap-detail"), Regex("""AP \d+ / 147 · \d{2}:\d{2} 가득 참""").matches(texts("header-ap-detail")))
    }

    private fun assertNoCarryOver(width: Int) {
        show(width, recovering(carry = 12))
        compose.onNodeWithTag("header-ap-carry", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(hasText("이월", substring = true) and hasAnyAncestor(hasTestTag("dashboard-account-header")), useUnmergedTree = true).assertCountEquals(0)
        assertFalse(texts("header-ap-detail"), texts("header-ap-detail").contains("이월"))
    }

    @Test fun theWideHeaderHasNoCarryOverEvenWhenTheValueIsPresent() = assertNoCarryOver(1280)
    @Test fun theNarrowHeaderHasNoCarryOverEvenWhenTheValueIsPresent() = assertNoCarryOver(360)

    @Test fun theWideNameIsCentredInThePane() {
        show(1280, named("샘플"))
        val pane = box("dashboard-nav-pane"); val name = box("header-name")
        val layout = layoutOf(hasTestTag("header-name"))
        val density = layout.layoutInput.density
        val left = name.left + with(density) { layout.getLineLeft(0).toDp() }
        val right = name.left + with(density) { layout.getLineRight(0).toDp() }
        assertEquals("pane $pane name $name", (pane.left + pane.right).value / 2, (left + right).value / 2, 2f)
    }

    @Test fun theUpdateTimeFollowsTheApRowWithoutALineBetween() {
        show(1280, recovering())
        val ap = box("header-ap"); val updated = box("dashboard-updated")
        assertTrue("ap $ap updated $updated", updated.top - ap.bottom < 12.dp)
    }

    @Test fun aLineSitsBetweenTheNameAndTheApRingOnWide() {
        show(1280, recovering())
        val name = box("header-name"); val line = box("header-divider"); val ap = box("header-ap")
        assertTrue("name $name line $line ap $ap", line.top >= name.bottom && line.bottom <= ap.top)
    }

    private fun assertNoRing(width: Int) {
        show(width, fixture.data().copy(game = null))
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-name", useUnmergedTree = true).assertExists()
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
