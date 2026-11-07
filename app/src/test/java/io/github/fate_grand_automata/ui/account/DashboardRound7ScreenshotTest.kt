package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.capture.ChaldeaExportStore
import io.github.fate_grand_automata.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardRound7ScreenshotTest(private val width: Int, private val height: Int, private val dark: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name="{0}x{1}_dark={2}") fun sizes() =
            listOf(360 to 800,840 to 800,1280 to 720).flatMap { (w,h) -> listOf(false,true).map { arrayOf<Any>(w,h,it) } }
    }
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { DashboardUiFixture() }
    @Test fun dashboardTop() = compose.shoot("dashboard-round7","DashboardTop",width,height,dark,"ko") {
        fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
            AccountDashboardContent(fixture.data,fixture.names,"샘플 별명",{},{},userId="synthetic")
        } }
    }
    @Test fun servantCollection() = compose.shoot("dashboard-round7","ServantCollection",width,height,dark,"ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("샘플 서번트 1", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dashboard-servant-list").performScrollTo()
    }) {
        fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
            AccountDashboardContent(fixture.data,fixture.names,"샘플 별명",{},{},initialSection=3,userId="synthetic")
        } }
    }
    @Test fun exportDialog() {
        installFakeChaldea(fixture.context)
        compose.shoot("dashboard-round7","ExportDialog",width,height,dark,"ko") {
            ChaldeaExportResultDialog(ChaldeaExportStore.Result.Written("chaldea-kr-sample-20261106-1200.json")) {}
        }
    }
}
