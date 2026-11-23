package io.github.fate_grand_automata.ui.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Light-theme shots of the #648 header: wide with a short name and a 12-character name, and narrow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardHeaderLayoutCScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { Round615Fixture() }

    private fun shot(name: String, accountName: String, width: Int = 1280) {
        val recoverAt = System.currentTimeMillis() / 1000 + 5 * 3600
        val data = fixture.data().copy(
            profile = fixture.data().profile + ("name" to accountName),
            game = Json.parseToJsonElement("""{"actMax":147,"actRecoverAt":$recoverAt,"carryOverActPoint":12}""").jsonObject,
        )
        compose.shoot("dashboard-648", name, width, 800, false, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard-section:3").fetchSemanticsNodes().isEmpty() }
        }) {
            fixture.case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(data, fixture.names, null, {}, {}, battle = battleLogFixture("recent"), userId = "synthetic",
                    friendCount = 24, progress = fixture.progress)
            } }
        }
    }

    @Test fun headerWideShortName() = shot("HeaderWideShortName", "샘플")
    @Test fun headerWideTwelveCharacterName() = shot("HeaderWideTwelveCharacterName", "가나다라마바사아자차카타")
    @Test fun headerNarrow() = shot("HeaderNarrow", "샘플", width = 360)
}
