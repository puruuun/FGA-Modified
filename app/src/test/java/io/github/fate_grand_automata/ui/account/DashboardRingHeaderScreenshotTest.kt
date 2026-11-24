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

/** After shots of #625 in the light theme: the ring header, wide and narrow, recovering and full, and the wide Overview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DashboardRingHeaderScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private val fixture by lazy { Round615Fixture() }

    private fun game(recoverAt: Long, carry: Int) = fixture.data().copy(
        game = Json.parseToJsonElement("""{"actMax":147,"actRecoverAt":$recoverAt,"carryOverActPoint":$carry}""").jsonObject,
    )

    private fun shot(name: String, width: Int, height: Int, data: DashboardData, displayName: String? = "샘플 별명") =
        compose.shoot("dashboard-625", name, width, height, false, "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard-section:3").fetchSemanticsNodes().isEmpty() }
        }) {
            fixture.case.fixture.Icons { CompositionLocalProvider(LocalNavigateBack provides {}) {
                AccountDashboardContent(data, fixture.names, displayName, {}, {}, battle = battleLogFixture("recent"), userId = "synthetic",
                    friendCount = 24, progress = fixture.progress)
            } }
        }

    private val soon get() = System.currentTimeMillis() / 1000 + 5 * 3600

    @Test fun headerWideRecovering() = shot("HeaderWideRecovering", 1280, 800, game(soon, 12))
    @Test fun headerWideFull() = shot("HeaderWideFull", 1280, 800, game(1, 12))
    @Test fun headerWideShortName() = shot("HeaderWideShortName", 1280, 800, game(soon, 12), "샘플")
    @Test fun headerWideTwelveCharacterName() = shot("HeaderWideTwelveCharacterName", 1280, 800,
        game(soon, 12).let { it.copy(profile = it.profile + ("name" to "가나다라마바사아자차카타")) }, null)
    @Test fun headerNarrowRecovering() = shot("HeaderNarrowRecovering", 360, 800, game(soon, 12))
    @Test fun headerNarrowFull() = shot("HeaderNarrowFull", 360, 800, game(1, 12))
    @Test fun overviewWide() = shot("OverviewWide", 1280, 1400, game(soon, 12))
}
