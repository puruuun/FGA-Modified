package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.Friend
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
    servants = mapOf(1L to "샘플 캐스터", 2L to "샘플 세이버", 3L to "샘플 엑스트라"),
    equips = mapOf(90L to "샘플 예장 하나", 91L to "샘플 예장 둘"),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2400dp-hdpi")
class FriendManagerUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun show(roster: FriendRoster? = syntheticRoster()) =
        compose.setContent { FGATheme { FriendManagerContent(roster, friendTestNames(), FRIEND_NOW) } }

    /** The list is filtered off the main thread, so wait for it to settle on the expected count. */
    private fun rows(count: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().size == count }
        compose.onAllNodesWithTag("friends-row").assertCountEquals(count)
    }

    @Test fun emptyStateExplainsHowToFillTheList() {
        show(null)
        compose.onNodeWithTag("friends-empty").assertIsDisplayed()
        compose.onNodeWithText("친구&팔로우", substring = true).assertIsDisplayed()
    }

    @Test fun headerAndRowsShowTheRequestedLinesAndTapExpands() {
        show()
        rows(5)
        compose.onNodeWithText("친구 3명 · 갱신 10분 전 · 표시 5칸").assertIsDisplayed()
        compose.onNodeWithText("이름").assertIsDisplayed()
        compose.onNodeWithText("스킬").assertIsDisplayed()
        compose.onAllNodesWithText("10/10/9").assertCountEquals(5)
        compose.onNodeWithText("1/2/3\n–/–").assertExists()
        compose.onNodeWithText("예장 없음").assertExists()
        compose.onAllNodesWithText("최대").assertCountEquals(3)
        compose.onNodeWithText("친구 코드 123,456,789").assertDoesNotExist()
        compose.onAllNodesWithTag("friends-row")[0].performClick()
        compose.onNodeWithTag("friends-expanded").assertIsDisplayed()
        compose.onNodeWithText("친구 코드 123,456,789").assertIsDisplayed()
        compose.onNodeWithTag("friends-copy").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            assertEquals("123456789", context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onAllNodesWithTag("friends-row")[0].performClick()
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
        compose.onNodeWithTag("friends-max-lb").performClick()
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithTag("friends-search").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("세이버")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 세이버 (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("샘플 세이버 (2)").performClick()
        compose.onNodeWithTag("friends-ce").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 예장 하나 (2)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("샘플 예장 하나 (2)").performClick()
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

    @Test fun loginDateUsesTheMaterialCalendar() {
        show(); rows(5)
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithText("이 날짜 이전").performScrollTo().performClick()
        compose.onNodeWithTag("friends-date").performScrollTo().performClick()
        compose.onNodeWithTag("friends-date-picker").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("friends-filter-sheet").assertIsDisplayed()
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
        compose.onNodeWithText("친구 0명 · 갱신 1분 전 · 표시 0칸").assertIsDisplayed()
    }

    @Test fun list360Light() = compose.shoot("friend-manager", "FriendList", 360, 800, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
    }) {
        FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW)
    }
}
