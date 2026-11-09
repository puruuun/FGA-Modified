package io.github.fate_grand_automata.ui.battle_config_item

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.BattleConfigFile
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CommandStartThirdTargetTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun config(id: String) = BattleConfigCore(id, context).apply {
        commandStartMode.set(CommandStartMode.CardCount)
    }

    private fun renderGroup(config: BattleConfigCore) {
        RuntimeEnvironment.setQualifiers("w600dp-h2400dp-hdpi")
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CommandStartGroup(config)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun shown(text: String) =
        composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun thirdTargetDefaultsAreOffServantThreeWithSecondTargetColourDefaults() {
        val config = config("third_defaults")
        assertFalse(config.tertiaryStartEnabled.get())
        assertEquals(3, config.tertiaryStartServant.get())
        assertEquals(2, config.tertiaryStartCardCount.get())
        assertEquals(config.secondaryStartBuster.get(), config.tertiaryStartBuster.get())
        assertEquals(config.secondaryStartArts.get(), config.tertiaryStartArts.get())
        assertEquals(config.secondaryStartQuick.get(), config.tertiaryStartQuick.get())
        assertEquals(config.secondaryStartCombineMode.get(), config.tertiaryStartCombineMode.get())
        assertEquals(config.tertiaryExecution, config.executionProfile(2))
    }

    @Test
    fun oldExportsImportWithTheThirdTargetOff() {
        val config = config("third_old_export")
        val old = "{\"command_start_mode\":\"CardCount\",\"secondary_start_enabled\":true,\"secondary_start_card_count\":3}"
        config.import(BattleConfigFile.decode(old))
        assertEquals(true, config.secondaryStartEnabled.get())
        assertEquals(3, config.secondaryStartCardCount.get())
        assertFalse(config.tertiaryStartEnabled.get())
        assertEquals("", config.tertiaryExecution.skillCommand.get())
    }

    @Test
    fun thirdTargetSurvivesExportAndImport() {
        val source = config("third_source").apply {
            secondaryStartEnabled.set(true)
            tertiaryStartEnabled.set(true)
            tertiaryStartServant.set(1)
            tertiaryStartCardCount.set(4)
            tertiaryStartBuster.set(2)
            tertiaryStartCombineMode.set(CommandStartCombineMode.Or)
            tertiaryExecution.skillCommand.set("a,b")
        }
        val copy = config("third_copy")
        copy.import(BattleConfigFile.decode(BattleConfigFile.encode(source.export())))
        assertEquals(true, copy.tertiaryStartEnabled.get())
        assertEquals(1, copy.tertiaryStartServant.get())
        assertEquals(4, copy.tertiaryStartCardCount.get())
        assertEquals(2, copy.tertiaryStartBuster.get())
        assertEquals(CommandStartCombineMode.Or, copy.tertiaryStartCombineMode.get())
        assertEquals("a,b", copy.tertiaryExecution.skillCommand.get())
        // The first two profiles are untouched.
        assertEquals("", copy.execution.skillCommand.get())
        assertEquals("", copy.secondaryExecution.skillCommand.get())
    }

    @Test
    fun thirdTargetRowIsHiddenWhileTheSecondTargetIsOff() {
        renderGroup(config("third_row_off"))
        assertFalse(shown(context.getString(R.string.command_start_third_servant)))
    }

    @Test
    fun thirdTargetRowAppearsWithTheSecondTargetOn() {
        val config = config("third_row_on").apply { secondaryStartEnabled.set(true) }
        renderGroup(config)
        composeRule.onNodeWithText(context.getString(R.string.command_start_third_servant)).assertIsDisplayed()
    }

    @Test
    fun duplicateWarningCoversTheThirdTarget() {
        val config = config("third_duplicate").apply {
            secondaryStartEnabled.set(true)
            secondaryStartServant.set(2)
            commandStartServant.set(1)
            tertiaryStartEnabled.set(true)
            tertiaryStartServant.set(2)
        }
        renderGroup(config)
        composeRule.onNodeWithText(context.getString(R.string.command_start_duplicate_targets_all)).assertIsDisplayed()
    }

    @Test
    fun command3SectionShowsOnlyWithTheThirdTargetOn() {
        val label = context.getString(R.string.command_start_edit_command, 3)
        val config = config("third_section").apply { secondaryStartEnabled.set(true) }
        RuntimeEnvironment.setQualifiers("w600dp-h2400dp-hdpi")
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                BattleConfigContent(config, "", {}, {}, {}, {})
            }
        }
        composeRule.waitForIdle()
        assertFalse(shown(label))

        config.tertiaryStartEnabled.set(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(label).assertIsDisplayed()
    }
}
