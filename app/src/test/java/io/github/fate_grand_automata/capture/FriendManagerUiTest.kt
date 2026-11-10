package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import io.github.fate_grand_automata.scripts.capture.FriendSlot
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.AgoUnit
import io.github.fate_grand_automata.ui.more.FriendManagerContent
import io.github.fate_grand_automata.ui.more.FriendManagerAccount
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.more.agoParts
import io.github.fate_grand_automata.ui.more.friendCodeText
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal const val FRIEND_NOW = 1_800_000_000L

/** Made-up friends, servants and CEs. */
internal fun syntheticRoster(): FriendRoster {
    fun slot(deck: Long, cls: Int, svt: Long, ce: Long, limit: Int, appends: List<Int?>) = FriendSlot(
        deck, cls, svt, lv = 90, np = 5, skills = listOf(10, 10, 9), appends = appends, ceId = ce, ceLimitCount = limit, ceLv = 100)
    val main = listOf(101L, 102L, 103L)
    val event = listOf(201L, 202L, 203L)
    return FriendRoster(FRIEND_NOW - 600, listOf(
        Friend("1", "샘플 친구 A", 120, "123,456,789", main, event, FRIEND_NOW - 300,
            listOf(slot(101, 5, 1, 90, 4, listOf(1, 2, 3, null, null)), slot(102, 1, 2, 91, 2, listOf(null, null, null, null, null)),
                slot(202, 5, 1, 90, 4, listOf(1, 1, 1, 1, 1)))),
        Friend("2", "샘플 친구 B", 80, "987654321", main, event, FRIEND_NOW - 3 * 86_400,
            listOf(slot(103, 8, 3, 0, 0, listOf(null, null, null, null, null)), slot(101, 3, 2, 90, 4, listOf(5, 5, 5, 5, 5)))),
        Friend("3", "샘플 친구 C", 40, null, main, event, FRIEND_NOW - 9 * 86_400,
            listOf(slot(102, 6, 1, 91, 4, listOf(1, null, 3, null, 5)))),
    ).associateBy { it.userId })
}

internal fun friendTestNames() = GameNameMap(
    servants = mapOf(1L to "샘플 캐스터", 2L to "샘플 세이버", 3L to "샘플 엑스트라",
        4L to "샘플 아주 길고 긴 이름을 가진 서번트 오르타 알터 오리지널"),
    servantData = mapOf(1L to AtlasBasic(1, className = "caster"), 2L to AtlasBasic(2, className = "saber"),
        3L to AtlasBasic(3, className = "ruler")),
    equips = mapOf(90L to "샘플 예장 하나", 91L to "샘플 예장 둘",
        92L to "샘플 아주 길고 긴 이름을 가진 예장 한정 한정 한정 한정 한정 한정",
        93L to "황금의잔", 94L to "칼데아의점심시간", 95L to "저편에서온달빛의축복과기원의"),
)

/** [longNameRoster] plus CEs with 4, 8 and 14 Korean characters (the last one without max limit break), all with 10/10/10 skills. */
internal fun ceLengthRoster(): FriendRoster {
    val base = longNameRoster()
    fun friend(id: String, ce: Long, limit: Int) = Friend(id, "친구 $id", 100, null, listOf(101L), listOf(201L), FRIEND_NOW - 5 * 86_400,
        listOf(FriendSlot(101, 1, 2, lv = 100, np = 1, skills = listOf(10, 10, 10), appends = listOf(10, 10, 10, 10, 10), ceId = ce, ceLimitCount = limit, ceLv = 100)))
    return base.copy(friends = base.friends + listOf(friend("5", 93, 4), friend("6", 94, 4), friend("7", 95, 0)).associateBy { it.userId })
}

/** [ceLengthRoster] plus a Grand Duel support with two CEs (a short name with max limit break, and a long one). */
internal fun duelRoster(): FriendRoster {
    val base = ceLengthRoster()
    val duel = Friend("8", "관위전 친구", 100, null, listOf(101L), listOf(201L), FRIEND_NOW - 4 * 3600, listOf(
        FriendSlot(101, 3, 2, lv = 100, np = 1, skills = listOf(10, 10, 10), appends = listOf(10, 10, 10, 10, 10),
            ceId = 90, ceLimitCount = 4, ceLv = 100, ce2Id = 92, ce2LimitCount = 2, ce2Lv = 60)))
    return base.copy(friends = base.friends + (duel.userId to duel))
}

