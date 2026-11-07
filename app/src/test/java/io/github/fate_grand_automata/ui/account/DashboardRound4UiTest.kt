package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.main.MainScreenDestinations
import io.github.fate_grand_automata.ui.main.Route
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
// Match the interactive Log tests' native runtime. API 36 remains in the isolated screenshot suite.
@Config(sdk=[32],qualifiers="en-w840dp-h800dp-hdpi")
class DashboardRound4UiTest {
    private val compose=createComposeRule()
    @get:Rule val rules=screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }
    private fun content(block:@androidx.compose.runtime.Composable ()->Unit) = compose.setContent {
        FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons { block() } } }
    }
    @Test fun equalTileHeightAndSecondaryTextIsBesideLoadedIcon() {
        content { fixture.Icons { Row {
            ResourceTile(LogResource(id=6510,count=3,holdings=123),fixture.names)
            ResourceTile(LogResource(id=6001,count=5),fixture.names)
            ResourceTile(LogResource(id=999,count=2,holdings=4),fixture.names)
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("log-icon-loaded",useUnmergedTree=true).fetchSemanticsNodes().size==2 }
        val with=compose.onNodeWithTag("resource:2:6510").fetchSemanticsNode().boundsInRoot
        val without=compose.onNodeWithTag("resource:2:6001").fetchSemanticsNode().boundsInRoot
        val fallback=compose.onNodeWithTag("resource:2:999").fetchSemanticsNode().boundsInRoot
        assertEquals(72f,with.height,0.1f); assertEquals(with.height,without.height,0.1f); assertEquals(with.height,fallback.height,0.1f)
        val secondary=compose.onAllNodesWithTag("resource-secondary",useUnmergedTree=true).fetchSemanticsNodes().first().boundsInRoot
        val icon=compose.onAllNodesWithTag("log-icon-loaded",useUnmergedTree=true).fetchSemanticsNodes().first().boundsInRoot
        assertTrue(secondary.left>=icon.right); assertTrue(secondary.bottom<=icon.bottom+1)
    }
    @Test fun eachAccountCardRoutesToItsOwnDashboardAndHomeHasNoTools() {
        var destination:MainScreenDestinations?=null
        content { MainScreenContent({destination=it},false,{},false,{},CaptureMainState(profiles=mainProfiles(3))) }
        listOf(1,2,3).forEach { n ->
            compose.onNodeWithText("마스터 $n").performClick()
            assertEquals(MainScreenDestinations.AccountDashboard("sample-$n"),destination)
        }
        compose.onNodeWithText("Tools").assertDoesNotExist()
    }
    @Test fun dashboardSettingsAndLogsUseTheirNavigationActions() {
        var route:Route?=null
        content { AccountDashboardContent(fixture.data,fixture.names,"Alias",{route=Route.GainsTracker},{route=Route.Logs}) }
        compose.onNodeWithTag("dashboard-settings").performClick()
        assertEquals(Route.GainsTracker,route)
        assertEquals("user-2",Route.AccountDashboard("user-2").userId)
    }
    @Test fun friendsIconShownOnlyWithFriendListAndOpensDashboardAccount() {
        var opened:String?=null
        val hasList = androidx.compose.runtime.mutableStateOf(false)
        content { AccountDashboardContent(fixture.data,fixture.names,"Alias",{},{},userId="acc-7",friendCount=if(hasList.value) 12 else null,openFriends={opened=it}) }
        compose.onNodeWithTag("dashboard-friends").assertDoesNotExist()
        compose.runOnIdle { hasList.value=true }
        compose.onNodeWithTag("dashboard-friends").assertExists().performClick()
        assertEquals("acc-7",opened)
    }
    @Test fun topBarIconsAreExportFriendsSettings() {
        content { AccountDashboardContent(fixture.data,fixture.names,"Alias",{},{},userId="acc-7",friendCount=3) }
        val x={tag:String->compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left}
        assertTrue(x("dashboard-export")<x("dashboard-friends")); assertTrue(x("dashboard-friends")<x("dashboard-settings"))
    }
    @Test fun recentActivityShowsBothKindsAndOpensLogs() {
        var route:Route?=null
        val battle=io.github.fate_grand_automata.battlelog.battleLogFixture("latest").copy(configName="Recent quest",reasonText="8 runs")
        content { AccountDashboardContent(fixture.data,fixture.names,"Alias",{},{route=Route.Logs},battle=battle,
            outside=outsideFixture(),initialSection=6) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-section:0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-logs").performScrollTo()
        compose.onNodeWithText("Recent quest",substring=true).assertExists()
        compose.onNodeWithText("8 runs").assertExists()
        compose.onNodeWithTag("dashboard-logs").performClick()
        assertEquals(Route.Logs,route)
    }
    @Test fun missingTablesHideEverySectionAndSensitiveFieldsNeverAppear() {
        val data=dashboardData(dashboardState("userGame" to """[{"birthday":"PRIVATE-BIRTHDAY","message":"PRIVATE-MESSAGE"}]"""),GameNameMap())
        content { AccountDashboardContent(data,GameNameMap(),null,{},{}) }
        (0..6).forEach { compose.onNodeWithTag("dashboard-section:$it").assertDoesNotExist() }
        compose.onNodeWithText("PRIVATE-BIRTHDAY",substring=true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE-MESSAGE",substring=true).assertDoesNotExist()
    }
    @Test fun profileShowsAliasAndGameNameWithoutBirthdayOrMessage() {
        content { AccountDashboardContent(fixture.data,fixture.names,"Alias",{},{}) }
        compose.onNodeWithText("Alias · 샘플 마스터").assertExists()
        compose.onNodeWithText("PRIVATE-BIRTHDAY",substring=true).assertDoesNotExist()
        compose.onNodeWithText("PRIVATE-MESSAGE",substring=true).assertDoesNotExist()
    }
    @Test fun servantListStartsWithFiveExpandsAllAndCollapses() {
        content { fixture.Icons { AccountDashboardContent(fixture.data,fixture.names,"Alias",{},{},initialSection=3) } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("샘플 서번트 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-servant-list").performScrollToIndex(4)
        compose.onNodeWithText("샘플 서번트 5", substring = true).assertExists()
        compose.onNodeWithText("샘플 서번트 6", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show all (35)").performScrollTo().performClick()
        compose.onNodeWithTag("dashboard-servant-list").performScrollToIndex(34)
        compose.onNodeWithText("샘플 서번트 35", substring = true).assertExists()
        compose.onNodeWithText("Collapse").performScrollTo().performClick()
        compose.onNodeWithText("샘플 서번트 35", substring = true).assertDoesNotExist()
    }
    @Test fun additionalFeaturesOrdersToolsThenCollectionThenGameArea() {
        var gains=0
        content { fixture.More(gains={gains++}) }
        compose.onNodeWithText(fixture.context.getString(R.string.p_additional_features)).performClick()
        val list=compose.onNodeWithTag("more-tab:AdditionalFeatures")
        val titles=listOf(R.string.portrait_import_title,R.string.original_import_title,R.string.tools_chaldea_export,R.string.capture_title,R.string.p_game_area)
        val bounds=titles.map {
            compose.onNode(hasText(fixture.context.getString(it)) and hasAnyAncestor(hasTestTag("more-tab:AdditionalFeatures")))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEach { (first,second) -> assertTrue(first.bottom<=second.top) }
        compose.onNode(hasText(fixture.context.getString(R.string.capture_title)) and hasContentDescription("icon"))
            .assertIsDisplayed().performClick()
        assertEquals(1,gains)
        val moved=listOf(R.string.p_check_for_updates,R.string.p_support_reader_diagnostics,R.string.weekly_route_settings,R.string.p_weekly_route_feature_enabled,R.string.p_bond_tracking_feature_enabled,R.string.p_enable_quest_item_control)
        moved.forEach { key -> assertTrue(runCatching { list.performScrollToNode(hasText(fixture.context.getString(key))) }.isFailure) }
    }
    @Test @Config(sdk=[28]) fun additionalFeaturesHidesLogCollectionBeforeAndroid10() {
        content { fixture.More() }
        compose.onNodeWithText(fixture.context.getString(R.string.p_additional_features)).performClick()
        compose.onNodeWithText(fixture.context.getString(R.string.capture_title)).assertDoesNotExist()
    }
    @Test fun advancedAndBattleContainMovedPreferencesInTheirNewLists() {
        var weekly=0
        content { fixture.More(weekly={weekly++}) }
        compose.onNodeWithText(fixture.context.getString(R.string.p_advanced)).performClick()
        compose.onNodeWithTag("more-tab:Advanced").performScrollToNode(hasText(fixture.context.getString(R.string.p_check_for_updates)))
        compose.onNode(hasText(fixture.context.getString(R.string.weekly_route_settings)) and hasContentDescription("icon"))
            .assertIsDisplayed().performClick()
        assertEquals(1,weekly)
        if(BuildConfig.BUILD_TYPE=="beta") compose.onNodeWithText(fixture.context.getString(R.string.p_support_reader_diagnostics)).assertExists()
        else compose.onNodeWithText(fixture.context.getString(R.string.p_support_reader_diagnostics)).assertDoesNotExist()
        compose.onNodeWithText(fixture.context.getString(R.string.p_script_mode_battle)).performClick()
        listOf(R.string.p_weekly_route_feature_enabled,R.string.p_bond_tracking_feature_enabled,R.string.p_enable_quest_item_control).forEach {
            compose.onNodeWithTag("more-tab:Battle").performScrollToNode(hasText(fixture.context.getString(it)))
            compose.onNode(hasText(fixture.context.getString(it)) and hasAnyAncestor(hasTestTag("more-tab:Battle"))).assertExists()
        }
    }
}
