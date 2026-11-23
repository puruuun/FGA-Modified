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
    private val hasClockText = SemanticsMatcher("shows a HH:mm time") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("""\d{2}:\d{2}""").matches(it.text) }
    }

    private fun box(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun layoutOf(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun fullAp(max: Int) = game("""{"actMax":$max,"actRecoverAt":1,"carryOverActPoint":12}""")
    private fun paneContent() = box("dashboard-nav-pane").let { it.left + 24.dp to it.right - 24.dp }

    private fun assertOneLineWithoutEllipsis(name: String) {
        show(1280, named(name))
        val layout = layoutOf("header-name")
        assertEquals(name, 1, layout.lineCount)
        assertFalse(name, layout.hasVisualOverflow)
        assertEquals(name, name.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test fun aTwelveCharacterKoreanNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("가나다라마바사아자차카타")
    @Test fun aTwelveCharacterJapaneseNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("あいうえおかきくけこさし")
    @Test fun twelveWideLatinCharactersFitOneLineOnTheRail() = assertOneLineWithoutEllipsis("WWWWWWWWWWWW")

    @Test fun theNameTakesTheWholeFirstLineOnWide() {
        show(1280, named("샘플 마스터"))
        val name = box("header-name"); val (left, right) = paneContent()
        assertEquals(left.value, name.left.value, 0.5f)
        assertEquals(right.value, name.right.value, 0.5f)
    }

    @Test fun theUpdateTimeIsItsOwnStartAlignedLineBelowTheBar() {
        show(1280, recovering())
        val updated = box("dashboard-updated"); val bar = box("header-ap-bar"); val name = box("header-name")
        assertTrue("bar $bar updated $updated", updated.top >= bar.bottom)
        assertEquals(paneContent().first.value, updated.left.value, 0.5f)
        assertTrue("name $name updated $updated", updated.top >= name.bottom)
    }

    @Test fun theUpdateTimeIsBelowEverythingInTheHeader() {
        show(1280, recovering())
        val updated = box("dashboard-updated")
        listOf("header-name", "header-level", "header-divider", "header-ap", "header-ap-detail", "header-ap-bar").forEach {
            assertTrue("$it ${box(it)} updated $updated", updated.top >= box(it).bottom)
        }
    }

    @Test fun withoutApDataTheUpdateTimeFollowsTheLevelLine() {
        show(1280, fixture.data().copy(game = null))
        val level = box("header-level"); val updated = box("dashboard-updated")
        assertTrue("level $level updated $updated", updated.top >= level.bottom && updated.top - level.bottom < 16.dp)
        assertEquals(paneContent().first.value, updated.left.value, 0.5f)
    }

    @Test fun theLevelLineIsBelowTheNameOnWide() {
        show(1280, named("샘플 마스터"))
        val name = box("header-name"); val level = box("header-level")
        assertTrue("name $name level $level", level.top >= name.bottom)
        assertEquals(name.left.value, level.left.value, 0.5f)
        compose.onNode(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).assertExists()
    }

    @Test fun theWideHeaderHasABarAndNoRing() {
        show(1280, recovering())
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertExists()
    }

    @Test fun theBarIsOneElementWithTheApAndItsFraction() {
        show(1280, recovering())
        val current = currentAp().replace(",", "").toLong()
        val node = compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true)
        node.assertContentDescriptionEquals("AP ${currentAp()} / 147")
        val range = node.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(apFraction(current, 147), range.current, 0.001f)
        assertTrue(range.current > 0f && range.current < 1f)
    }

    @Test fun theBarIsFilledByTheFractionOfTheAp() {
        show(1280, recovering())
        val fraction = apFraction(currentAp().replace(",", "").toLong(), 147)
        val bitmap = compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val y = bitmap.height / 2
        val edge = (bitmap.width * fraction).toInt()
        val track = bitmap.getPixel(bitmap.width - 4, y)
        assertNotEquals(track, bitmap.getPixel(edge - 10, y))
        assertEquals(track, bitmap.getPixel(edge + 10, y))
    }

    @Test fun theBarIsTheOverviewsEightDpBarAcrossTheContentWidth() {
        show(1280, recovering())
        val bar = box("header-ap-bar"); val (left, right) = paneContent()
        assertEquals(8f, (bar.bottom - bar.top).value, 0.5f)
        assertEquals(left.value, bar.left.value, 0.5f)
        assertEquals(right.value, bar.right.value, 0.5f)
    }

    @Test fun theNumberAndTheMaxAreOneRowAboveTheBar() {
        show(1280, recovering())
        compose.onNodeWithTag("header-ap-max", useUnmergedTree = true).assertTextEquals("/ 147 AP")
        val current = box("header-ap-current"); val max = box("header-ap-max"); val bar = box("header-ap-bar")
        assertTrue("current $current max $max", max.left >= current.right && max.bottom <= current.bottom + 1.dp)
        assertTrue("current $current bar $bar", bar.top >= current.bottom)
    }

    @Test fun theFullTextIsBesideTheNumberWhenItFits() {
        show(1280, fullAp(147))
        val current = box("header-ap-current"); val max = box("header-ap-max"); val detail = box("header-ap-detail"); val bar = box("header-ap-bar")
        val centre = (detail.top + detail.bottom) / 2
        assertTrue("current $current detail $detail", centre >= current.top && centre <= current.bottom && detail.left >= max.right)
        assertEquals(paneContent().second.value, detail.right.value, 0.5f)
        assertTrue("detail $detail bar $bar", bar.top >= detail.bottom)
    }

    @Test fun theFullTextDropsBelowTheNumberEndAlignedWhenItDoesNotFit() {
        show(1280, fullAp(99999))
        val current = box("header-ap-current"); val detail = box("header-ap-detail"); val bar = box("header-ap-bar")
        assertTrue("current $current detail $detail", detail.top >= current.bottom)
        assertEquals(paneContent().second.value, detail.right.value, 0.5f)
        assertTrue("detail $detail bar $bar", bar.top >= detail.bottom)
    }

    @Test fun aRecoveringApShowsTheLabelAndTheClockTimeOnOneLineOnWide() {
        show(1280, recovering())
        val label = compose.onNode(hasText("가득 참") and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val time = compose.onNode(hasClockText and hasAnyAncestor(hasTestTag("header-ap-detail")), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("label $label time $time", (label.top + label.bottom).value, (time.top + time.bottom).value, 4f)
        assertTrue(time.left >= label.right)
    }

    private fun assertNoCarryOver(width: Int) {
        show(width, recovering(carry = 12))
        compose.onNodeWithTag("header-ap-carry", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodes(hasText("이월", substring = true) and hasAnyAncestor(hasTestTag("dashboard-account-header")), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theWideHeaderHasNoCarryOverEvenWhenTheValueIsPresent() = assertNoCarryOver(1280)
    @Test fun theNarrowHeaderHasNoCarryOverEvenWhenTheValueIsPresent() = assertNoCarryOver(360)

    @Test fun theAccountBlockHasNoLevelOrServerField() {
        show(1280, fixture.data())
        compose.onNodeWithTag("overview-account:level", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("overview-account:server", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("overview-account:friendCode", useUnmergedTree = true).assertExists()
    }

    @Test fun theNarrowHeaderKeepsTheRingTheLevelLineAndTheSummary() {
        show(360, recovering())
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("header-ap-max", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap-current", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).assertExists()
        assertTrue(texts("header-ap-detail"), Regex("""AP \d+ / 147 · \d{2}:\d{2} 가득 참""").matches(texts("header-ap-detail")))
    }

    @Test fun aLineSitsBetweenTheLevelAndTheApOnWide() {
        show(1280, recovering())
        val level = box("header-level"); val line = box("header-divider"); val ap = box("header-ap")
        assertTrue("level $level line $line ap $ap", line.top >= level.bottom && line.bottom <= ap.top)
    }

    private fun assertNoApParts(width: Int) {
        show(width, fixture.data().copy(game = null))
        compose.onNodeWithTag("header-ap-ring", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-ap", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("header-name", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("header-level", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("dashboard-updated", useUnmergedTree = true).assertExists()
    }

    @Test fun withoutApDataThereIsNoApPartOnWide() = assertNoApParts(1280)
    @Test fun withoutApDataThereIsNoApPartOnNarrow() = assertNoApParts(360)

    @Test fun aFullApFillsTheBarAndSaysOnlyFullOnWide() {
        show(1280, fullAp(147))
        compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).assertContentDescriptionEquals("AP 147 / 147")
        assertEquals(1f, compose.onNodeWithTag("header-ap-bar", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
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
