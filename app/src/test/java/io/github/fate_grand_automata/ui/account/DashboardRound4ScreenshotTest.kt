package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.ui.LocalNavigateBack
import androidx.compose.runtime.CompositionLocalProvider
import io.github.fate_grand_automata.ui.log.*
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardRound4ScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}x{1}_dark={2}") fun sizes() =
            listOf(360 to 800,840 to 800,1280 to 720).flatMap { (w,h) -> listOf(false,true).map { arrayOf<Any>(w,h,it) } }
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }
    @Test fun dashboardTop() = compose.shoot("dashboard-round4","DashboardTop",width,height,dark,"ko") {
        fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) { AccountDashboardContent(fixture.data,fixture.names,"샘플 별명",{},{}) } }
    }
    @Test fun servantCollection() = compose.shoot("dashboard-round4","ServantCollection",width,height,dark,"ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 서번트 1").fetchSemanticsNodes().isNotEmpty() }
    }) {
        fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) { AccountDashboardContent(fixture.data,fixture.names,"샘플 별명",{},{},initialSection=3) } }
    }
    @Test fun resourceTiles() = compose.shoot("dashboard-round4","ResourceTiles",width,height,dark,"ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("log-icon-loaded",useUnmergedTree=true).fetchSemanticsNodes().size >= 3 }
    }) {
        fixture.Icons {
            Surface(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                ResourceTile(LogResource(id=6510,count=3,holdings=123),fixture.names)
                ResourceTile(LogResource(id=6001,count=5),fixture.names)
                ResourceTile(LogResource(id=2,count=10,split=8L to 2L),fixture.names)
                ResourceTile(LogResource(id=999,count=7,holdings=28),fixture.names)
            }
            }
        }
    }
    private fun tab(name:String,label:String) = compose.shoot("dashboard-round4",name,width,height,dark,"ko", beforeCapture = {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
        if(name=="Battle") compose.onNodeWithTag("more-tab:Battle").performScrollToNode(
            hasText(fixture.context.getString(io.github.fate_grand_automata.R.string.p_enable_quest_item_control)))
    }) { CompositionLocalProvider(LocalNavigateBack provides {}) { fixture.More() } }
    @Test fun additionalFeatures() = tab("AdditionalFeatures","추가기능")
    @Test fun battle() = tab("Battle","배틀")
    @Test fun advanced() = tab("Advanced","고급")
}
