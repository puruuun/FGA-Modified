package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.ui.launcher.ManualSupportFilterRow
import io.github.fate_grand_automata.ui.launcher.ManualSupportFilterRowTag
import io.github.fate_grand_automata.ui.launcher.ManualSupportFilterReasonTag
import io.github.fate_grand_automata.ui.more.ImportConfigTag
import io.github.fate_grand_automata.ui.more.ImportFromBattleConfigTag
import io.github.fate_grand_automata.ui.more.ManualSupportFilter
import io.github.fate_grand_automata.ui.more.SupportListLastResultLine
import io.github.fate_grand_automata.ui.pref_support.DisplayedAscensionChipTag
import io.github.fate_grand_automata.ui.pref_support.DisplayedAscensionTag
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterNoticeTag
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterSwitchTag
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterUi
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.util.StorageProvider
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class SupportListFilterUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() = SupportListLastResult.resetForTesting()

    private val lastText = "마지막 목록: 20명 중 12명, 서번트 칸 498개 중 15개"

    @Test fun showsNothingBeforeTheFirstList() {
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("마지막 목록", substring = true).assertDoesNotExist()
    }

    @Test fun showsTheCountsAfterAFilteredList() {
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithText(context.getString(R.string.capture_support_filter_last, 20, 12, 498, 15)).assertExists()
    }

    @Test fun showsNotChangedAfterAPassThrough() {
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        SupportListLastResult.unchanged()
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("마지막 목록: 바꾸지 않음").assertExists()
    }

    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("support-filter-ui", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        for (id in listOf("t", "src")) context.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().commit()
        return PrefsCore(PrefMaker(store), context)
    }

    private fun battleConfig(
        prefs: PrefsCore,
        build: SupportFilterBuild? = SupportFilterBuild.Off,
        captureAvailable: Boolean = true,
    ) {
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent {
            FGATheme {
                PreferredSupport(
                    prefs.forBattleConfig("t").support, vm,
                    filter = SupportListFilterUi(R.string.support_list_filter_summary_config, build = { build }),
                    captureAvailable = captureAvailable,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun battleConfigSwitchShowsTheLastResultAndTurningItOffClearsIt() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.supportListFilter.set(true)
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        battleConfig(prefs)
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithTag(SupportListFilterSwitchTag).performClick()
        compose.onNodeWithText(lastText).assertDoesNotExist()
        assertNull(SupportListLastResult.last.value)
        assertFalse(prefs.forBattleConfig("t").support.supportListFilter.get())
    }

    @Test fun battleConfigSwitchIsOffByDefaultAndHidesTheLine() {
        val prefs = prefs()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        battleConfig(prefs)
        compose.onNodeWithText(lastText).assertDoesNotExist()
        compose.onNodeWithTag(SupportListFilterSwitchTag).performClick()
        assertTrue(prefs.forBattleConfig("t").support.supportListFilter.get())
    }

    @Test fun eachConditionSaysWhetherTheFilterAppliesIt() {
        val prefs = prefs()
        battleConfig(prefs)
        compose.onAllNodesWithText("필터 적용").onFirst().assertExists()
        compose.onAllNodesWithText("화면 판독만").onFirst().assertExists()
    }

    @Test fun noticeWhenCaptureIsOff() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.supportListFilter.set(true)
        battleConfig(prefs, captureAvailable = false)
        compose.onNodeWithTag(SupportListFilterNoticeTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_capture_off)).assertExists()
    }

    @Test fun noticeNamesTheEntriesThatCannotBeMapped() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.supportListFilter.set(true)
        battleConfig(prefs, build = SupportFilterBuild.Unmapped(listOf("My image"), listOf("My CE.png")))
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unmapped, "My image, My CE.png")).assertExists()
    }

    @Test fun noticeWhenNoServantListIsOnDisk() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.supportListFilter.set(true)
        battleConfig(prefs, build = SupportFilterBuild.NoClassList)
        compose.onNodeWithText(context.getString(R.string.support_list_filter_no_class_list)).assertExists()
    }

    @Test fun noticeNamesTheServantsOfUnknownClass() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.supportListFilter.set(true)
        battleConfig(prefs, build = SupportFilterBuild.UnknownClass(listOf("No.1 Mash", "No.900 ruler")))
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unknown_class, "No.1 Mash, No.900 ruler")).assertExists()
    }

    @Test fun playButtonMenuShowsWhyTheManualFilterDoesNotRun() {
        val prefs = prefs()
        val manual = prefs.manualSupportFilter
        manual.preferredServants.set(setOf("Arcueid"))
        var build: SupportFilterBuild = SupportFilterBuild.UnknownClass(listOf("Arcueid"))
        compose.setContent { FGATheme { ManualSupportFilterRow(manual, build = { build }) } }
        // Switched off, the summary shows.
        compose.onNodeWithText("서번트 1").assertExists()
        compose.onNodeWithTag(ManualSupportFilterReasonTag).assertDoesNotExist()
        compose.onNodeWithTag(ManualSupportFilterRowTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unknown_class, "Arcueid")).assertExists()
    }

    @Test fun playButtonMenuShowsUnmappedAndNoClassListToo() {
        val prefs = prefs()
        val manual = prefs.manualSupportFilter
        manual.supportListFilter.set(true)
        compose.setContent {
            FGATheme {
                androidx.compose.foundation.layout.Column {
                    ManualSupportFilterRow(manual, build = { SupportFilterBuild.Unmapped(listOf("Mine"), emptyList()) })
                    ManualSupportFilterRow(manual, build = { SupportFilterBuild.NoClassList })
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unmapped, "Mine")).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_no_class_list)).assertExists()
    }

    private val autoFriendRequest get() = context.getString(R.string.p_battle_config_support_auto_friend_request)
    private val friendsOnlyTitle get() = context.getString(R.string.p_battle_config_support_friends_only)

    @Test fun theManualFilterHidesTheScriptOnlyFriendRequestSwitch() {
        val prefs = prefs()
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent {
            FGATheme {
                ManualSupportFilter(
                    prefs, vm,
                    filter = SupportListFilterUi(R.string.support_list_filter_summary_manual, build = { SupportFilterBuild.Off }, script = false),
                    captureAvailable = true,
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(friendsOnlyTitle))
        compose.onNodeWithText(friendsOnlyTitle).assertExists()
        compose.onNodeWithText(autoFriendRequest).assertDoesNotExist()
    }

    @Test fun theBattleConfigScreenKeepsTheFriendRequestSwitch() {
        val prefs = prefs()
        battleConfig(prefs)
        compose.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(autoFriendRequest))
        compose.onNodeWithText(autoFriendRequest).assertExists()
    }

    @Test fun displayedAscensionChipsAreStoredPerServant() {
        val prefs = prefs()
        val support = prefs.forBattleConfig("t").support
        support.preferredServants.set(setOf("Arcueid"))
        battleConfig(prefs)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(DisplayedAscensionTag))
        compose.onNodeWithTag(DisplayedAscensionTag).performClick()
        compose.onNodeWithTag("${DisplayedAscensionChipTag}s4:Arcueid").performClick()
        compose.onNodeWithTag("${DisplayedAscensionChipTag}c2:Arcueid").performClick()
        compose.waitForIdle()
        assertEquals(setOf("s4|Arcueid", "c2|Arcueid"), support.displayedAscensions.get())
        compose.onNodeWithTag("${DisplayedAscensionChipTag}s4:Arcueid").performClick()
        compose.waitForIdle()
        assertEquals(setOf("c2|Arcueid"), support.displayedAscensions.get())
    }

    @Test fun displayedAscensionIsOnlyOnTheFilterScreens() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.preferredServants.set(setOf("Arcueid"))
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent { FGATheme { PreferredSupport(prefs.forBattleConfig("t").support, vm) } }
        compose.waitForIdle()
        compose.onNodeWithTag(DisplayedAscensionTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportListFilterSwitchTag).assertDoesNotExist()
    }

    @Test fun manualFilterImportsABattleConfigsConditions() {
        val prefs = prefs()
        prefs.battleConfigList.set(setOf("src"))
        val source = prefs.forBattleConfig("src")
        source.name.set("Farming")
        source.support.preferredServants.set(setOf("Arcueid"))
        source.support.friendsOnly.set(true)
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent {
            FGATheme {
                ManualSupportFilter(
                    prefs, vm,
                    filter = SupportListFilterUi(
                        R.string.support_list_filter_summary_manual, build = { SupportFilterBuild.Off },
                        extra = { io.github.fate_grand_automata.ui.more.ImportFromBattleConfig(prefs) },
                        script = false,
                    ),
                    captureAvailable = true,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.support_list_filter_manual_title)).assertExists()
        compose.onNodeWithTag(ImportFromBattleConfigTag).performClick()
        compose.onNodeWithTag("${ImportConfigTag}src").performClick()
        compose.waitForIdle()
        assertEquals(setOf("Arcueid"), prefs.manualSupportFilter.preferredServants.get())
        assertTrue(prefs.manualSupportFilter.friendsOnly.get())
        assertFalse(prefs.manualSupportFilter.supportListFilter.get())
    }

    @Test fun playButtonMenuSwitchesTheManualFilterAndSummarisesIt() {
        val prefs = prefs()
        val manual = prefs.manualSupportFilter
        manual.preferredServants.set(setOf("Arcueid", "No.2 a"))
        manual.maxAscended.set(true)
        manual.friendsOnly.set(true)
        compose.setContent { FGATheme { ManualSupportFilterRow(manual) } }
        compose.onNodeWithText("서번트 2 · 최종재림 · 친구만").assertExists()
        compose.onNodeWithTag(ManualSupportFilterRowTag).performClick()
        compose.waitForIdle()
        assertTrue(manual.supportListFilter.get())
        manual.friendsOnly.set(false)
        compose.waitForIdle()
        compose.onNodeWithText("서번트 2 · 최종재림").assertExists()
    }

    @Test fun playButtonMenuSaysWhenNoConditionIsSet() {
        val prefs = prefs()
        compose.setContent { FGATheme { ManualSupportFilterRow(prefs.manualSupportFilter) } }
        compose.onNodeWithText("조건 없음").assertExists()
    }
}
