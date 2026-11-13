package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2400dp-hdpi")
class DashboardAccountCardsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun content(block: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons { block() } } }
    }

    @Test
    fun presentBoxShowsHeaderNoticeToggleAndNeverMessage() {
        val entries = (1..14).map { idx ->
            DashboardPresent(giftType = 1, objectId = 9000L + idx, count = (20 - idx).toLong())
        }
        val extraNames = fixture.names.copy(
            svtData = (1..14).associate { idx ->
                val id = 9000L + idx
                id to AtlasBasic(id, "Gift Entry $idx")
            },
        )
        val presentState = dashboardState(
            "userPresentBox" to buildList {
                entries.forEach { entry ->
                    add("""{"giftType":${entry.giftType},"objectId":${entry.objectId},"num":${entry.count},"message":"SECRET-GIFT-MESSAGE"}""")
                }
                add("""{"giftType":1,"objectId":9001,"num":1,"message":"SECRET-GIFT-MESSAGE"}""")
                add("""{"giftType":1,"objectId":9002,"num":1,"message":"SECRET-GIFT-MESSAGE"}""")
            }.joinToString(",", "[", "]"),
        )
        val data = dashboardData(presentState, extraNames)
        content { AccountDashboardContent(data, extraNames, "Alias", {}, {}) }

        compose.onNodeWithText("선물함 (16)").performScrollTo().assertExists()
        compose.onNodeWithText("마지막 로그인 이후 받은 선물은 다음 로그인 때 반영됩니다.").assertExists()
        compose.onNodeWithText("SECRET-GIFT-MESSAGE", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Gift Entry 12").assertExists()
        compose.onNodeWithText("Gift Entry 13").assertDoesNotExist()
        compose.onNodeWithText("Gift Entry 14").assertDoesNotExist()

        compose.onNodeWithText("전체 보기 (14)").performScrollTo().performClick()
        compose.onNodeWithTag("dashboard-present-grid").performScrollToIndex(13)
        compose.onNodeWithText("Gift Entry 14").assertExists()
        compose.onNodeWithText("접기").performScrollTo().performClick()
        compose.onNodeWithText("Gift Entry 14").assertDoesNotExist()
    }

    @Test
    fun presentBoxEmptyShowsZeroAndNoGiftsNotice() {
        val data = dashboardData(dashboardState("userPresentBox" to "[]"), fixture.names)
        content { AccountDashboardContent(data, fixture.names, "Alias", {}, {}) }

        compose.onNodeWithText("선물함 (0)").performScrollTo().assertExists()
        compose.onNodeWithText("마지막 로그인 이후 받은 선물은 다음 로그인 때 반영됩니다.").assertExists()
        compose.onNodeWithText("선물 없음").assertExists()
    }

    @Test
    fun dashboardOmitsCostumeAndPartyCardsEvenWhenStateHasDecksAndCostumeIds() {
        val names = fixture.names.copy(
            commandCodeData = mapOf(501L to AtlasBasic(501L, "하얀 그릇")),
            mysticData = mapOf(200L to AtlasBasic(200L, "아틀라스원")),
        )
        val state = dashboardState(
            "userSvtCollection" to """[
                {"svtId":1,"status":2,"friendshipRank":10,"friendship":5000,"maxLv":90,"costumeIds":[-11,11,12]}
            ]""",
            "userDeck" to """[
                {"deckNo":1,"name":"주회 파티","cost":96,"deckInfo":{
                    "userEquipId":501,
                    "svts":[
                        {"userSvtId":101,"userSvtEquipIds":[201],"isFollowerSvt":false,"initPos":1}
                    ]
                }}
            ]""",
            "userSvt" to """[
                {"id":101,"svtId":1,"lv":90,"limitCount":4},
                {"id":201,"svtId":9400010,"lv":100}
            ]""",
            "userEquip" to """[{"id":501,"equipId":200,"lv":10}]""",
            "userPresentBox" to """[{"giftType":2,"objectId":6510,"num":3}]""",
            "userCommandCodeCollection" to """[{"commandCodeId":501,"status":2}]""",
        )
        val data = dashboardData(state, names)
        content { AccountDashboardContent(data, names, "Alias", {}, {}) }

        compose.onNodeWithText("서번트 도감").performScrollTo().assertExists()
        compose.onNodeWithText("선물함 (1)").performScrollTo().assertExists()
        compose.onNodeWithText("커맨드 코드 도감").performScrollTo().assertExists()

        compose.onNodeWithText("영의", substring = true).assertDoesNotExist()
        compose.onNodeWithText("내 파티 편성", substring = true).assertDoesNotExist()
        compose.onNodeWithText("주회 파티", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:9").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:11").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-costumes-list").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-costumes-toggle").assertDoesNotExist()
        compose.onNodeWithTag("party-decks").assertDoesNotExist()
    }

    @Test
    fun commandCodesStartCollapsedAndExpandOnTap() {
        val namesWithList = fixture.names.copy(
            commandCodeData = mapOf(
                501L to AtlasBasic(501L, "하얀 그릇"),
                502L to AtlasBasic(502L, "푸른 환상"),
                503L to AtlasBasic(503L, "붉은 각인"),
            ),
        )
        val state = dashboardState(
            "userCommandCodeCollection" to """[
                {"commandCodeId":501,"status":2},
                {"commandCodeId":502,"status":1},
                {"commandCodeId":599,"status":2}
            ]""",
        )
        val withTotal = dashboardData(state, namesWithList)
        content { AccountDashboardContent(withTotal, namesWithList, "Alias", {}, {}) }

        compose.onNodeWithText("커맨드 코드 도감").performScrollTo().assertExists()
        compose.onNodeWithText("보유 2 / 전체 3").assertExists()
        compose.onNodeWithText("+ 보유 (2)").assertExists()
        compose.onNodeWithText("하얀 그릇").assertDoesNotExist()
        compose.onNodeWithText("#599").assertDoesNotExist()

        compose.onNodeWithText("+ 보유 (2)").performScrollTo().performClick()
        compose.onNodeWithText("− 보유 (2)").assertExists()
        compose.onNodeWithText("하얀 그릇").assertExists()
        compose.onNodeWithText("#599").assertExists()
    }

    @Test
    fun commandCodesWithoutAtlasListShowOwnedOnly() {
        val state = dashboardState(
            "userCommandCodeCollection" to """[
                {"commandCodeId":501,"status":2},
                {"commandCodeId":502,"status":1},
                {"commandCodeId":599,"status":2}
            ]""",
        )
        val ownedOnly = dashboardData(state, fixture.names)
        content { AccountDashboardContent(ownedOnly, fixture.names, "Alias", {}, {}) }
        compose.onNodeWithText("보유 2").performScrollTo().assertExists()
    }

    @Test
    fun allSectionsRenderInSpecOrderR5OnNarrowWidth() {
        val names = fixture.names.copy(
            commandCodeData = mapOf(501L to AtlasBasic(501L, "하얀 그릇")),
            mysticData = mapOf(200L to AtlasBasic(200L, "아틀라스원")),
        )
        val data = fixture.data.copy(
            presentBox = DashboardPresentBox(totalCount = 1, entries = listOf(DashboardPresent(2, 6510L, 1L))),
            formations = OwnFormations(
                main = listOf(OwnDeck(1L, 1, listOf(FriendSlot(deckId = 1L, classId = 0, svtId = 1L, lv = 90, np = 1)))),
                event = emptyList(),
                grand = listOf(FriendGrandSlot(seat = 1, svtId = 1L, lv = 90, np = 1)),
            ),
            equips = listOf(DashboardEquip(id = 200L, level = 10L, equipped = true)),
            commandCodes = DashboardCommandCodes(ownedIds = listOf(501L), total = 1),
            classBoards = listOf(DashboardClassBoard(1L, "세이버", null, acquired = 3, unlockedLocks = 1, squareTotal = 10, lockTotal = 2)),
            summons = DashboardSummons(
                friendPointTotal = 100L,
                otherSummons = listOf(DashboardSummon(10L, "픽업", "stone", null, null, 11L)),
                recentLog = listOf(DashboardSummonLog("2026-10-01", 10L, "픽업", 1)),
            ),
        )
        content {
            AccountDashboardContent(
                data = data,
                names = names,
                displayName = "Alias",
                openSettings = {},
                openLogs = {},
                battle = battleLogFixture("latest"),
            )
        }

        val expectedOrder = listOf(0, 1, 2, 3, 4, 10, 7, 8, 5, 12, 13, 14, 6)
        val tops = expectedOrder.map { section ->
            compose.onNodeWithTag("dashboard-section:$section").fetchSemanticsNode().positionInRoot.y
        }
        for (i in 0 until tops.lastIndex) {
            assertTrue(
                "Expected section ${expectedOrder[i]} (top=${tops[i]}) above ${expectedOrder[i + 1]} (top=${tops[i + 1]})",
                tops[i] < tops[i + 1],
            )
        }
    }

    @Test
    fun classScoreAndSummonHistoryHiddenWhenMissing() {
        val absent = fixture.data.copy(classBoards = null, summons = null)
        content { AccountDashboardContent(absent, fixture.names, "Alias", {}, {}) }
        compose.onNodeWithTag("dashboard-section:13").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-section:14").assertDoesNotExist()
    }

    @Test
    fun classScoreShowsProgressCompleteBadgeAndUnknownFallback() {
        val data = fixture.data.copy(
            classBoards = listOf(
                DashboardClassBoard(1L, "세이버 스코어", null, acquired = 10, unlockedLocks = 2, squareTotal = 10, lockTotal = 2),
                DashboardClassBoard(2L, "아처 스코어", null, acquired = 3, unlockedLocks = 1, squareTotal = 10, lockTotal = 2),
                DashboardClassBoard(99L, null, null, acquired = 4, unlockedLocks = 1, squareTotal = null, lockTotal = null),
            ),
        )
        content { AccountDashboardContent(data, fixture.names, "Alias", {}, {}) }
        compose.onNodeWithText("클래스 스코어").performScrollTo().assertExists()
        compose.onNodeWithTag("dashboard-class-score-notice").assertTextEquals("마지막 로그인 기준")
        compose.onNodeWithText("세이버 스코어").performScrollTo().assertExists()
        compose.onNodeWithText("칸 10 / 10 · 잠금 2 / 2").assertExists()
        compose.onNodeWithTag("class-board-complete:1").assertExists()
        compose.onNodeWithText("아처 스코어").performScrollTo().assertExists()
        compose.onNodeWithText("칸 3 / 10 · 잠금 1 / 2").assertExists()
        compose.onNodeWithTag("class-board-complete:2").assertDoesNotExist()
        compose.onNodeWithText("#99").performScrollTo().assertExists()
        compose.onNodeWithText("칸 4 · 잠금 1").assertExists()
    }

    @Test
    fun summonHistoryShowsFriendPointOtherShowAllAndCollapsedRecentLog() {
        val others = (1..12).map { index ->
            DashboardSummon(
                id = 100L + index,
                name = if (index == 11) null else "픽업 $index",
                type = "stone",
                openedAt = null,
                closedAt = null,
                count = (20 - index).toLong(),
            )
        }
        val data = fixture.data.copy(
            summons = DashboardSummons(
                friendPointTotal = 1234L,
                otherSummons = others,
                recentLog = listOf(
                    DashboardSummonLog("2026-10-02", 101L, "픽업 1", 11),
                    DashboardSummonLog("2026-10-01", 999L, null, 1),
                ),
            ),
        )
        content { AccountDashboardContent(data, fixture.names, "Alias", {}, {}) }

        compose.onNodeWithText("소환 기록").performScrollTo().assertExists()
        compose.onNodeWithTag("dashboard-summons-notice").assertTextEquals("마지막 로그인 기준")
        compose.onNodeWithText("친구 포인트 소환 1,234회").assertExists()
        compose.onNodeWithText("횟수는 뽑은 장수이며 10연차 1번은 11로 셉니다.").assertExists()
        compose.onNodeWithTag("dashboard-summons-others-toggle").assertTextEquals("전체 보기 (12)")
        compose.onNodeWithText("픽업 12").assertDoesNotExist()

        compose.onNodeWithTag("dashboard-summons-others-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("dashboard-summons-others-toggle").assertTextEquals("접기")
        compose.onNodeWithTag("dashboard-summons-others").performScrollToIndex(11)
        compose.onNodeWithText("#111").assertExists()
        compose.onNodeWithText("픽업 12").assertExists()

        // Recent log starts collapsed and expands on tap.
        compose.onNodeWithTag("dashboard-summons-log-toggle").assertTextEquals("+ 최근 소환 내역 (2)")
        compose.onNodeWithTag("dashboard-summons-log").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-summons-log-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("dashboard-summons-log-toggle").assertTextEquals("− 최근 소환 내역 (2)")
        compose.onNodeWithTag("dashboard-summons-log").assertExists()
        compose.onNodeWithText("2026-10-02").assertExists()
        compose.onNodeWithText("#999").assertExists()
        compose.onNodeWithText("×11").assertExists()
    }
}
