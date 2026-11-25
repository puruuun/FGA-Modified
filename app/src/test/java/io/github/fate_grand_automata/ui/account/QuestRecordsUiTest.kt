package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The hand-made `nice_war` fixture and account table of [QuestRecordsTest], projected as the loader would. */
internal fun questRecordsFixture(names: Boolean = true): List<QuestRecord> {
    val wars = if (names) GameNames.summarizeWars(NICE_WAR_FIXTURE) else emptyList()
    val table = listOf(
        """{"questId":1000000,"questPhase":1,"clearNum":1,"challengeNum":1,"lastStartedAt":1700000000}""",
        """{"questId":93000001,"questPhase":3,"clearNum":687,"challengeNum":690,"lastStartedAt":1763600000}""",
        """{"questId":94002501,"questPhase":5,"clearNum":1,"challengeNum":1,"lastStartedAt":1700000000}""",
        """{"questId":94006801,"questPhase":1,"clearNum":30,"challengeNum":30,"lastStartedAt":1763000000}""",
        """{"questId":91000101,"questPhase":2,"clearNum":1,"challengeNum":2,"lastStartedAt":1700000000}""",
        """{"questId":94010101,"questPhase":1,"clearNum":1204,"challengeNum":1204,"lastStartedAt":1763700000}""",
        """{"questId":94010102,"questPhase":1,"clearNum":1,"challengeNum":1,"lastStartedAt":1700000000}""",
        """{"questId":94010103,"questPhase":1,"clearNum":55,"challengeNum":55,"lastStartedAt":1762000000}""",
        """{"questId":94061234,"questPhase":1,"clearNum":4,"challengeNum":4,"lastStartedAt":1761000000}""",
    ).joinToString(",", "[", "]")
    return questRecords(readUserQuest(table), wars)
}

internal fun manyQuestRecords(count: Int): List<QuestRecord> =
    questRecords(readUserQuest((1..count).joinToString(",", "[", "]") {
        """{"questId":${94_100_000 + it},"questPhase":1,"clearNum":${count - it + 1},"challengeNum":${count - it + 1},"lastStartedAt":1763000000}"""
    }), emptyList())

