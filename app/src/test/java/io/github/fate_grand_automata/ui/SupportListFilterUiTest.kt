package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.capture.SupportFilterBuild
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.LauncherPanel
import io.github.fate_grand_automata.ui.launcher.ScriptLauncher
import io.github.fate_grand_automata.ui.launcher.SupportFilterChipTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterLauncherPanel
import io.github.fate_grand_automata.ui.launcher.SupportFilterLoadProfileTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterPanelTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterProfileItemTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterReasonTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterTakeConfigTag
import io.github.fate_grand_automata.ui.launcher.SupportFilterToggleTag
import io.github.fate_grand_automata.ui.more.ImportConfigTag
import io.github.fate_grand_automata.ui.more.ImportFromBattleConfigTag
import io.github.fate_grand_automata.ui.more.SupportFilterAddProfileTag
import io.github.fate_grand_automata.ui.more.SupportFilterDeleteConfirmTag
import io.github.fate_grand_automata.ui.more.SupportFilterDeleteTag
import io.github.fate_grand_automata.ui.more.SupportFilterFeatureTag
import io.github.fate_grand_automata.ui.more.SupportFilterProfile
import io.github.fate_grand_automata.ui.more.SupportFilterProfileMenuTag
import io.github.fate_grand_automata.ui.more.SupportFilterRenameTag
import io.github.fate_grand_automata.ui.more.SupportFilterUseProfileTag
import io.github.fate_grand_automata.ui.more.SupportListFilterScreen
import io.github.fate_grand_automata.ui.more.SupportListLastResultLine
import io.github.fate_grand_automata.ui.pref_support.DisplayedAscensionChipTag
import io.github.fate_grand_automata.ui.pref_support.DisplayedAscensionTag
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterHeaderTag
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterNoticeTag
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterUi
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.util.StorageProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class SupportListFilterUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() = SupportListLastResult.resetForTesting()

    private val lastText = "필터 결과: 20명 중 12명 표시"

    @Test fun showsNothingBeforeTheFirstList() {
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("필터 결과", substring = true).assertDoesNotExist()
    }

    @Test fun showsTheCountsAfterAFilteredList() {
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithText(context.getString(R.string.capture_support_filter_last, 12, 20)).assertExists()
    }

    @Test fun showsNotChangedAfterAPassThrough() {
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        SupportListLastResult.unchanged()
        compose.setContent { FGATheme { SupportListLastResultLine() } }
        compose.onNodeWithText("필터 결과: 바꾸지 않음").assertExists()
    }

    private fun prefs(): PrefsCore {
        val store = context.getSharedPreferences("support-filter-ui", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        for (id in listOf("t", "src")) context.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().commit()
        return PrefsCore(PrefMaker(store), context).also { it.captureTrackerEnabled.set(true) }
    }

    /** The filter's own editor: where the labels and the displayed ascension item live since #708. */
    private fun profileEditor(
        prefs: PrefsCore,
        build: SupportFilterBuild? = SupportFilterBuild.Off,
        captureAvailable: Boolean = true,
    ): io.github.fate_grand_automata.prefs.core.SupportFilterProfileCore {
        val profile = prefs.addSupportFilterProfile("Farming", "p1")
        profileScreen(prefs, profile, build, captureAvailable)
        return profile
    }

    private fun battleConfigScreen(prefs: PrefsCore) {
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent { FGATheme { PreferredSupport(prefs.forBattleConfig("t").support, vm) } }
        compose.waitForIdle()
    }

    @Test fun theProfileEditorHasNoSwitchOfItsOwn() {
        profileEditor(prefs())
        compose.onNodeWithTag(SupportListFilterHeaderTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_summary_profile)).assertExists()
        compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag(SupportListFilterHeaderTag))).assertCountEquals(0)
    }

    @Test fun eachConditionOfAProfileSaysWhetherTheFilterAppliesIt() {
        profileEditor(prefs())
        compose.onAllNodesWithText("필터 적용").onFirst().assertExists()
    }

    @Test fun noticeWhenCaptureIsOff() {
        profileEditor(prefs(), captureAvailable = false)
        compose.onNodeWithTag(SupportListFilterNoticeTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_capture_off)).assertExists()
    }

    @Test fun noticesNameWhatStopsTheFilter() {
        profileEditor(prefs(), build = SupportFilterBuild.Unmapped(listOf("My image"), listOf("My CE.png")))
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unmapped, "My image, My CE.png")).assertExists()
    }

    @Test fun displayedAscensionChipsAreStoredPerServantInAProfile() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("Farming", "p1")
        profile.support.preferredServants.set(setOf("Arcueid"))
        profileScreen(prefs, profile)
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(DisplayedAscensionTag))
        compose.onNodeWithTag(DisplayedAscensionTag).performClick()
        compose.onNodeWithTag("${DisplayedAscensionChipTag}s4:Arcueid").performClick()
        compose.onNodeWithTag("${DisplayedAscensionChipTag}c2:Arcueid").performClick()
        compose.waitForIdle()
        assertEquals(setOf("s4|Arcueid", "c2|Arcueid"), profile.support.displayedAscensions.get())
        compose.onNodeWithTag("${DisplayedAscensionChipTag}s4:Arcueid").performClick()
        compose.waitForIdle()
        assertEquals(setOf("c2|Arcueid"), profile.support.displayedAscensions.get())
    }

    // The battle config's own screen is the one from before #685, with the feature on as well (#708).
    @Test fun theBattleConfigSupportScreenHasNoFilterElementsWithTheFeatureOn() {
        val prefs = prefs()
        prefs.supportFilterFeature.set(true)
        prefs.forBattleConfig("t").support.preferredServants.set(setOf("Arcueid"))
        battleConfigScreen(prefs)
        compose.onNodeWithTag(DisplayedAscensionTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportListFilterHeaderTag).assertDoesNotExist()
        compose.onAllNodesWithText("필터 적용").assertCountEquals(0)
        compose.onAllNodesWithText("화면 판독만").assertCountEquals(0)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(autoFriendRequest))
        compose.onNodeWithText(autoFriendRequest).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(npImages))
        compose.onNodeWithText(npImages).assertExists()
    }

    private val autoFriendRequest get() = context.getString(R.string.p_battle_config_support_auto_friend_request)
    private val friendsOnlyTitle get() = context.getString(R.string.p_battle_config_support_friends_only)
    private val npImages get() = context.getString(R.string.p_battle_config_support_pref_nps)
    private val grandServant get() = context.getString(R.string.p_battle_config_support_grand_servant)

    private fun profileScreen(
        prefs: PrefsCore,
        profile: io.github.fate_grand_automata.prefs.core.SupportFilterProfileCore,
        build: SupportFilterBuild? = SupportFilterBuild.Off,
        captureAvailable: Boolean = true,
    ) {
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent {
            FGATheme {
                SupportFilterProfile(
                    prefs, profile, vm,
                    filter = SupportListFilterUi(
                        R.string.support_list_filter_summary_profile, build = { build },
                        extra = { io.github.fate_grand_automata.ui.more.ImportFromBattleConfig(prefs, profile) },
                        script = false,
                    ),
                    captureAvailable = captureAvailable,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun aProfileHidesTheScriptOnlyItems() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("Farming", "p1")
        profile.support.preferredServants.set(setOf("Arcueid"))
        profile.support.friendsOnly.set(true)
        profileScreen(prefs, profile)
        compose.onNodeWithText("Farming").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(friendsOnlyTitle))
        compose.onNodeWithText(friendsOnlyTitle).assertExists()
        compose.onNodeWithText(autoFriendRequest).assertDoesNotExist()
        compose.onNodeWithText(npImages).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.p_battle_config_support_friend_names)).assertDoesNotExist()
    }

    @Test fun withoutAPreferredServantTheGrandCeItemsAreNotMarkedAsFiltered() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("Grand", "p4")
        profile.support.preferredCEs.set(setOf("Black Grail.png"))
        profileScreen(prefs, profile)
        val both = context.getString(R.string.p_battle_config_support_require_both_normal_and_reward_match)
        fun labelRightUnderBoth(): Boolean {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(both))
            compose.waitForIdle()
            // Positions, not clipped bounds: the label may sit below the visible part of the list.
            val item = compose.onNodeWithText(both).fetchSemanticsNode()
            val itemBottom = item.positionInRoot.y + item.size.height
            // The label sits right under its item; the next card's labels are much further down.
            val reach = 40f * context.resources.displayMetrics.density
            return compose.onAllNodesWithText("필터 적용").fetchSemanticsNodes()
                .any { it.positionInRoot.y >= itemBottom && it.positionInRoot.y <= itemBottom + reach }
        }
        compose.runOnIdle { profile.support.grandServant.set(true) }
        compose.waitForIdle()
        assertFalse(labelRightUnderBoth())
        // With a preferred servant the filter applies them, and the label shows.
        compose.runOnIdle { profile.support.preferredServants.set(setOf("Arcueid")) }
        compose.waitForIdle()
        assertTrue(labelRightUnderBoth())
    }

    @Test fun aProfileShowsTheGrandItemsTheFilterNowApplies() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("Grand", "p3")
        profile.support.preferredServants.set(setOf("Arcueid"))
        profile.support.preferredCEs.set(setOf("Black Grail.png"))
        profileScreen(prefs, profile)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(grandServant))
        compose.onNodeWithText(grandServant).performClick()
        compose.waitForIdle()
        assertTrue(profile.support.grandServant.get())
        val grand16 = context.getString(R.string.p_grand_score_16_check)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(grand16))
        compose.onNodeWithText(grand16).performClick()
        compose.waitForIdle()
        assertTrue(profile.support.grandScore16Check.get())
        val both = context.getString(R.string.p_battle_config_support_require_both_normal_and_reward_match)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(both))
        compose.onNodeWithText(context.getString(R.string.p_battle_config_support_bond_ce_effect_preference)).assertExists()
        compose.onNodeWithText(both).performClick()
        compose.waitForIdle()
        assertTrue(profile.support.requireBothNormalAndRewardMatch.get())
        // Every item the profile shows is one the filter applies.
        compose.onAllNodesWithText("화면 판독만").assertCountEquals(0)
    }

    @Test fun aProfileImportsABattleConfigsConditions() {
        val prefs = prefs()
        prefs.battleConfigList.set(setOf("src"))
        val source = prefs.forBattleConfig("src")
        source.name.set("Farming")
        source.support.preferredServants.set(setOf("Arcueid"))
        source.support.friendsOnly.set(true)
        source.support.displayedAscensions.set(setOf("s4|Arcueid"))
        val profile = prefs.addSupportFilterProfile("P", "p2")
        profileScreen(prefs, profile)
        compose.onNodeWithTag(ImportFromBattleConfigTag).performClick()
        compose.onNodeWithTag("${ImportConfigTag}src").performClick()
        compose.waitForIdle()
        assertEquals(setOf("Arcueid"), profile.support.preferredServants.get())
        assertTrue(profile.support.friendsOnly.get())
        assertEquals(emptySet(), profile.support.displayedAscensions.get())
        // A profile is not the active filter.
        assertEquals(emptySet(), prefs.activeSupportFilter.preferredServants.get())
    }

    private fun panel(prefs: PrefsCore, selected: String? = null, build: SupportFilterBuild? = SupportFilterBuild.Off) {
        compose.setContent { FGATheme { Surface(Modifier.fillMaxSize()) { SupportFilterLauncherPanel(prefs, selected, build = { build }) } } }
        compose.waitForIdle()
    }

    @Test fun thePanelShowsEveryActiveConditionInFull() {
        val prefs = prefs()
        val active = prefs.activeSupportFilter
        active.preferredServants.set(setOf("Arcueid", "No.2 Somebody"))
        active.displayedAscensions.set(setOf("s4|Arcueid", "s1|Arcueid", "c2|Arcueid"))
        active.preferredCEs.set(setOf("Black Grail.png"))
        active.mlb.set(true)
        active.maxAscended.set(true)
        active.skill1Max.set(true)
        active.appendSkillCheck.set(true)
        active.appendSkillsMax[1].set(true)
        active.minimumNpLevel.set(3)
        active.npLevelCheck.set(true)
        active.score62Check.set(true)
        active.friendsOnly.set(true)
        prefs.supportFilterSource.set(SupportFilterSource.Profile)
        prefs.supportFilterSourceName.set("주회용")
        panel(prefs)
        val arcueid = io.github.fate_grand_automata.util.SupportNameResources.getLocalizedServantName(context, "Arcueid")
        val grail = io.github.fate_grand_automata.util.SupportNameResources.getLocalizedCEName(context, "Black Grail")
        compose.onNodeWithText("서번트: $arcueid (1단계, 최종, 영의 2), No.2 Somebody").assertExists()
        compose.onNodeWithText("예장: $grail · 풀돌").assertExists()
        for (line in listOf("최종재림", "스킬 10/x/x", "어펜드 x/10/x/x/x", "보구 3+", "클래스 스코어 +62", "친구만")) {
            compose.onNodeWithText(line).assertExists()
        }
        compose.onNodeWithText("조건 출처: 프로필 주회용").assertExists()
        compose.onNodeWithText("가져오기").assertExists()
    }

    @Test fun thePanelListsTheGrandConditionsOnTheBuildersTerms() {
        val prefs = prefs()
        val active = prefs.activeSupportFilter
        active.preferredServants.set(setOf("Arcueid"))
        active.preferredCEs.set(setOf("Black Grail.png"))
        active.grandServant.set(true)
        active.grandScore16Check.set(true)
        active.bondCEEffect.set(io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum.NP)
        active.requireBothNormalAndRewardMatch.set(true)
        panel(prefs)
        val lines = listOf("관위 서번트", "그랜드 스코어 +16", "인연예장: NP 충전", "일반·보상 예장 모두 일치")
        for (line in lines) compose.onNodeWithText(line).assertExists()
        // Without a preferred CE the two CE conditions do not apply, and the panel does not list them.
        compose.runOnIdle { active.preferredCEs.set(emptySet()) }
        compose.waitForIdle()
        compose.onNodeWithText("관위 서번트").assertExists()
        compose.onNodeWithText("인연예장: NP 충전").assertDoesNotExist()
        compose.onNodeWithText("일반·보상 예장 모두 일치").assertDoesNotExist()
        // Without a preferred servant none of them applies.
        compose.runOnIdle { active.preferredServants.set(emptySet()) }
        compose.waitForIdle()
        for (line in lines) compose.onNodeWithText(line).assertDoesNotExist()
    }

    @Test fun takingFromTheSelectedConfigCopiesTheGrandConditions() {
        val prefs = prefs()
        val source = prefs.forBattleConfig("src").support
        source.preferredServants.set(setOf("Arcueid"))
        source.preferredCEs.set(setOf("Black Grail.png"))
        source.grandServant.set(true)
        source.grandScore16Check.set(true)
        source.bondCEEffect.set(io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum.Default)
        source.requireBothNormalAndRewardMatch.set(true)
        panel(prefs, selected = "src")
        compose.onNodeWithTag(SupportFilterTakeConfigTag).performClick()
        compose.waitForIdle()
        val active = prefs.activeSupportFilter
        assertTrue(active.grandServant.get())
        assertTrue(active.grandScore16Check.get())
        assertEquals(io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum.Default, active.bondCEEffect.get())
        assertTrue(active.requireBothNormalAndRewardMatch.get())
    }

    @Test fun thePanelsCheckboxSwitchesTheFilterAndOffClearsTheLastResult() {
        val prefs = prefs()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        prefs.supportFilterOn.set(true)
        panel(prefs)
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithTag(SupportFilterToggleTag, useUnmergedTree = true).assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(prefs.supportFilterOn.get())
        assertNull(SupportListLastResult.last.value)
        compose.onNodeWithTag(SupportFilterToggleTag, useUnmergedTree = true).assertIsOff().performClick()
        compose.waitForIdle()
        assertTrue(prefs.supportFilterOn.get())
    }

    // The whole row is the target, as in the middle column, not just the box.
    @Test fun tappingTheLabelTogglesTheFilter() {
        val prefs = prefs()
        panel(prefs)
        compose.onNodeWithText("서포트 목록 필터", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(prefs.supportFilterOn.get())
    }

    @Test fun thePanelHasNoHintAndTheLabelSitsAboveTheTwoButtons() {
        val prefs = prefs()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        panel(prefs, selected = "src")
        compose.onNodeWithText("서포트 목록은 없을 때만", substring = true).assertDoesNotExist()
        val result = compose.onNodeWithText(lastText).getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("가져오기").getUnclippedBoundsInRoot()
        val script = compose.onNodeWithTag(SupportFilterTakeConfigTag).getUnclippedBoundsInRoot()
        assertTrue(result.bottom <= label.top && label.bottom <= script.top, "$result $label $script")
    }

    @Test fun theButtonsKeepTheirFullActionForAccessibility() {
        val prefs = prefs()
        panel(prefs, selected = "src")
        compose.onNodeWithTag(SupportFilterTakeConfigTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(context.getString(R.string.support_filter_take_config))))
        compose.onNodeWithTag(SupportFilterLoadProfileTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(context.getString(R.string.support_filter_load_profile))))
    }

    @Test fun theStopReasonShowsInsteadOfFilteringOnlyWhileTheToggleIsOn() {
        val prefs = prefs()
        prefs.activeSupportFilter.preferredServants.set(setOf("Arcueid"))
        panel(prefs, build = SupportFilterBuild.UnknownClass(listOf("Arcueid")))
        compose.onNodeWithTag(SupportFilterReasonTag).assertDoesNotExist()
        prefs.supportFilterOn.set(true)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unknown_class, "Arcueid")).assertExists()
    }

    @Test fun takingFromTheSelectedConfigCopiesItsConditionsWhateverItsMode() {
        val prefs = prefs()
        val source = prefs.forBattleConfig("src")
        source.name.set("일일 주회")
        source.support.preferredServants.set(setOf("Arcueid"))
        source.support.selectionMode.set(io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum.First)
        panel(prefs, selected = "src")
        compose.onNodeWithTag(SupportFilterTakeConfigTag).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(setOf("Arcueid"), prefs.activeSupportFilter.preferredServants.get())
        assertEquals(SupportFilterSource.Config, prefs.supportFilterSource.get())
        compose.onNodeWithText("조건 출처: 전투 설정 일일 주회").assertExists()
        // A copy: the config changing later leaves the active filter alone.
        source.support.preferredServants.set(setOf("Other"))
        assertEquals(setOf("Arcueid"), prefs.activeSupportFilter.preferredServants.get())
    }

    @Test fun takingFromAConfigLeavesItsDisplayedAscensionsBehind() {
        val prefs = prefs()
        val source = prefs.forBattleConfig("src")
        source.name.set("Daily")
        source.support.preferredServants.set(setOf("Arcueid"))
        source.support.displayedAscensions.set(setOf("s4|Arcueid"))
        prefs.activeSupportFilter.displayedAscensions.set(setOf("c1|Other"))
        panel(prefs, selected = "src")
        compose.onNodeWithTag(SupportFilterTakeConfigTag).performClick()
        compose.waitForIdle()
        assertEquals(setOf("Arcueid"), prefs.activeSupportFilter.preferredServants.get())
        assertEquals(emptySet(), prefs.activeSupportFilter.displayedAscensions.get())
        // The config's stored value itself is not touched.
        assertEquals(setOf("s4|Arcueid"), source.support.displayedAscensions.get())
    }

    @Test fun aProfileKeepsItsDisplayedAscensionsWhenLoaded() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("P", "p9")
        profile.support.preferredServants.set(setOf("Arcueid"))
        profile.support.displayedAscensions.set(setOf("s4|Arcueid"))
        panel(prefs)
        compose.onNodeWithTag(SupportFilterLoadProfileTag).performClick()
        compose.onNodeWithTag("${SupportFilterProfileItemTag}p9").performClick()
        compose.waitForIdle()
        assertEquals(setOf("s4|Arcueid"), prefs.activeSupportFilter.displayedAscensions.get())
    }

    @Test fun withoutASelectedConfigOrAProfileTheButtonsAreOff() {
        val prefs = prefs()
        panel(prefs)
        compose.onNodeWithTag(SupportFilterTakeConfigTag).assertIsNotEnabled()
        compose.onNodeWithTag(SupportFilterLoadProfileTag).assertIsNotEnabled()
        compose.onNodeWithText("조건 출처: 아직 없음").assertExists()
    }

    @Test fun loadingAProfileCopiesItsConditions() {
        val prefs = prefs()
        val profile = prefs.addSupportFilterProfile("고난도", "p3")
        profile.support.friendsOnly.set(true)
        panel(prefs)
        compose.onNodeWithTag(SupportFilterLoadProfileTag).assertIsEnabled().performClick()
        compose.onNodeWithTag("${SupportFilterProfileItemTag}p3").performClick()
        compose.waitForIdle()
        assertTrue(prefs.activeSupportFilter.friendsOnly.get())
        assertEquals("고난도", prefs.supportFilterSourceName.get())
        compose.onNodeWithText("조건 출처: 프로필 고난도").assertExists()
    }

    @Test fun aProfileNameIsTrimmedAndCannotBeBlank() {
        assertNull(io.github.fate_grand_automata.ui.more.profileName(""))
        assertNull(io.github.fate_grand_automata.ui.more.profileName("  \t "))
        assertEquals("새 프로필", io.github.fate_grand_automata.ui.more.profileName("  새 프로필 "))
    }

    /**
     * The name dialog stands in as a button that confirms [name]: Robolectric never gets idle once a dialog's text
     * field has focus, also with the clock driven by hand. It confirms only what [profileName] can give.
     */
    private fun fakeNameDialog(name: String): io.github.fate_grand_automata.ui.more.ProfileNameDialogContent = { title, _, _, onConfirm ->
        androidx.compose.material3.TextButton(
            onClick = { onConfirm(io.github.fate_grand_automata.ui.more.profileName(name)!!) },
            modifier = androidx.compose.ui.Modifier.testTag("fake-name:$title"),
        ) { androidx.compose.material3.Text("ok") }
    }

    @Test fun addingAProfileOpensItsEditorAndRenamingUpdatesTheListButNotTheSourceLine() {
        val prefs = prefs()
        var edited: String? = null
        var typed by androidx.compose.runtime.mutableStateOf(" 주회용 ")
        compose.setContent { FGATheme { SupportListFilterScreen(prefs, editProfile = { edited = it }, nameDialog = { a, b, c, d -> fakeNameDialog(typed)(a, b, c, d) }) } }
        val add = context.getString(R.string.support_filter_profile_add)
        val rename = context.getString(R.string.support_filter_profile_rename)

        compose.onNodeWithTag(SupportFilterAddProfileTag).performClick()
        compose.onNodeWithTag("fake-name:$add").performClick()
        compose.waitForIdle()
        val id = prefs.supportFilterProfiles.get().single()
        assertEquals(id, edited)
        assertEquals("주회용", prefs.forSupportFilterProfile(id).name.get())

        compose.onNodeWithTag("$SupportFilterUseProfileTag$id").performClick()
        compose.waitForIdle()
        assertEquals("주회용", prefs.supportFilterSourceName.get())

        typed = "고난도"
        compose.onNodeWithTag("$SupportFilterProfileMenuTag$id").performClick()
        compose.onNodeWithTag(SupportFilterRenameTag).performClick()
        compose.onNodeWithTag("fake-name:$rename").performClick()
        compose.waitForIdle()
        assertEquals("고난도", prefs.forSupportFilterProfile(id).name.get())
        compose.onNodeWithText("고난도").assertExists()
        compose.onNodeWithText("주회용").assertDoesNotExist()
        // The active filter was copied under the old name; the source line keeps it.
        assertEquals("주회용", prefs.supportFilterSourceName.get())
    }

    @Test fun theMoreScreenSwitchesTheFeatureAndManagesProfiles() {
        val prefs = prefs()
        val id = prefs.addSupportFilterProfile("새 프로필", "m1").id
        prefs.forSupportFilterProfile(id).support.friendsOnly.set(true)
        var edited: String? = null
        compose.setContent { FGATheme { SupportListFilterScreen(prefs, editProfile = { edited = it }) } }
        compose.onNodeWithTag(SupportFilterFeatureTag).performClick()
        compose.waitForIdle()
        assertTrue(prefs.supportFilterFeature.get())
        compose.onNodeWithTag(SupportFilterAddProfileTag).assertExists()

        // The middle of the row is the "use" button, so the tap goes to the name at the left.
        compose.onNodeWithText("새 프로필").performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, height / 2f)) }
        compose.waitForIdle()
        assertEquals(id, edited)

        compose.onNodeWithTag("$SupportFilterUseProfileTag$id").performClick()
        compose.waitForIdle()
        assertTrue(prefs.activeSupportFilter.friendsOnly.get())
        assertEquals(SupportFilterSource.Profile, prefs.supportFilterSource.get())
        assertEquals("새 프로필", prefs.supportFilterSourceName.get())

        prefs.forSupportFilterProfile(id).name.set("이름 바꿈")
        compose.waitForIdle()
        compose.onNodeWithText("이름 바꿈").assertExists()

        compose.onNodeWithTag("$SupportFilterProfileMenuTag$id").performClick()
        compose.onNodeWithTag(SupportFilterRenameTag).assertExists()
        compose.onNodeWithTag(SupportFilterDeleteTag).performClick()
        compose.onNodeWithTag(SupportFilterDeleteConfirmTag).performClick()
        compose.waitForIdle()
        assertEquals(emptySet(), prefs.supportFilterProfiles.get())
        compose.onNodeWithText(context.getString(R.string.support_filter_profiles_none)).assertExists()
        // The active filter keeps what it copied.
        assertTrue(prefs.activeSupportFilter.friendsOnly.get())
    }

    private fun launcher(fixture: CaptureLauncherFixture, mode: ScriptModeEnum = ScriptModeEnum.Battle, initial: LauncherPanel = LauncherPanel.None) {
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        fixture.core.captureTrackerEnabled.set(true)
        compose.setContent { FGATheme { ScriptLauncher(mode, {}, fixture.prefs, fixture.core, initialPanel = initial) } }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun withTheFeatureOffTheLauncherHasNoChipAndNoPanel() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        launcher(fixture, initial = LauncherPanel.SupportFilter)
        compose.onNodeWithTag(SupportFilterChipTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theChipOpensTheFilterAndClosesTheWeeklyPanel() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture)
        compose.onNodeWithText("서포트 필터 · 꺼짐").assertExists()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        // The config selected in the launcher is the one "take from" uses.
        compose.onNodeWithTag(SupportFilterTakeConfigTag).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.weekly_route_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        assertFalse(fixture.core.supportFilterPanelOpen.get())
    }

    private fun assertPanelAsWideAsTheConfigList() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture, initial = LauncherPanel.SupportFilter)
        val list = compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherConfigListTag).getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag(SupportFilterPanelTag).getUnclippedBoundsInRoot()
        // Equal weights in one row; the row hands out leftover pixels one at a time, so they may differ by one pixel.
        assertEquals(list.width.value, panel.width.value, 1f / 1.5f + 0.01f)
        // The two buttons sit side by side inside the panel, each with its short label whole on one line.
        val script = compose.onNodeWithTag(SupportFilterTakeConfigTag).getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithTag(SupportFilterLoadProfileTag).getUnclippedBoundsInRoot()
        assertTrue(script.left >= panel.left && profile.right <= panel.right, "$script $profile in $panel")
        assertTrue(script.right <= profile.left, "$script is left of $profile")
        assertEquals(script.top.value, profile.top.value, 0.5f)
        val labels = if (context.resources.configuration.locales[0].language == "ja") listOf("スクリプト", "プロファイル") else listOf("스크립트", "프로필")
        for ((tag, label) in listOf(SupportFilterTakeConfigTag to labels[0], SupportFilterLoadProfileTag to labels[1])) {
            val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            text.assertExists()
            val layouts = mutableListOf<TextLayoutResult>()
            text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            assertEquals(1, layout.lineCount, "$label is on one line")
            assertFalse(layout.isLineEllipsized(0), "$label is not ellipsized")
            assertTrue(layout.getLineRight(0) <= layout.size.width, "$label fits its box ${layout.getLineRight(0)} ${layout.size.width}")
        }
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theFilterPanelIsAsWideAsTheConfigListAt840() = assertPanelAsWideAsTheConfigList()

    // The weekly route ignores the run limits, so they are hidden next to its panel; the filter panel keeps them.
    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theLimitsHideOnlyNextToTheWeeklyPanel() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture, initial = LauncherPanel.Weekly)
        val settings = compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherSettingsListTag)
        repeat(5) { settings.performTouchInput { swipeUp() } }
        compose.waitForIdle()
        compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        settings.performScrollToNode(hasTestTag(io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag))
        compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag).assertExists()
    }

    // #719: the two import buttons are the size of "모두 초기화", measured in one composition.
    private fun assertImportButtonsAreAsHighAsResetAll() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture, initial = LauncherPanel.SupportFilter)
        val settings = compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherSettingsListTag)
        settings.performScrollToNode(hasTestTag(io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag))
        compose.waitForIdle()
        val reset = compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag).getUnclippedBoundsInRoot()
        val script = compose.onNodeWithTag(SupportFilterTakeConfigTag).getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithTag(SupportFilterLoadProfileTag).getUnclippedBoundsInRoot()
        assertEquals(reset.height, script.height, "reset $reset script $script")
        assertEquals(reset.height, profile.height, "reset $reset profile $profile")
        // Each is as wide as its own label, not stretched over the panel.
        val panelWidth = compose.onNodeWithTag(SupportFilterPanelTag).getUnclippedBoundsInRoot().width
        assertTrue(script.width < panelWidth / 2 && profile.width < panelWidth / 2, "$script $profile $panelWidth")
    }

    @Test @Config(qualifiers = "ko-w840dp-h900dp-hdpi")
    fun theImportButtonsAreAsHighAsResetAll() = assertImportButtonsAreAsHighAsResetAll()

    @Test @Config(qualifiers = "ja-w1280dp-h720dp-hdpi")
    fun theFilterPanelIsAsWideAsTheConfigListAt1280() = assertPanelAsWideAsTheConfigList()

    // #719: only the battle launcher offers the filter, even with the feature on and the open state stored as true.
    private fun assertNoFilterInLauncher(mode: ScriptModeEnum) {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterOn.set(true)
        fixture.core.supportFilterPanelOpen.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        context.getSharedPreferences(PrefsCore.MANUAL_SUPPORT_FILTER_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        val asked = mutableListOf<Float>()
        compose.setContent { FGATheme { ScriptLauncher(mode, {}, fixture.prefs, fixture.core, onSidePanelExpanded = { asked += it }) } }
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterChipTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        compose.onNodeWithText("서포트 필터 · 켜짐").assertDoesNotExist()
        // The window is never asked to widen, so its width is the one without the feature.
        assertEquals(emptyList(), asked)
        // The stored state stays for the battle launcher.
        assertTrue(fixture.core.supportFilterPanelOpen.get())
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theFpLauncherHasNoFilterChipNorPanel() = assertNoFilterInLauncher(ScriptModeEnum.FP)

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theLotteryLauncherHasNoFilterChipNorPanel() = assertNoFilterInLauncher(ScriptModeEnum.Lottery)

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theBattleLauncherStillRestoresTheStoredPanelAndWidens() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterPanelOpen.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        val asked = mutableListOf<Float>()
        compose.setContent { FGATheme { ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core, onSidePanelExpanded = { asked += it }) } }
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterChipTag).assertExists()
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        assertEquals(listOf(LauncherPanel.SupportFilter.widen), asked)
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theOpenStateIsRememberedAcrossTwoLaunchers() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        var show by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            FGATheme { if (show) ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        show = false
        compose.waitForIdle()
        show = true
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        compose.onNodeWithTag(SupportFilterChipTag).performClick()
        compose.waitForIdle()
        show = false
        compose.waitForIdle()
        show = true
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theWeeklyPanelClosingTheFilterPanelIsRemembered() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterPanelOpen.set(true)
        launcher(fixture)
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.weekly_route_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        assertFalse(fixture.core.supportFilterPanelOpen.get())
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun aRememberedOpenPanelIsIgnoredWithTheFeatureOff() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterPanelOpen.set(true)
        launcher(fixture)
        compose.onNodeWithTag(SupportFilterPanelTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportFilterChipTag).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun aRestoredOpenPanelAsksTheWindowToWiden() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterPanelOpen.set(true)
        fixture.core.captureTrackerEnabled.set(true)
        val asked = mutableListOf<Float>()
        compose.setContent { FGATheme { ScriptLauncher(ScriptModeEnum.Battle, {}, fixture.prefs, fixture.core, onSidePanelExpanded = { asked += it }) } }
        compose.waitForIdle()
        assertEquals(listOf(LauncherPanel.SupportFilter.widen), asked)
    }

    // Both buttons use one style and show the open panel the same way.
    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theTwoPanelButtonsShowTheOpenPanelTheSameWay() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture)
        val weekly = compose.onNode(hasText(context.getString(R.string.weekly_route_title)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
        val filter = compose.onNodeWithTag(SupportFilterChipTag)
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        val notSelected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        weekly.assert(notSelected)
        filter.assert(notSelected)
        filter.performClick()
        compose.waitForIdle()
        filter.assert(selected)
        weekly.assert(notSelected)
        weekly.performClick()
        compose.waitForIdle()
        weekly.assert(selected)
        filter.assert(notSelected)
        // One style: the same height, since both are text buttons.
        assertEquals(weekly.getUnclippedBoundsInRoot().height.value, filter.getUnclippedBoundsInRoot().height.value, 0.5f)
    }
}
