package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.battlelog.gainsFixture
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalNavigateBack
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.screenshotRules
import java.text.DateFormat
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class CaptureAccountsUiTest {
    private val composeRule = createComposeRule()
    @get:Rule val rules = screenshotRules(composeRule)
    private val account = captureAccountFixture(name = "Farming")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun blankRenameIsSubmittedForTheCorrectAccount() {
        var renamed: Pair<String, String>? = null
        composeRule.setContent { FGATheme(darkTheme = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CaptureAccountCard(account, true, { id, name -> renamed = id to name }, {})
            }
        } }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        composeRule.onNodeWithText("Farming  Lv.120").assertIsDisplayed()
        composeRule.onNodeWithTag("capture-account-server:${account.id}").assertIsDisplayed().assertTextEquals("KR")
        composeRule.onNodeWithText(context.getString(R.string.capture_account_current)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.capture_snapshot_time, format.format(Date(account.summary.snapshotTime * 1000))),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.capture_last_update, format.format(Date(account.summary.lastUpdateTime * 1000))),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.capture_chaldea_create)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.capture_account_delete)).assertExists()
        listOf(
            "AP",
            context.getString(R.string.capture_quartz),
            context.getString(R.string.capture_fragments),
            context.getString(R.string.capture_apples_gold),
            context.getString(R.string.capture_apples_silver),
            context.getString(R.string.capture_apples_bronze),
            context.getString(R.string.capture_apples_copper),
            "Bronze Sapling",
            "Summon Tickets",
            context.getString(R.string.capture_mana_prism),
            context.getString(R.string.capture_rare_prism),
            context.getString(R.string.capture_qp),
        ).forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        composeRule.onNodeWithText(context.getString(R.string.capture_account_rename)).performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        assertEquals(account.id to "", renamed)
    }

    @Test fun accountCardShowsKrAndJpServersNextToNameAndHidesNullServer() {
        val krAccount = captureAccountFixture(id = "7001", name = "KR Master").copy(server = "KR")
        val jpAccount = captureAccountFixture(id = "7002", name = "JP Master").copy(server = "JP")
        composeRule.setContent { FGATheme(darkTheme = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CaptureAccountCard(krAccount, false, { _, _ -> }, {})
                CaptureAccountCard(jpAccount, false, { _, _ -> }, {})
                CaptureSummaryCard(account.summary, "No Server", server = null)
            }
        } }
        val krName = composeRule.onNodeWithText("KR Master  Lv.120").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val krServer = composeRule.onNodeWithTag("capture-account-server:7001").assertIsDisplayed().assertTextEquals("KR")
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("capture-account-server:7001", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
        assertTrue(krServer.left >= krName.right)
        assertTrue(krServer.top < krName.bottom && krServer.bottom > krName.top)

        val jpName = composeRule.onNodeWithText("JP Master  Lv.120").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val jpServer = composeRule.onNodeWithTag("capture-account-server:7002").assertIsDisplayed().assertTextEquals("JP")
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("capture-account-server:7002", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
        assertTrue(jpServer.left >= jpName.right)
        assertTrue(jpServer.top < jpName.bottom && jpServer.bottom > jpName.top)

        composeRule.onNodeWithTag("capture-account-server").assertDoesNotExist()
    }

    @Test fun deletingRequiresConfirmationNamingTheAccountAndCancelKeepsIt() {
        var deleted: String? = null
        composeRule.setContent { FGATheme(darkTheme = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CaptureAccountCard(account, false, { _, _ -> }, { deleted = it })
            }
        } }
        val delete = context.getString(R.string.capture_account_delete)
        composeRule.onNodeWithText(delete).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.capture_account_delete_message, account.displayName)).assertIsDisplayed()
        assertNull(deleted)
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertNull(deleted)
        composeRule.onNodeWithText(delete).performScrollTo().performClick()
        composeRule.onAllNodesWithText(delete).filter(hasClickAction()).onLast().performClick()
        assertEquals(account.id, deleted)
    }

    @Test fun namedListCardAndDetailBothShowTheHistoricalName() {
        val entry = battleLogFixture().copy(gains = gainsFixture(), accountId = "7001", accountName = "Farming")
        composeRule.setContent { FGATheme(darkTheme = false) {
            CompositionLocalProvider(LocalNavigateBack provides {}) { LogScreenContent(listOf(entry), {}, {}) }
        } }
        composeRule.onNodeWithText("Farming").assertIsDisplayed()
        composeRule.onNodeWithText(entry.configName).performClick()
        composeRule.onNodeWithText("Farming").assertIsDisplayed()
    }
}
