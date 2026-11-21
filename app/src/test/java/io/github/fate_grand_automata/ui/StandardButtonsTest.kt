package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.ui.battle_config_item.PartyItemTagPrefix
import io.github.fate_grand_automata.ui.battle_config_item.PartySelectionDialogContent
import io.github.fate_grand_automata.ui.battle_config_item.RaidDelay
import io.github.fate_grand_automata.ui.battle_config_item.RaidDelayToggleTagPrefix
import io.github.fate_grand_automata.ui.battle_config_item.SupportClassPicker
import io.github.fate_grand_automata.ui.battle_config_item.SupportClassTagPrefix
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListAction
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.battle_config_list.ConfigCheckboxTagPrefix
import io.github.fate_grand_automata.ui.battle_config_list.ServerHeaderTagPrefix
import io.github.fate_grand_automata.ui.card_priority.CardPriorityScreen
import io.github.fate_grand_automata.ui.card_priority.CardPriorityViewModel
import io.github.fate_grand_automata.ui.card_priority.WaveAddTag
import io.github.fate_grand_automata.ui.card_priority.WaveRemoveTag
import io.github.fate_grand_automata.ui.card_priority.WaveTabTagPrefix
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.dialog.multiChoiceList
import io.github.fate_grand_automata.ui.dialog.singleChoiceList
import io.github.fate_grand_automata.ui.main.MainBuildStatusRow
import io.github.fate_grand_automata.ui.pref_support.MaxSkillTagPrefix
import io.github.fate_grand_automata.ui.pref_support.MaxSkills
import io.github.fate_grand_automata.ui.prefs.MultiSelectChip
import io.github.fate_grand_automata.ui.prefs.SingleSelectChip
import io.github.fate_grand_automata.ui.spam.SpamScreen
import io.github.fate_grand_automata.ui.spam.SpamScreenViewModel
import io.github.fate_grand_automata.ui.spam.SpamServantTabTagPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each control that #596 turned into a standard Material 3 component still drives the same state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "w360dp-h800dp-hdpi")
class StandardButtonsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefsCore(name: String): PrefsCore {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context)
    }

    private fun SemanticsNodeInteraction.assertRole(role: Role) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))

    /**
     * [layout] checks the drawn node, for controls this PR sizes itself. Material controls pad a small node out to
     * 48dp for touch and report that padding only in the touch bounds, so they are checked there.
     */
    private fun SemanticsNodeInteraction.assertTouchTarget(
        min: Dp = 48.dp,
        minWidth: Dp = min,
        layout: Boolean = false
    ): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val bounds = if (layout) node.boundsInRoot else node.touchBoundsInRoot
        val density = node.layoutInfo.density.density
        val tolerance = 0.5f
        assertTrue("width ${bounds.width / density} < $minWidth", bounds.width / density + tolerance >= minWidth.value)
        assertTrue("height ${bounds.height / density} < $min", bounds.height / density + tolerance >= min.value)
        return this
    }

    @Test
    fun `heading button is a 40dp button that clicks and the danger one is still a button`() {
        var clicks = 0
        compose.setContent {
            FGATheme {
                Column {
                    HeadingButton(text = "Export", onClick = { clicks++ }, icon = icon(Icons.Default.FileUpload))
                    HeadingButton(text = "Delete", onClick = { clicks += 10 }, isDanger = true)
                }
            }
        }

        compose.onNodeWithText("EXPORT").assertRole(Role.Button).performClick()
        compose.onNodeWithText("DELETE").assertRole(Role.Button).performClick()
        assertEquals(11, clicks)
        compose.onNodeWithText("EXPORT").assertTouchTarget(40.dp, layout = true)
    }

    @Test
    fun `build badge opens the build info and is a button`() {
        var opened = 0
        compose.setContent { FGATheme { MainBuildStatusRow(false, false, { opened++ }, {}) } }

        compose.onNodeWithTag("buildBadge").onChildren().onFirst().assertRole(Role.Button).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `selecting wave 2 tab moves the pager and the add and remove buttons keep their limits`() {
        val vm = CardPriorityViewModel(prefsCore("buttons596_wave"), "cfg_wave", profileIndex = 0)
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        val before = vm.cardPriorityItems.size
        compose.onNodeWithTag(WaveAddTag).assertRole(Role.Button).performClick()
        compose.onNodeWithTag(WaveAddTag).performClick()
        compose.waitForIdle()
        assertEquals(before + 2, vm.cardPriorityItems.size)

        compose.onNodeWithTag("${WaveTabTagPrefix}0").assertRole(Role.Tab).assertIsSelected()
        compose.onNodeWithTag("${WaveTabTagPrefix}1").assertRole(Role.Tab).assertIsNotSelected().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("${WaveTabTagPrefix}1").assertIsSelected()
        compose.onNodeWithTag("${WaveTabTagPrefix}0").assertIsNotSelected()

        compose.onNodeWithTag(WaveRemoveTag).assertRole(Role.Button).performClick()
        compose.waitForIdle()
        assertEquals(before + 1, vm.cardPriorityItems.size)
    }

    @Test
    fun `wave add stops at the entry limit`() {
        val vm = CardPriorityViewModel(prefsCore("buttons596_wave_max"), "cfg_wave_max", profileIndex = 0)
        compose.setContent { FGATheme { CardPriorityScreen(vm = vm) } }

        repeat(12) {
            if (compose.onAllNodesWithTagCount(WaveAddTag) > 0) compose.onNodeWithTag(WaveAddTag).performClick()
            compose.waitForIdle()
        }
        assertEquals(io.github.fate_grand_automata.ui.card_priority.CARD_PRIORITY_MAX_ENTRIES, vm.cardPriorityItems.size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun `spam servant tab switches the pager`() {
        val prefs = prefsCore("buttons596_spam")
        val vm = SpamScreenViewModel(PreferencesImpl(prefs, CompletedRunsHolder()), prefs, "cfg_spam")
        compose.setContent { FGATheme { SpamScreen(vm) } }

        compose.onNodeWithTag("${SpamServantTabTagPrefix}0").assertRole(Role.Tab).assertIsSelected()
        compose.onNodeWithTag("${SpamServantTabTagPrefix}2").assertIsNotSelected().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("${SpamServantTabTagPrefix}2").assertIsSelected()
        compose.onNodeWithTag("${SpamServantTabTagPrefix}0").assertIsNotSelected()
    }

    @Test
    fun `choosing party 3 reports party index 2 and each circle is a 48dp radio`() {
        var chosen = -1
        compose.setContent { FGATheme { PartySelectionDialogContent(selected = 0, onSelectedChange = { chosen = it }) } }

        compose.onNodeWithTag("${PartyItemTagPrefix}1").assertRole(Role.RadioButton).assertIsSelected()
        compose.onNodeWithTag("${PartyItemTagPrefix}3").assertRole(Role.RadioButton).assertIsNotSelected()
            .assertTouchTarget(layout = true).performClick()
        assertEquals(2, chosen)
    }

    @Test
    fun `single select chip picks the clicked entry and the selected chip is selected`() {
        val selected = mutableStateOf(2)
        compose.setContent {
            FGATheme {
                SingleSelectChip(
                    title = "Single", selected = selected.value, onSelectedChange = { selected.value = it },
                    entries = mapOf(1 to "One", 2 to "Two", 3 to "Three")
                )
            }
        }

        compose.onNodeWithText("Two").assertIsSelected()
        compose.onNodeWithText("One").assertRole(Role.Checkbox).assertIsNotSelected().assertTouchTarget(48.dp).performClick()
        assertEquals(1, selected.value)
        compose.onNodeWithText("One").assertIsSelected()
        compose.onNodeWithText("Two").assertIsNotSelected()
    }

    @Test
    fun `multi select chip toggles membership`() {
        val selected = mutableStateOf(setOf(1))
        compose.setContent {
            FGATheme {
                MultiSelectChip(
                    title = "Multi", selected = selected.value, onSelectedChange = { selected.value = it },
                    entries = mapOf(1 to "One", 2 to "Two")
                )
            }
        }

        compose.onNodeWithText("Two").performClick()
        assertEquals(setOf(1, 2), selected.value)
        compose.onNodeWithText("One").performClick()
        assertEquals(setOf(2), selected.value)
    }

    @Test
    fun `raid delay off is saved to the preference`() {
        val config = BattleConfigCore("buttons596_raid", context)
        config.addRaidTurnDelay.set(true)
        compose.setContent { FGATheme { RaidDelay(config = config) } }

        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("${RaidDelayToggleTagPrefix}true").assertRole(Role.RadioButton).assertIsSelected()
        compose.onNodeWithTag("${RaidDelayToggleTagPrefix}false").assertIsNotSelected().performClick()
        compose.onNodeWithTag("${RaidDelayToggleTagPrefix}false").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.save).uppercase()).performClick()

        assertFalse(config.addRaidTurnDelay.get())
    }

    @Test
    fun `raid delay on is saved to the preference`() {
        val config = BattleConfigCore("buttons596_raid_on", context)
        config.addRaidTurnDelay.set(false)
        compose.setContent { FGATheme { RaidDelay(config = config) } }

        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithTag("${RaidDelayToggleTagPrefix}true").performClick()
        compose.onNodeWithText(context.getString(R.string.save).uppercase()).performClick()

        assertTrue(config.addRaidTurnDelay.get())
    }

    @Test
    fun `single choice dialog row selects the clicked item and is a radio button`() {
        var chosen = "b"
        compose.setContent {
            FGATheme {
                FgaDialog(true).build {
                    singleChoiceList(selected = chosen, items = listOf("alpha", "bravo", "charlie"), onSelectedChange = { chosen = it })
                }
            }
        }

        compose.onNodeWithText("bravo").assertRole(Role.RadioButton)
        compose.onNodeWithText("charlie").assertIsNotSelected().assertTouchTarget(layout = true).performClick()
        assertEquals("charlie", chosen)
    }

    @Test
    fun `multi choice dialog row toggles the clicked item and is a checkbox`() {
        val selected = mutableStateOf(setOf("alpha"))
        compose.setContent {
            FGATheme {
                FgaDialog(true).build {
                    multiChoiceList(selected = selected.value, items = listOf("alpha", "bravo"), onSelectedChange = { selected.value = it })
                }
            }
        }

        compose.onNodeWithText("bravo").assertRole(Role.Checkbox).performClick()
        assertEquals(setOf("alpha", "bravo"), selected.value)
        compose.onNodeWithText("alpha").performClick()
        assertEquals(setOf("bravo"), selected.value)
    }

    @Test
    fun `support max box toggles its preference`() {
        val config = BattleConfigCore("buttons596_max", context)
        config.support.skill1Max.set(false)
        compose.setContent {
            FGATheme { MaxSkills(listOf(config.support.skill1Max, config.support.skill2Max, config.support.skill3Max)) }
        }

        compose.onNodeWithTag("${MaxSkillTagPrefix}0").assertRole(Role.Checkbox).assertIsNotSelected().assertTouchTarget(48.dp).performClick()
        assertTrue(config.support.skill1Max.get())
        compose.onNodeWithTag("${MaxSkillTagPrefix}0").assertIsSelected().performClick()
        assertFalse(config.support.skill1Max.get())
    }

    @Test
    fun `support class picker selects a class and clears it when clicked again`() {
        val selected = mutableStateOf(SupportClass.None)
        compose.setContent {
            FGATheme { SupportClassPicker(selected = selected.value, onSelectedChange = { selected.value = it }) }
        }

        compose.onNodeWithTag("${SupportClassTagPrefix}Saber").assertRole(Role.RadioButton).assertIsNotSelected()
            .assertTouchTarget(layout = true).performClick()
        assertEquals(SupportClass.Saber, selected.value)
        compose.onNodeWithTag("${SupportClassTagPrefix}Saber").assertIsSelected().performClick()
        assertEquals(SupportClass.None, selected.value)
    }

    @Test
    fun `selection mode checkbox toggles that config and the server header keeps its tag`() {
        val configs = listOf("Alpha" to GameServer.Jp.Original, "Beta" to GameServer.Jp.Original, "Gamma" to GameServer.Kr).mapIndexed { i, (name, server) ->
            BattleConfigCore("buttons596_cfg_$i", context).apply {
                this.name.set(name)
                this.server.set(BattleConfigCore.Server.Set(server))
            }
        }
        val actions = mutableListOf<BattleConfigListAction>()
        compose.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs, selectionMode = true, selectedConfigs = setOf(configs[0].id),
                    expandedFolders = emptySet(), folderRevision = 0, action = { actions += it }
                )
            }
        }

        compose.onNodeWithTag("${ServerHeaderTagPrefix}${GameServer.Jp.Original.serialize()}").assertExists()
        compose.onNodeWithTag("${ConfigCheckboxTagPrefix}Alpha").assertRole(Role.Checkbox).assertTouchTarget(48.dp)
        compose.onNodeWithTag("${ConfigCheckboxTagPrefix}Beta").performClick()

        val toggled = actions.filterIsInstance<BattleConfigListAction.ToggleSelected>()
        assertEquals(listOf(configs[1].id), toggled.map { it.id })
    }
}
