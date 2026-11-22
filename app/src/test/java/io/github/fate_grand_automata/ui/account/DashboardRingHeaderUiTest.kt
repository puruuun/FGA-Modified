package io.github.fate_grand_automata.ui.account

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

    private fun assertOneLineWithoutEllipsis(name: String) {
        show(1280, named(name))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("header-name", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(name, 1, layout.lineCount)
        assertFalse(name, layout.hasVisualOverflow)
        assertEquals(name, name.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test fun aTwelveCharacterKoreanNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("가나다라마바사아자차카타")
    @Test fun aTwelveCharacterJapaneseNameFitsOneLineOnTheRail() = assertOneLineWithoutEllipsis("あいうえおかきくけこさし")
    @Test fun twelveWideLatinCharactersFitOneLineOnTheRail() = assertOneLineWithoutEllipsis("WWWWWWWWWWWW")

    @Test fun theLevelIsBelowTheNameNotBesideIt() {
        show(1280, named("샘플 마스터"))
        val name = box("header-name"); val level = box("header-level")
        assertTrue("name $name level $level", level.top >= name.bottom)
        compose.onNode(hasText("Lv 160") and hasAnyAncestor(hasTestTag("header-level")), useUnmergedTree = true).assertExists()
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
