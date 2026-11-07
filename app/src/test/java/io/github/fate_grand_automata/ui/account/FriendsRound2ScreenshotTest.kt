package io.github.fate_grand_automata.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.ui.*
import io.github.fate_grand_automata.ui.more.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "ko-w360dp-h800dp-hdpi")
class FriendsRound2ScreenshotTest {
    private val compose = createComposeRule()
    @get:Rule val rules = screenshotRules(compose)
    private fun content() {
        val roster = longNameRoster()
        val accounts = listOf(FriendManagerAccount(CaptureAccount("sample", "샘플 마스터",
            AccountSummary.from(dashboardState("userGame" to """[{"lv":160}]"""), FRIEND_NOW), FRIEND_NOW), roster))
        compose.shoot("friends-round2", "TableExpanded", 360, 800, locale = "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("friends-row")[0].performClick()
        }) { FriendManagerContent(null, friendTestNames(), FRIEND_NOW, accounts, "sample") }
    }
    @Test fun friendTableExpanded360Light() = content()

    @Test fun filterSheet360Light() = compose.shoot("friends-round2", "FilterSheet", 360, 800, locale = "ko", beforeCapture = {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("friends-row").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("friends-filter").performClick()
        compose.onNodeWithTag("friends-login-inactive").performScrollTo().performClick()
    }) { FriendManagerContent(syntheticRoster(), friendTestNames(), FRIEND_NOW) }

    private fun servants(expanded: Boolean) {
        val fixture = DashboardUiFixture()
        val data = fixture.data.copy(servants = fixture.data.servants!!.map { it.copy(level = 100, np = 5, skills = listOf(10, 10, 9),
            appends = listOf(10, 1, null, null, 5)) })
        compose.shoot("friends-round2", if (expanded) "ServantsExpanded" else "ServantsCollapsed", 360, 800, locale = "ko", beforeCapture = {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-servant-row").fetchSemanticsNodes().isNotEmpty() }
            if (expanded) compose.onNodeWithTag("dashboard-servants-toggle").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("dashboard-icon-ready", useUnmergedTree = true).fetchSemanticsNodes().size >= if (expanded) 10 else 5 }
        }) { fixture.Icons { Card(Modifier.fillMaxWidth().padding(8.dp)) { Column(Modifier.padding(12.dp)) { Servants(data) } } } }
    }
    @Test fun servantsCollapsed360Light() = servants(false)
    @Test fun servantsExpanded360Light() = servants(true)
}