internal fun questSource(state: QuestRecordsState, retry: () -> Unit = {}) = QuestRecordsSource({ flowOf(state) }, retry)
internal fun questSource(flow: Flow<QuestRecordsState>) = QuestRecordsSource({ flow }, {})

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h2400dp-hdpi")
class QuestRecordsUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun show(source: QuestRecordsSource?, hasQuests: Boolean = true) {
        compose.setContent {
            FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data.copy(hasQuestRecords = hasQuests), fixture.names, "Alias", {}, {},
                    initialSection = DashboardBlockId.QuestRecords, questRecords = source)
            } }
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theBlockSitsInTheRenamedTabWithTheSummaryAndRows() {
        show(questSource(QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready)))
        compose.onNodeWithTag("dashboard-nav:Collection").assertTextContains("도감·기록")
        waitFor("quest:93000001")
        compose.onNodeWithTag("dashboard-section:${DashboardBlockId.QuestRecords}").assertExists()
        compose.onNodeWithText("퀘스트 기록").assertExists()
        // Story quests (1000000, 94010102) count under 전체.
        compose.onNodeWithTag("quest-records-summary").assertTextEquals("클리어 1,984회 · 클리어한 퀘스트 9개")
        compose.onNodeWithTag("quest-clears:93000001").assertTextEquals("687회")
        compose.onNodeWithTag("quest-challenges:93000001").assertTextEquals("도전 690")
        compose.onNodeWithTag("quest-challenges:94010101").assertDoesNotExist()
        compose.onAllNodesWithText("90++").assertCountEquals(0)
        compose.onNodeWithText("교회 터").assertExists()
        compose.onNodeWithText("특이점F 염상오염도시 후유키 · 교회 터").assertExists()
        assertEquals(3, compose.onAllNodesWithText("서유기 이벤트 · 오행산").fetchSemanticsNodes().size)
        compose.onNodeWithText("#94061234").assertExists()
        compose.onNodeWithText("2025-11-20").assertExists()
    }

    @Test fun storyQuestsAreListedUnderAllAndTheRemovedChipsAreGone() {
        show(questSource(QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("quest:1000000")
        compose.onNodeWithTag("quest:94010102").assertExists()
        for (chip in listOf("All", "Free", "Event", "Daily", "Raid")) compose.onNodeWithTag("quest-filter:$chip").assertExists()
        for (chip in listOf("HighLevel", "InterludeRankUp", "Story")) compose.onNodeWithTag("quest-filter:$chip").assertDoesNotExist()
    }

    @Test fun theSearchFieldOpensFromTheHeadingIconAndClosingClearsIt() {
        show(questSource(QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("quest:93000001")
        compose.onNodeWithTag("quest-search").assertDoesNotExist()
        compose.onNodeWithContentDescription("검색").assertExists()
        compose.onNodeWithTag("quest-search-open").performScrollTo().performClick()
        waitFor("quest-search")
        compose.onNodeWithTag("quest-search").assertIsFocused()
        compose.onNodeWithTag("quest-search").performTextInput("94061234")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("quest:93000001").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quest:94061234").assertExists()
        compose.onNodeWithTag("quest-search-clear").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("quest-search").fetchSemanticsNodes().isEmpty() }
        waitFor("quest:93000001")
    }

    @Test fun theSearchFieldStaysWhileAQueryIsSet() {
        show(questSource(QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready)))
        waitFor("quest:93000001")
        compose.onNodeWithTag("quest-search-open").performScrollTo().performClick()
        waitFor("quest-search")
        compose.onNodeWithTag("quest-search").performTextInput("교회")
        compose.onNodeWithTag("quest-filter:Free").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("quest:94010101").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("quest-search").assertExists()
        compose.onNodeWithTag("quest:93000001").assertExists()
    }

    @Test fun theListGrowsAPageAtATimeAndAFilterStartsAgain() {
        show(questSource(QuestRecordsState(manyQuestRecords(130), RecordNameStatus.Ready)))
        waitFor("quest-records-show-all")
        assertEquals(RECORDS_FIRST_ROWS, compose.onAllNodes(hasTestTagPrefix("quest:")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("quest-records-show-all").assertTextEquals("전체 보기 (130)").performScrollTo().performClick()
        compose.onNodeWithTag("quest-records-more").assertTextEquals("50개 더 보기 (남은 80개)").performScrollTo().performClick()
        compose.onNodeWithTag("quest-records-more").assertTextEquals("30개 더 보기 (남은 30개)").performScrollTo().performClick()
        compose.onNodeWithTag("quest-records-more").assertDoesNotExist()
        compose.onNodeWithTag("quest-sort:Name").performScrollTo().performClick()
        waitFor("quest-records-show-all")
        compose.onNodeWithTag("quest-records-collapse").assertDoesNotExist()
    }

    @Test fun missingNamesShowNumbersAndARetry() {
        var retries = 0
        show(questSource(QuestRecordsState(questRecordsFixture(names = false), RecordNameStatus.Missing)) { retries++ })
        waitFor("quest:93000001")
        compose.onNodeWithText("#93000001").assertExists()
        compose.onNodeWithTag("quest-names-missing").assertExists()
        compose.onNodeWithTag("quest-names-retry").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun loadingThenRecords() {
        val flow = MutableStateFlow(QuestRecordsState())
        show(questSource(flow))
        waitFor("quest-records-loading")
        flow.value = QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready, paused = true)
        waitFor("quest:93000001")
        compose.onNodeWithTag("quest-records-loading").assertDoesNotExist()
        compose.onNodeWithTag("quest-records-paused").assertExists()
    }

    @Test fun noBlockWithoutTheTable() {
        show(questSource(QuestRecordsState(questRecordsFixture(), RecordNameStatus.Ready)), hasQuests = false)
        compose.onNodeWithTag("dashboard-section:${DashboardBlockId.QuestRecords}").assertDoesNotExist()
    }
}

private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") { node ->
    val tag = androidx.compose.ui.semantics.SemanticsProperties.TestTag
    node.config.contains(tag) && node.config[tag].startsWith(prefix)
}
