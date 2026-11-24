package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.width
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
        return PrefsCore(PrefMaker(store), context).also { it.captureTrackerEnabled.set(true) }
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

    @Test fun theBattleConfigScreenHasNoSwitchOfItsOwn() {
        battleConfig(prefs())
        compose.onNodeWithTag(SupportListFilterHeaderTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_summary_config)).assertExists()
        compose.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag(SupportListFilterHeaderTag))).assertCountEquals(0)
    }

    @Test fun eachConditionSaysWhetherTheFilterAppliesIt() {
        battleConfig(prefs())
        compose.onAllNodesWithText("필터 적용").onFirst().assertExists()
        compose.onAllNodesWithText("화면 판독만").onFirst().assertExists()
    }

    @Test fun noticeWhenCaptureIsOff() {
        battleConfig(prefs(), captureAvailable = false)
        compose.onNodeWithTag(SupportListFilterNoticeTag).assertExists()
        compose.onNodeWithText(context.getString(R.string.support_list_filter_capture_off)).assertExists()
    }

    @Test fun noticesNameWhatStopsTheFilter() {
        battleConfig(prefs(), build = SupportFilterBuild.Unmapped(listOf("My image"), listOf("My CE.png")))
        compose.onNodeWithText(context.getString(R.string.support_list_filter_unmapped, "My image, My CE.png")).assertExists()
    }

    @Test fun theBattleConfigScreenKeepsTheScriptOnlyItems() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.preferredServants.set(setOf("Arcueid"))
        battleConfig(prefs)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(autoFriendRequest))
        compose.onNodeWithText(autoFriendRequest).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(npImages))
        compose.onNodeWithText(npImages).assertExists()
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

    @Test fun withoutTheFilterTheSupportScreenIsAsBefore() {
        val prefs = prefs()
        prefs.forBattleConfig("t").support.preferredServants.set(setOf("Arcueid"))
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent { FGATheme { PreferredSupport(prefs.forBattleConfig("t").support, vm) } }
        compose.waitForIdle()
        compose.onNodeWithTag(DisplayedAscensionTag).assertDoesNotExist()
        compose.onNodeWithTag(SupportListFilterHeaderTag).assertDoesNotExist()
        compose.onAllNodesWithText("필터 적용").onFirst().assertDoesNotExist()
    }

    private val autoFriendRequest get() = context.getString(R.string.p_battle_config_support_auto_friend_request)
    private val friendsOnlyTitle get() = context.getString(R.string.p_battle_config_support_friends_only)
    private val npImages get() = context.getString(R.string.p_battle_config_support_pref_nps)
    private val grandServant get() = context.getString(R.string.p_battle_config_support_grand_servant)

    private fun profileScreen(prefs: PrefsCore, profile: io.github.fate_grand_automata.prefs.core.SupportFilterProfileCore) {
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent {
            FGATheme {
                SupportFilterProfile(
                    prefs, profile, vm,
                    filter = SupportListFilterUi(
                        R.string.support_list_filter_summary_profile, build = { SupportFilterBuild.Off },
                        extra = { io.github.fate_grand_automata.ui.more.ImportFromBattleConfig(prefs, profile) },
                        script = false,
                    ),
                    captureAvailable = true,
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
        compose.onNodeWithText(grandServant).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.p_battle_config_support_friend_names)).assertDoesNotExist()
    }

    @Test fun aProfileImportsABattleConfigsConditions() {
        val prefs = prefs()
        prefs.battleConfigList.set(setOf("src"))
        val source = prefs.forBattleConfig("src")
        source.name.set("Farming")
        source.support.preferredServants.set(setOf("Arcueid"))
        source.support.friendsOnly.set(true)
        val profile = prefs.addSupportFilterProfile("P", "p2")
        profileScreen(prefs, profile)
        compose.onNodeWithTag(ImportFromBattleConfigTag).performClick()
        compose.onNodeWithTag("${ImportConfigTag}src").performClick()
        compose.waitForIdle()
        assertEquals(setOf("Arcueid"), profile.support.preferredServants.get())
        assertTrue(profile.support.friendsOnly.get())
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
        compose.onNodeWithText(context.getString(R.string.support_filter_refresh_hint)).assertExists()
    }

    @Test fun thePanelsToggleSwitchesTheFilterAndOffClearsTheLastResult() {
        val prefs = prefs()
        SupportListLastResult.filtered(SupportListCounts(20, 12, 498, 15))
        prefs.supportFilterOn.set(true)
        panel(prefs)
        compose.onNodeWithText(lastText).assertExists()
        compose.onNodeWithTag(SupportFilterToggleTag).assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(prefs.supportFilterOn.get())
        assertNull(SupportListLastResult.last.value)
        compose.onNodeWithTag(SupportFilterToggleTag).assertIsOff().performClick()
        compose.waitForIdle()
        assertTrue(prefs.supportFilterOn.get())
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

    // The add and rename dialogs are not driven here: Robolectric never gets idle once a dialog's text field takes focus.
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
    }

    private fun assertPanelAsWideAsTheConfigList() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        launcher(fixture, initial = LauncherPanel.SupportFilter)
        val list = compose.onNodeWithTag(io.github.fate_grand_automata.ui.launcher.LauncherConfigListTag).getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag(SupportFilterPanelTag).getUnclippedBoundsInRoot()
        // Equal weights in one row; the row hands out leftover pixels one at a time, so they may differ by one pixel.
        assertEquals(list.width.value, panel.width.value, 1f / 1.5f + 0.01f)
        // The buttons are never clipped: stacked or side by side, each lies inside the panel.
        for (tag in listOf(SupportFilterTakeConfigTag, SupportFilterLoadProfileTag)) {
            val button = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(button.left >= panel.left && button.right <= panel.right, "$tag $button in $panel")
        }
    }

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theFilterPanelIsAsWideAsTheConfigListAt840() = assertPanelAsWideAsTheConfigList()

    @Test @Config(qualifiers = "ja-w1280dp-h720dp-hdpi")
    fun theFilterPanelIsAsWideAsTheConfigListAt1280() = assertPanelAsWideAsTheConfigList()

    @Test @Config(qualifiers = "ko-w840dp-h480dp-hdpi")
    fun theChipIsOfferedOutsideTheBattleLauncherTooWithNothingToTake() {
        val fixture = CaptureLauncherFixture(context, GameServer.Kr)
        fixture.core.supportFilterFeature.set(true)
        fixture.core.supportFilterOn.set(true)
        launcher(fixture, mode = ScriptModeEnum.Lottery)
        compose.onNodeWithText("서포트 필터 · 켜짐").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SupportFilterPanelTag).assertExists()
        compose.onNodeWithTag(SupportFilterTakeConfigTag).assertIsNotEnabled()
    }
}
