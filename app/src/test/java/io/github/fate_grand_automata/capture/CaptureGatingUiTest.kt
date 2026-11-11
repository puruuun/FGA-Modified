package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.account.DashboardUiFixture
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w840dp-h800dp-hdpi")
class CaptureGatingUiTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @After fun cleanTracker() { CaptureTracker.delete() }

    private fun more(on: Boolean) = compose.setContent {
        FGATheme { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.Icons { fixture.More(captureOn = on) } } }
    }
    private fun title(res: Int) = context.getString(res)
    private fun tab(res: Int) = compose.onNodeWithText(title(res)).performClick()

    private fun assertFeatures(shown: Boolean) {
        tab(R.string.p_additional_features)
        listOf(R.string.tools_chaldea_export, R.string.friends_title).forEach {
            val node = compose.onNode(hasText(title(it)) and hasAnyAncestor(hasTestTag("more-tab:AdditionalFeatures")))
            if (shown) node.assertExists() else node.assertDoesNotExist()
        }
        // The collection settings entry stays on Android 10+ whatever the switch says.
        compose.onNode(hasText(title(R.string.capture_title)) and hasAnyAncestor(hasTestTag("more-tab:AdditionalFeatures"))).assertExists()
        tab(R.string.p_storage)
        val icons = compose.onNode(hasText(title(R.string.icon_prefetch_title)))
        if (shown) icons.assertExists() else icons.assertDoesNotExist()
        // The default support image extraction is not a log-collection feature.
        compose.onNodeWithText(title(R.string.support_menu_extract_default_support_images)).assertExists()
    }

    @Test fun switchOffHidesLogFeaturesButKeepsTheSettingsEntry() { more(false); assertFeatures(false) }
    @Test fun switchOnShowsThem() { more(true); assertFeatures(true) }
    @Test @Config(sdk = [28]) fun android9HidesIconDownloadAndFriendsEvenWithSwitchOn() {
        more(true)
        tab(R.string.p_additional_features)
        compose.onNodeWithText(title(R.string.friends_title)).assertDoesNotExist()
        compose.onNodeWithText(title(R.string.tools_chaldea_export)).assertDoesNotExist()
        compose.onNodeWithText(title(R.string.capture_title)).assertDoesNotExist()
        tab(R.string.p_storage)
        compose.onNodeWithText(title(R.string.icon_prefetch_title)).assertDoesNotExist()
    }

    @Test fun togglingTheSwitchUpdatesTheScreenWithoutLeavingIt() {
        more(false)
        tab(R.string.p_additional_features)
        compose.onNodeWithText(title(R.string.friends_title)).assertDoesNotExist()
        compose.runOnIdle { fixture.prefs.captureTrackerEnabled.set(true) }
        compose.onNodeWithText(title(R.string.friends_title)).assertExists()
        compose.runOnIdle { fixture.prefs.captureTrackerEnabled.set(false) }
        compose.onNodeWithText(title(R.string.friends_title)).assertDoesNotExist()
    }

    @Test fun mainScreenAccountCardsFollowTheSwitchWithoutLeaving() {
        val store = context.getSharedPreferences("gating-main", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val prefs = PrefsCore(PrefMaker(store), context)
        CaptureTracker.init(context)
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/login/top", """{"response":[{"nid":"login","resCode":"00"}],
            "cache":{"replaced":{"userGame":[{"userId":7001,"name":"Fictional","actMax":100}]}}}""".toByteArray(), null)
        assertTrue(CaptureTracker.accounts.value.isNotEmpty())
        compose.setContent { FGATheme {
            val capture = rememberCaptureMain(prefs) {}
            MainScreenContent(navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {}, capture = capture.state)
        } }
        compose.onAllNodesWithText("KR").assertCountEquals(0)
        compose.runOnIdle { prefs.captureTrackerEnabled.set(true) }
        compose.onAllNodesWithText("KR").assertCountEquals(1)
        compose.runOnIdle { prefs.captureTrackerEnabled.set(false) }
        compose.onAllNodesWithText("KR").assertCountEquals(0)
    }

    @Test @Config(sdk = [28]) fun mainScreenAccountCardsStayHiddenOnAndroid9() {
        val store = context.getSharedPreferences("gating-main", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val prefs = PrefsCore(PrefMaker(store), context).apply { captureTrackerEnabled.set(true) }
        CaptureTracker.init(context)
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/login/top", """{"response":[{"nid":"login","resCode":"00"}],
            "cache":{"replaced":{"userGame":[{"userId":7001,"name":"Fictional","actMax":100}]}}}""".toByteArray(), null)
        compose.setContent { FGATheme {
            val capture = rememberCaptureMain(prefs) {}
            MainScreenContent(navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = true, toggleAccessibilityService = {}, capture = capture.state)
        } }
        compose.onAllNodesWithText("KR").assertCountEquals(0)
    }
}
