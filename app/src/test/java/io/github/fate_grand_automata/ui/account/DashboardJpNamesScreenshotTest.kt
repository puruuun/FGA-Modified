package io.github.fate_grand_automata.ui.account

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.log.LocalLogIconLoader
import io.github.fate_grand_automata.ui.log.localIconLoader
import io.github.fate_grand_automata.ui.log.localIconNames
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals

/** Real dashboard rendered from synthetic account and Atlas lists, with generated local images. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardJpNamesScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    @get:Rule val temporary = TemporaryFolder()

    private fun dashboard(width: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = runBlocking {
            GameNames(temporary.root, JpNamesFixture::download) { 1000 }
                .dashboard(listOf(JpNamesFixture.state), hasJpAccount = true)
        }
        val data = dashboardData(JpNamesFixture.state, names)
        assertEquals(2, data.storage?.servants)
        assertEquals(2, data.storage?.equips)
        assertEquals("한국 예장", names.equip(30))
        assertEquals("日本の礼装", names.equip(40))
        assertEquals(JpNamesFixture.icon("JP", 40), names.equipData[40]?.icon)
        localIconNames(context)
        listOf(10, 20, 30, 40).forEach { id ->
            val file = File(context.filesDir, "capture/icons/servants/$id.png").apply { parentFile!!.mkdirs() }
            File(context.filesDir, "capture/icons/6510.png").copyTo(file, overwrite = true)
        }
        val loader = localIconLoader(context)
        compose.shoot("issue-243-names", "DashboardKrJp", width, if (width == 360) 1100 else 720, locale = "ko",
            beforeCapture = {
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("日本の騎士", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("JP").assertIsDisplayed()
                compose.onNodeWithText("한국 기사", substring = true).assertIsDisplayed()
                compose.onNodeWithText("日本の騎士", substring = true).assertIsDisplayed()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().size == 2
                }
            }) {
            CompositionLocalProvider(LocalNavigateBack provides {}, LocalLogIconLoader provides loader) {
                AccountDashboardContent(data, names, "합성 JP 계정", {}, {}, userId = "7002", server = "JP")
            }
        }
    }

    @Test fun phone() = dashboard(360)
    @Test fun tablet() = dashboard(1280)
}
