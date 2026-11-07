package io.github.fate_grand_automata.events

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.scripts.events.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.main.MainScreenDestinations
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal const val EVENT_NOW = 1_800_000_000L
internal fun syntheticEvents() = EventData(
    events = listOf(GameEvent(1, "eventQuest", "샘플 이벤트 · 별빛 축제", EVENT_NOW - 86_400, EVENT_NOW + 3 * 86_400 + 3600),
        GameEvent(2, "questCampaign", "샘플 소비 AP 0 캠페인", EVENT_NOW - 100, EVENT_NOW + 3600),
        GameEvent(3, "combineCampaign", "샘플 강화 성공률 UP", EVENT_NOW - 100, EVENT_NOW + 7200),
        GameEvent(4, "questCampaign", "샘플 인연 포인트 UP", EVENT_NOW - 100, EVENT_NOW + 10_800)),
    bonuses = mapOf(1L to listOf(BonusEquip(10, setOf(BonusKind.Drop, BonusKind.Point)),
        BonusEquip(20, setOf(BonusKind.Bond, BonusKind.Encounter, BonusKind.Other)))),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h1000dp-hdpi")
class EventCardUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)

    private fun show(data: EventData) = compose.setContent { FGATheme { EventCard(data, EVENT_NOW) {} } }

    @Test fun nothingFetchedHidesCard() {
        show(EventData())
        compose.onNodeWithTag("events-card").assertDoesNotExist()
    }

    @Test fun nothingRunningHidesCard() {
        show(syntheticEvents().copy(events = listOf(GameEvent(1, "eventQuest", "Ended", 1, 2))))
        compose.onNodeWithTag("events-card").assertDoesNotExist()
    }

    @Test fun campaignsAloneShowCardAndExpandInEndOrder() {
        show(syntheticEvents().let { it.copy(events = it.events!!.drop(1)) })
        compose.onNodeWithText("진행 중인 이벤트 (KR)").assertIsDisplayed()
        compose.onNodeWithText("캠페인 3개 · 샘플 소비 AP 0 캠페인 외").assertIsDisplayed()
        compose.onNodeWithText("샘플 강화 성공률 UP").assertDoesNotExist()
        compose.onNodeWithTag("events-campaigns").performClick()
        val first = compose.onNodeWithText("샘플 소비 AP 0 캠페인", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("샘플 강화 성공률 UP", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithText("샘플 인연 포인트 UP", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < second.top && second.top < third.top)
        compose.onAllNodesWithText("종료", substring = true, useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithTag("events-campaigns").performClick()
        compose.onNodeWithText("샘플 강화 성공률 UP").assertDoesNotExist()
    }

    @Test fun singleCampaignUsesOnlyItsName() {
        show(syntheticEvents().let { it.copy(events = it.events!!.filter { row -> row.id == 2L }) })
        compose.onNodeWithText("샘플 소비 AP 0 캠페인").assertIsDisplayed()
        compose.onNodeWithText("캠페인 1개", substring = true).assertDoesNotExist()
    }

    @Test fun eventShowsRemainingTimeCountAndEveryPresentKind() {
        show(syntheticEvents())
        compose.onNodeWithText("3일 남음").assertIsDisplayed()
        compose.onNodeWithText("보너스 예장 2종").assertIsDisplayed()
        listOf("드롭 UP", "포인트 UP", "인연 UP", "출현율 UP", "기타").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun oneHourMinimumAndEndTimeUseLocalizedTextAndDeviceTimeZone() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+09:00"))
            val data = EventData(events = listOf(GameEvent(1, "eventQuest", "샘플 이벤트", EVENT_NOW - 100, EVENT_NOW + 30)))
            show(data)
            compose.onNodeWithText("1시간 남음").assertIsDisplayed()
            val expected = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREAN)
                .format(java.util.Date((EVENT_NOW + 30) * 1000)) + " 종료"
            compose.onNodeWithText(expected).assertIsDisplayed()
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    @Test fun unloadedBonusMapHidesCountAndTags() {
        show(syntheticEvents().copy(bonuses = emptyMap()))
        compose.onNodeWithText("보너스 예장", substring = true).assertDoesNotExist()
        compose.onNodeWithText("드롭 UP").assertDoesNotExist()
        compose.onNodeWithTag("event-1").assertIsDisplayed()
    }

    @Test fun eventWithoutBonusCesStillShowsButHidesBonusLine() {
        show(syntheticEvents().copy(bonuses = mapOf(1L to emptyList())))
        compose.onNodeWithTag("event-1").assertIsDisplayed()
        compose.onNodeWithText("보너스 예장", substring = true).assertDoesNotExist()
    }

    @Test fun mainScreenWithoutCaptureShowsCardAndNavigatesOnEventTap() {
        var destination: MainScreenDestinations? = null
        compose.setContent { FGATheme { Surface {
            MainScreenContent(navigate = { destination = it }, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = false, toggleAccessibilityService = {}, events = syntheticEvents(), eventNow = EVENT_NOW)
        } } }
        compose.onNodeWithTag("event-1").performScrollTo().performClick()
        assertEquals(MainScreenDestinations.EventBonus(1), destination)
    }

    @Test fun bonusScreenSortsByResolvedNameAndFallsBackToId() {
        val equips = listOf(BonusEquip(20, setOf(BonusKind.Point)), BonusEquip(10, setOf(BonusKind.Drop)), BonusEquip(30, setOf(BonusKind.Other)))
        compose.setContent { FGATheme { EventBonusContent("샘플 이벤트", equips, GameNameMap(equips = mapOf(20L to "가 예장", 10L to "나 예장"))) } }
        compose.onNodeWithText("이벤트 보너스 예장").assertIsDisplayed()
        compose.onNodeWithText("샘플 이벤트").assertIsDisplayed()
        compose.onAllNodesWithTag("event-bonus-row").assertCountEquals(3)
        val unknown = compose.onNodeWithText("#30").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("가 예장").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("나 예장").fetchSemanticsNode().boundsInRoot
        assertTrue(unknown.top < first.top && first.top < second.top)
    }
}