/** The synthetic roster plus one friend whose servant, CE and name are far too long for their cells. */
internal fun longNameRoster(): FriendRoster {
    val base = syntheticRoster()
    val long = Friend("4", "샘플 아주 길고 긴 이름을 가진 친구 닉네임 테스트", 100, "555666777", listOf(101L), listOf(201L), FRIEND_NOW - 2 * 3600,
        listOf(FriendSlot(101, 5, 4, lv = 120, np = 5, skills = listOf(10, 10, 10), appends = listOf(1, 2, 3, 4, 5), ceId = 92, ceLimitCount = 4, ceLv = 100)))
    return base.copy(friends = base.friends + (long.userId to long))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2400dp-hdpi")
class FriendManagerUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    @Test fun accountPickerShowsKrAndJpTags() {
        val accounts = listOf(
            FriendManagerAccount(captureAccountFixture("7001", "KR sample"), syntheticRoster()),
            FriendManagerAccount(captureAccountFixture("7002", "JP sample").copy(server = "JP"), syntheticRoster()),
        )
        compose.setContent { FGATheme {
            FriendManagerContent(null, friendTestNames(), FRIEND_NOW, accounts, "7001")
        } }
        compose.onNodeWithText("KR").assertIsDisplayed()
        compose.onNodeWithTag("friends-account").performClick()
        compose.onNodeWithText("JP").assertIsDisplayed().performClick()
        compose.onNodeWithText("JP").assertIsDisplayed()
    }

    private fun show(roster: FriendRoster? = syntheticRoster()) =
        compose.setContent { FGATheme { FriendManagerContent(roster, friendTestNames(), FRIEND_NOW) } }

    /** The list is filtered off the main thread, so wait for it to settle on the expected count. */
    private fun rows(count: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().size == count }
        compose.onAllNodesWithTag("friends-row").assertCountEquals(count)
    }

    @Test fun grandBadgeStaysOnRegularAndEventRowsAndNoRemovedSwitchesExist() {
        val base = syntheticRoster()
        val roster = base.copy(friends = base.friends.mapValues { (_, friend) ->
            friend.copy(slots = friend.slots.map { it.copy(grand = true) })
        })
        show(roster); rows(5)
        compose.onAllNodesWithTag("friends-grand-badge", useUnmergedTree = true).assertCountEquals(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-grand-only").assertDoesNotExist()
        compose.onNodeWithTag("friends-max-lb").assertDoesNotExist()
        compose.onNodeWithTag("friends-deck-event").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(1)
        compose.onAllNodesWithTag("friends-grand-badge", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun krWithoutGrandStillHasNormalRowsAndAnEmptyGrandFormation() {
        show(); rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-deck-grand").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(0)
        compose.onNodeWithText("조건에 맞는 서포터가 없습니다").assertExists()
    }

    @Test fun emptyStateExplainsHowToFillTheList() {
        show(null)
        compose.onNodeWithTag("friends-empty").assertIsDisplayed()
        compose.onNodeWithText("친구&팔로우", substring = true).assertIsDisplayed()
    }

    @Test fun headerAndRowsShowTheRequestedLinesAndTapExpands() {
        show()
        rows(5)
        compose.onNodeWithText("친구 3명 · 갱신 10분 전 · 표시 5칸").assertDoesNotExist()
        compose.onAllNodesWithText("표시", substring = true).assertCountEquals(0)
        compose.onNodeWithText("이름").assertIsDisplayed()
        compose.onNodeWithText("스킬").assertIsDisplayed()
        compose.onNodeWithText("어펜드").assertIsDisplayed()
        compose.onNodeWithText("로그인").assertIsDisplayed()
        compose.onAllNodesWithText("5분", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("3일", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("9일", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Lv90 보5 · 샘플 친구 A", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("10/10/9").assertCountEquals(5)
        compose.onNodeWithText("1/2/3/-/-").assertExists()
        compose.onNodeWithText("예장 없음").assertExists()
        compose.onAllNodesWithText("최대").assertCountEquals(3)
        compose.onNodeWithText("친구 코드 123,456,789").assertDoesNotExist()
        compose.onAllNodesWithTag("friends-row")[0].performClick()
        compose.onNodeWithTag("friends-expanded").assertIsDisplayed()
        compose.onNodeWithText("친구 코드 123,456,789").assertIsDisplayed()
        compose.onNodeWithText("친구 Lv 120 · 캐스터 · 편성 1 · ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("friends-copy").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            assertEquals("123456789", context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onAllNodesWithText("샘플 캐스터", useUnmergedTree = true)[0].performClick()
        compose.onNodeWithTag("friends-expanded").assertDoesNotExist()
    }

    @Test fun filtersNarrowTheListAndDeckSwitchResetsServantAndCe() {
        show()
        rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-deck-event").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(1)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-deck-main").performClick()
        compose.onNodeWithTag("friends-limit-Max").performScrollTo().performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-search").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("세이버")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("(세이버) 샘플 세이버 (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("(세이버) 샘플 세이버 (2)").performClick()
        compose.onNodeWithTag("friends-ce").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 예장 하나 (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("샘플 예장 하나 (2)").performClick()
        compose.onNodeWithTag("friends-picker-done").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(1)
        // Removable chips clear only that filter.
        compose.onNode(hasText("샘플 세이버") and isSelected()).performClick()
        rows(2)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-deck-event").performClick()
        compose.onNodeWithText("모든 서번트").assertExists()
        compose.onNodeWithText("모든 예장").assertExists()
        compose.onNodeWithText("초기화").performClick()
        compose.onNodeWithTag("friends-apply").performClick()
        rows(5)
    }
    @Test fun relativeTimeAndCodeFormatting() {
        assertEquals(AgoUnit.Minutes to 1L, agoParts(5))
        assertEquals(AgoUnit.Minutes to 59L, agoParts(3599))
        assertEquals(AgoUnit.Hours to 3L, agoParts(3 * 3600 + 5))
        assertEquals(AgoUnit.Days to 2L, agoParts(2 * 86_400 + 5))
        assertEquals("123,456,789", friendCodeText("123456789"))
        assertEquals("12ab", friendCodeText("12ab"))
    }

    @Test fun filterChipOpensTheTopPanelAndHeaderHasNoFilterIcon() {
        show(); rows(5)
        compose.onNodeWithTag("friends-filter-panel").assertDoesNotExist()
        compose.onNodeWithTag("friends-filter").assertTextContains("필터").performClick()
        compose.onNodeWithTag("friends-filter-panel").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("필터").assertCountEquals(0)
    }

    @Test fun inactiveLoginFilterDefaultsToThreeDaysAndNarrowsTheList() {
        show(); rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        compose.onNodeWithTag("friends-login-amount").assertTextContains("3")
        compose.onNodeWithTag("friends-login-unit").assertTextContains("일")
        compose.onNodeWithTag("friends-apply").performClick()
        // Friends B (3 days, exactly on the boundary) and C (9 days); A (5 minutes) is hidden.
        rows(3)
        compose.onNodeWithText("3일 이상 미접속").assertExists()
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-unit").performScrollTo().performClick()
        compose.onNodeWithTag("friends-unit-Hours").performClick()
        repeat(4) { compose.onNodeWithTag("friends-login-plus").performClick() }
        compose.onNodeWithTag("friends-apply").performClick()
        rows(3)
        compose.onNodeWithText("7시간 이상 미접속").assertExists()
        compose.onNodeWithText("7시간 이상 미접속").performClick()
        rows(5)
    }

    @Test fun amountButtonsStopAtZeroAndNinetyNine() {
        show(); rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
        repeat(3) { compose.onNodeWithTag("friends-login-minus").performScrollTo().performClick() }
        compose.onNodeWithTag("friends-login-amount").assertTextEquals("0")
        compose.onNodeWithTag("friends-login-minus").assertIsNotEnabled()
        repeat(99) { compose.onNodeWithTag("friends-login-plus").performClick() }
        compose.onNodeWithTag("friends-login-amount").assertTextEquals("99")
        compose.onNodeWithTag("friends-login-plus").assertIsNotEnabled()
        compose.onNodeWithTag("friends-apply").performClick()
        compose.onNodeWithText("99일 이상 미접속").assertExists()
    }

    @Test fun everyRowCellIsAtMostTwoLinesAndCeTagSitsBesideTheName() {
        compose.setContent { FGATheme { FriendManagerContent(longNameRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(6)
        val rowNodes = compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes()
        assertEquals(6, rowNodes.size)
        compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().forEach { row ->
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    results.forEach { assertTrue("more than 2 lines", it.lineCount <= 2 || node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } == "friends-appends") }
                }
                node.children.forEach(::walk)
            }
            walk(row)
        }
        // The long CE name fills the cell; "최대" and "Lv100" follow it on the same line.
        val longCe = compose.onAllNodes(hasText("샘플 아주 길고 긴 이름을 가진 예장", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first()
        fun beside(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.positionInRoot.y >= longCe.positionInRoot.y - 8 && it.positionInRoot.y <= longCe.positionInRoot.y + longCe.size.height + 8 }
        val tag = beside("최대")
        val lv = beside("Lv100")
        assertTrue(tag.positionInRoot.x >= longCe.positionInRoot.x + longCe.size.width - 1)
        assertTrue(lv.positionInRoot.x >= tag.positionInRoot.x + tag.size.width - 1)
        // Skills and the 10/10/10 row are never cut.
        compose.onAllNodes(hasText("10/10/10"), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            assertTrue(!layout.first().hasVisualOverflow)
        }
    }

    @Test fun ceTagAndLevelStartRightAfterAShortName() {
        show(); rows(5)
        val density = compose.density.density
        val name = compose.onAllNodes(hasText("샘플 예장 하나"), useUnmergedTree = true).fetchSemanticsNodes().first()
        fun after(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
            .first { kotlin.math.abs(it.positionInRoot.y - name.positionInRoot.y) < 12 * density }
        val tag = after("최대")
        val lv = after("Lv100")
        val nameRight = name.positionInRoot.x + name.size.width
        assertTrue("tag starts ${tag.positionInRoot.x - nameRight}px after the name", tag.positionInRoot.x - nameRight in -1f..(8 * density))
        assertTrue(lv.positionInRoot.x >= tag.positionInRoot.x + tag.size.width - 1)
        assertTrue("level follows the tag", lv.positionInRoot.x - (tag.positionInRoot.x + tag.size.width) <= 8 * density)
    }

    @Test fun singleCeNameUsesTwoLinesAndShowsMoreThanOneCharacterAt360() {
        show(); rows(5)
        val node = compose.onAllNodes(hasText("샘플 예장 하나"), useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.first()
        assertTrue("at most two lines", layout.lineCount <= 2)
        val visible = layout.getLineEnd(0, visibleEnd = true)
        assertTrue("only $visible character(s) of the name visible", visible > 1 || !layout.hasVisualOverflow)
        assertTrue("the name uses a second line when needed", layout.lineCount == 2 || !layout.hasVisualOverflow)
    }

    @Test fun twoCesShowTwoLinesAndRowsStayAtMostTwoLines() {
        compose.setContent { FGATheme { FriendManagerContent(duelRoster(), friendTestNames(), FRIEND_NOW) } }
        rows(duelRoster().friends.values.sumOf { f -> f.slots.count { !f.isEvent(it) } })
        val lines = compose.onAllNodesWithTag("friends-ce-line", useUnmergedTree = true).fetchSemanticsNodes()
        // Only the Grand Duel slot has two CE lines inside one cell.
        val duelRow = compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().first { row ->
            lines.count { l -> l.positionInRoot.y >= row.positionInRoot.y && l.positionInRoot.y < row.positionInRoot.y + row.size.height } == 2
        }
        val inRow = lines.filter { it.positionInRoot.y >= duelRow.positionInRoot.y && it.positionInRoot.y < duelRow.positionInRoot.y + duelRow.size.height }
        assertEquals(2, inRow.size)
        assertTrue(inRow[1].positionInRoot.y > inRow[0].positionInRoot.y)
        assertEquals(inRow[0].positionInRoot.x, inRow[1].positionInRoot.x, 1f)
        compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().forEach { row ->
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    results.forEach { assertTrue("more than 2 lines", it.lineCount <= 2 || node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } == "friends-appends") }
                }
                node.children.forEach(::walk)
            }
            walk(row)
            // A row is as tall as its tallest cell: two CE lines of body text plus padding, never three.
            assertTrue(row.size.height <= 110 * compose.density.density)
        }
    }

    @Test fun accountRowShowsHoursInsteadOfManyMinutes() {
        val roster = syntheticRoster().copy(updatedAt = FRIEND_NOW - 205 * 60)
        fun account(id: String, name: String) = FriendManagerAccount(
            CaptureAccount(id, name, AccountSummary.from(dashboardState("userGame" to """[{"lv":160}]"""), FRIEND_NOW), 0), roster)
        compose.setContent { FGATheme { FriendManagerContent(null, friendTestNames(), FRIEND_NOW, listOf(account("one", "첫 계정")), "one") } }
        rows(5)
        compose.onNodeWithText("갱신 3시간 전", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("205분", substring = true).assertCountEquals(0)
    }

    @Test fun accountMenuSwitchesRosters() {
        val roster = syntheticRoster()
        val empty = FriendRoster(FRIEND_NOW, emptyMap())
        fun account(id: String, name: String, roster: FriendRoster) = FriendManagerAccount(
            CaptureAccount(id, name, AccountSummary.from(dashboardState("userGame" to """[{"lv":160}]"""), FRIEND_NOW), 0), roster)
        compose.setContent { FGATheme { FriendManagerContent(null, friendTestNames(), FRIEND_NOW,
            listOf(account("one", "첫 계정", roster), account("two", "둘째 계정", empty)), "one") } }
        rows(5)
        compose.onNodeWithTag("friends-account").performClick()
        compose.onNodeWithText("둘째 계정").performClick()
        compose.onNodeWithText("친구 0 · 갱신 1분 전", substring = true).assertIsDisplayed()
    }

    @Test fun list360Light() = compose.shoot("friend-manager", "FriendList", 360, 800, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
    }) {
        FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW)
    }

    private fun shootDuel(width: Int, height: Int) = compose.shoot("friend-manager", "FriendListRound4", width, height, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
    }) {
        FriendManagerContent(duelRoster(), friendTestNames(), FRIEND_NOW)
    }

    @Test fun round4List360Light() = shootDuel(360, 900)
    @Test fun round4List1280Light() = shootDuel(1280, 900)
}
