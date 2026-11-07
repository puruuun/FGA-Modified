package io.github.fate_grand_automata.capture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.scripts.capture.Friend
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import io.github.fate_grand_automata.scripts.capture.FriendSlot
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.more.AgoUnit
import io.github.fate_grand_automata.ui.more.FriendManagerContent
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
        Friend("1", "샘플 친구 A", 120, "123456789", main, event, FRIEND_NOW - 300,
            listOf(slot(101, 5, 1, 90, 4, listOf(1, 2, 3, null, null)), slot(102, 1, 2, 91, 2, listOf(null, null, null, null, null)),
                slot(202, 5, 1, 90, 4, listOf(1, 1, 1, 1, 1)))),
        Friend("2", "샘플 친구 B", 80, "987654321", main, event, FRIEND_NOW - 3 * 86_400,
            listOf(slot(103, 8, 3, 0, 0, listOf(null, null, null, null, null)), slot(101, 3, 2, 90, 4, listOf(5, 5, 5, 5, 5)))),
        Friend("3", "샘플 친구 C", 40, null, main, event, FRIEND_NOW - 9 * 86_400,
            listOf(slot(102, 6, 1, 91, 4, listOf(1, null, 3, null, 5)))),
    ).associateBy { it.userId })
}

private fun names() = GameNameMap(
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
        compose.setContent { FGATheme { FriendManagerContent(roster, names(), FRIEND_NOW) } }

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

    @Test fun headerAndRowsShowTheRequestedLines() {
        show()
        compose.onNodeWithText("친구 3명").assertIsDisplayed()
        compose.onNodeWithText("갱신: 10분 전").assertIsDisplayed()
        rows(5) // main deck only: A 2, B 2, C 1
        compose.onAllNodesWithText("로그인 5분 전").assertCountEquals(2)
        compose.onAllNodesWithText("로그인 3일 전").assertCountEquals(2)
        compose.onAllNodesWithText("친구 코드 123,456,789").assertCountEquals(2)
        compose.onAllNodesWithText("샘플 캐스터 · 캐스터 · 편성 1").assertCountEquals(1)
        compose.onNodeWithText("Lv 90 · 보구 5 · 스킬 10/10/9 · 어펜드 1 2 3 – –").assertExists()
        compose.onNodeWithText("예장 없음").assertExists()
        compose.onAllNodesWithText("최대 돌파").assertCountEquals(3)
    }

    @Test fun filtersNarrowTheList() {
        show()
        compose.onNodeWithTag("friends-deck-event").performClick()
        rows(1)
        compose.onNodeWithTag("friends-deck-main").performClick()
        rows(5)
        compose.onNodeWithTag("friends-max-lb").performClick()
        rows(3) // A deck 101, B deck 101, C
        compose.onNodeWithText("7일 이상 미접속").performClick()
        rows(1)
        compose.onNodeWithText("전체").performClick()
        compose.onNodeWithText("7일 이내").performClick()
        rows(2)
        compose.onNodeWithTag("friends-servant").performClick()
        compose.onNodeWithText("샘플 세이버 (2)").performClick()
        rows(1)
        compose.onNodeWithTag("friends-ce").performClick()
        compose.onNodeWithText("샘플 예장 하나 (2)").assertExists()
    }

    @Test fun relativeTimeAndCodeFormatting() {
        assertEquals(AgoUnit.Minutes to 1L, agoParts(5))
        assertEquals(AgoUnit.Minutes to 59L, agoParts(3599))
        assertEquals(AgoUnit.Hours to 3L, agoParts(3 * 3600 + 5))
        assertEquals(AgoUnit.Days to 2L, agoParts(2 * 86_400 + 5))
        assertEquals("123,456,789", friendCodeText("123456789"))
        assertEquals("12ab", friendCodeText("12ab"))
    }

    @Test fun list360Light() = compose.shoot("friend-manager", "FriendList", 360, 800, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
    }) {
        FriendManagerContent(syntheticRoster(), names(), FRIEND_NOW)
    }
}
