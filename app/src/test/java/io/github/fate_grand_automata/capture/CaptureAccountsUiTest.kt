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
        composeRule.onNodeWithText(context.getString(R.string.capture_account_current)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.capture_account_rename)).performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        assertEquals(account.id to "", renamed)
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
