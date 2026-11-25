package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The quest records block (#730) for the owner's review: with names, with numbers only, and while reading. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class QuestRecordsScreenshotTest(private val width: Int, private val dark: Boolean, private val locale: String) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp_dark={1}_{2}")
        fun cases() = listOf(
            arrayOf<Any>(360, false, "ko"), arrayOf<Any>(360, true, "ko"), arrayOf<Any>(360, false, "ja"),
            arrayOf<Any>(1280, false, "ko"), arrayOf<Any>(1280, true, "ko"),
        )
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }

    private fun shot(name: String, state: QuestRecordsState, readyTag: String) =
        compose.shoot("dashboard-730", name, width, if (width >= 840) 900 else 1600, dark, locale, beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(readyTag).fetchSemanticsNodes().isNotEmpty() }
        }) {
            CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(fixture.data.copy(hasQuestRecords = true), fixture.names, "샘플 별명", {}, {},
                    initialSection = DashboardBlockId.QuestRecords, userId = "synthetic", questRecords = questSource(state))
            }
        }

    @Test fun withNames() = shot("QuestRecords", QuestRecordsState(questRecordsFixture(), QuestNameStatus.Ready), "quest:93000001")
    @Test fun numbersOnly() = shot("QuestRecordsNumbers", QuestRecordsState(questRecordsFixture(names = false), QuestNameStatus.Missing), "quest:93000001")
    @Test fun loading() = shot("QuestRecordsLoading", QuestRecordsState(), "quest-records-loading")
}
