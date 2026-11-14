package io.github.fate_grand_automata.ui.battle_config_list

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w900dp-h600dp")
class BattleConfigListScreenTest {
    private val composeRule = createComposeRule()
    @get:Rule
    val rules = screenshotRules(composeRule)

    private fun makeConfig(
        id: String,
        name: String,
        folder: String = "",
        server: GameServer? = null
    ): BattleConfigCore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return BattleConfigCore(id, context).apply {
            this.name.set(name)
            this.folder.set(folder)
            this.server.set(server?.let { BattleConfigCore.Server.Set(it) } ?: BattleConfigCore.Server.NotSet)
        }
    }

    private fun assertDpClose(actual: Dp, expected: Dp, tolerance: Dp = 1.dp) {
        assertThat(actual.value).isBetween(expected.value - tolerance.value, expected.value + tolerance.value)
    }

    @Test
    fun `two columns at 900dp lay out first two configs side by side and third on next row`() {
        val configs = listOf(
            makeConfig("c1", "Alpha"),
            makeConfig("c2", "Beta"),
            makeConfig("c3", "Gamma")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val b1 = composeRule.onNodeWithText("Alpha").onParent().getBoundsInRoot()
        val b2 = composeRule.onNodeWithText("Beta").onParent().getBoundsInRoot()
        val b3 = composeRule.onNodeWithText("Gamma").onParent().getBoundsInRoot()

        assertDpClose(b1.top, b2.top)
        assertThat(b2.left).isGreaterThan(b1.right)
        assertThat(b3.top).isGreaterThan(b1.bottom)
        assertDpClose(b3.left, b1.left)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one column at 400dp stacks configs vertically`() {
        val configs = listOf(
            makeConfig("c1", "Alpha"),
            makeConfig("c2", "Beta"),
            makeConfig("c3", "Gamma")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val b1 = composeRule.onNodeWithText("Alpha").onParent().getBoundsInRoot()
        val b2 = composeRule.onNodeWithText("Beta").onParent().getBoundsInRoot()
        val b3 = composeRule.onNodeWithText("Gamma").onParent().getBoundsInRoot()

        assertDpClose(b1.left, b2.left)
        assertDpClose(b2.left, b3.left)
        assertThat(b2.top).isGreaterThan(b1.bottom)
        assertThat(b3.top).isGreaterThan(b2.bottom)
        assertDpClose(b1.left, 16.dp)
        assertDpClose(400.dp - b1.right, 16.dp)
    }

    @Test
    fun `side padding is 16dp and column gap is 12dp at 900dp`() {
        val configs = listOf(
            makeConfig("c1", "Alpha"),
            makeConfig("c2", "Beta")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val gridBounds = composeRule.onNodeWithTag(ConfigGridTag).getBoundsInRoot()
        val leftCard = composeRule.onNodeWithText("Alpha").onParent().getBoundsInRoot()
        val rightCard = composeRule.onNodeWithText("Beta").onParent().getBoundsInRoot()

        assertDpClose(leftCard.left - gridBounds.left, 16.dp)
        assertDpClose(gridBounds.right - rightCard.right, 16.dp)
        assertDpClose(rightCard.left - leftCard.right, 12.dp)
    }

    @Test
    fun `server headers are full bleed, aligned with item text, and folder rows span full width at 900dp`() {
        val configs = listOf(
            makeConfig("j1", "JpFolderItem", folder = "F1", server = GameServer.Jp.Original),
            makeConfig("j2", "JpUnfiled", server = GameServer.Jp.Original),
            makeConfig("k1", "KrUnfiled", server = GameServer.Kr)
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val gridBounds = composeRule.onNodeWithTag(ConfigGridTag).getBoundsInRoot()
        val jpHeader = composeRule.onNodeWithTag("${ServerHeaderTagPrefix}${GameServer.Jp.Original.serialize()}")
            .getBoundsInRoot()
        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}f1").getBoundsInRoot()
        val folderBounds = composeRule.onNodeWithText("F1").getBoundsInRoot()
        val jpItemBounds = composeRule.onNodeWithText("JpUnfiled").getBoundsInRoot()

        // Header band is full-bleed across the grid width (0..900dp)
        assertDpClose(jpHeader.left, gridBounds.left)
        assertDpClose(jpHeader.right, gridBounds.right)

        // Header text starts on the same horizontal line as item text below it
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jpLabel = context.getString(R.string.game_server_jp)
        val headerTextBounds = composeRule
            .onNodeWithTag("${ServerHeaderTagPrefix}${GameServer.Jp.Original.serialize()}")
            .onChildren()
            .filterToOne(hasText(jpLabel))
            .getBoundsInRoot()
        val itemTextBounds = composeRule.onNodeWithText("JpUnfiled", useUnmergedTree = true).getBoundsInRoot()
        assertDpClose(headerTextBounds.left, itemTextBounds.left)

        // Folder box spans full content width (across both columns), with 8dp padding around the folder row
        assertDpClose(boxBounds.left - gridBounds.left, 16.dp)
        assertDpClose(gridBounds.right - boxBounds.right, 16.dp)
        assertDpClose(folderBounds.left - boxBounds.left, 8.dp)
        assertDpClose(boxBounds.right - folderBounds.right, 8.dp)
        assertThat(folderBounds.width).isGreaterThan(jpItemBounds.width)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `server headers are full bleed and folder rows span full width at 400dp`() {
        val configs = listOf(
            makeConfig("j1", "JpFolderItem", folder = "F1", server = GameServer.Jp.Original),
            makeConfig("j2", "JpUnfiled", server = GameServer.Jp.Original),
            makeConfig("k1", "KrUnfiled", server = GameServer.Kr)
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val gridBounds = composeRule.onNodeWithTag(ConfigGridTag).getBoundsInRoot()
        val jpHeader = composeRule.onNodeWithTag("${ServerHeaderTagPrefix}${GameServer.Jp.Original.serialize()}")
            .getBoundsInRoot()
        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}f1").getBoundsInRoot()
        val folderBounds = composeRule.onNodeWithText("F1").getBoundsInRoot()

        assertDpClose(jpHeader.left, gridBounds.left)
        assertDpClose(jpHeader.right, gridBounds.right)
        assertDpClose(boxBounds.left - gridBounds.left, 16.dp)
        assertDpClose(gridBounds.right - boxBounds.right, 16.dp)
        assertDpClose(folderBounds.left - boxBounds.left, 8.dp)
        assertDpClose(boxBounds.right - folderBounds.right, 8.dp)
    }

    @Test
    fun `expanded folder items lay out in two columns without indent at 900dp`() {
        val configs = listOf(
            makeConfig("f1", "FirstInFolder", folder = "Lancer"),
            makeConfig("f2", "SecondInFolder", folder = "Lancer"),
            makeConfig("f3", "ThirdInFolder", folder = "Lancer"),
            makeConfig("u1", "UnfiledItem")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = setOf("lancer"),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer").getBoundsInRoot()
        val b1 = composeRule.onNodeWithText("FirstInFolder").onParent().getBoundsInRoot()
        val b2 = composeRule.onNodeWithText("SecondInFolder").onParent().getBoundsInRoot()
        val b3 = composeRule.onNodeWithText("ThirdInFolder").onParent().getBoundsInRoot()
        val u1 = composeRule.onNodeWithText("UnfiledItem").onParent().getBoundsInRoot()

        // First two folder items are side by side inside the 8dp-padded box; odd third item takes one cell width
        assertDpClose(b1.top, b2.top)
        assertDpClose(b2.left - b1.right, 12.dp)
        assertDpClose(b1.left - boxBounds.left, 8.dp)
        assertDpClose(boxBounds.right - b2.right, 8.dp)
        assertThat(b3.top).isGreaterThan(b1.bottom)
        assertDpClose(b3.width, b1.width)

        // Unfiled item starts below the folder box on a fresh row
        assertThat(u1.top).isGreaterThan(boxBounds.bottom)
        assertDpClose(u1.left, boxBounds.left)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `expanded folder items sit inside 8dp box padding without extra indent in one-column mode`() {
        val configs = listOf(
            makeConfig("f1", "FirstInFolder", folder = "Lancer"),
            makeConfig("u1", "UnfiledItem")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = setOf("lancer"),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer").getBoundsInRoot()
        val folderItem = composeRule.onNodeWithText("FirstInFolder").onParent().getBoundsInRoot()
        val unfiledItem = composeRule.onNodeWithText("UnfiledItem").onParent().getBoundsInRoot()

        assertDpClose(boxBounds.left, unfiledItem.left)
        assertDpClose(folderItem.left - boxBounds.left, 8.dp)
        assertDpClose(folderItem.left - unfiledItem.left, 8.dp)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tap opens edit, long-press starts selection mode, and toggling folder works at 900dp`() {
        val configs = listOf(
            makeConfig("f1", "FolderConfig", folder = "Farm"),
            makeConfig("u1", "SoloConfig")
        )
        val edited = mutableListOf<String>()

        composeRule.setContent {
            var selectionMode by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(emptySet<String>()) }
            var expanded by remember { mutableStateOf(emptySet<String>()) }

            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = selectionMode,
                    selectedConfigs = selected,
                    expandedFolders = expanded,
                    folderRevision = 0,
                    action = { action ->
                        when (action) {
                            is BattleConfigListAction.Edit -> edited += action.id
                            is BattleConfigListAction.StartSelection -> {
                                selectionMode = true
                                selected = setOf(action.id)
                            }
                            is BattleConfigListAction.ToggleSelected -> {
                                selected = if (action.id in selected) selected - action.id else selected + action.id
                                if (selected.isEmpty()) selectionMode = false
                            }
                            is BattleConfigListAction.ToggleFolder -> {
                                expanded = if (action.key in expanded) expanded - action.key else expanded + action.key
                            }
                            else -> {}
                        }
                    }
                )
            }
        }
        composeRule.waitForIdle()

        // Tap opens edit
        composeRule.onNodeWithText("SoloConfig").performClick()
        assertThat(edited).isEqualTo(listOf("u1"))

        // Folder toggle expands and collapses
        composeRule.onNodeWithText("FolderConfig").assertDoesNotExist()
        composeRule.onNodeWithText("Farm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FolderConfig").assertIsDisplayed()

        composeRule.onNodeWithText("Farm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FolderConfig").assertDoesNotExist()

        // Long-press starts selection mode
        composeRule.onNodeWithText("SoloConfig").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(
            context.getString(R.string.battle_config_folder_move),
            ignoreCase = true
        ).assertIsDisplayed()
    }

    @Test
    fun `sticky header stays visible while scrolling through multi-server grid`() {
        val jpConfigs = (1..24).map { i ->
            makeConfig("j$i", "JP_${i.toString().padStart(2, '0')}", server = GameServer.Jp.Original)
        }
        val krConfigs = (1..6).map { i ->
            makeConfig("k$i", "KR_${i.toString().padStart(2, '0')}", server = GameServer.Kr)
        }

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = jpConfigs + krConfigs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val gridNode = composeRule.onNodeWithTag(ConfigGridTag)
        val gridTop = gridNode.getBoundsInRoot().top
        // Scroll partway through the JP configs so JP_01 scrolls off-screen, while JP header stays pinned
        gridNode.performScrollToIndex(12)
        composeRule.waitForIdle()

        val jpHeaderNode = composeRule.onNodeWithTag("${ServerHeaderTagPrefix}${GameServer.Jp.Original.serialize()}")
        jpHeaderNode.assertIsDisplayed()
        val headerBounds = jpHeaderNode.getBoundsInRoot()
        assertThat(headerBounds.top).isLessThan(gridTop + 20.dp)
        composeRule.onNodeWithText("JP_01").assertDoesNotExist()
    }

    @Test
    fun `expanded folder box contains its 3 configs and excludes 2 unfiled configs`() {
        val configs = listOf(
            makeConfig("f1", "Alpha", folder = "Lancer"),
            makeConfig("f2", "Beta", folder = "Lancer"),
            makeConfig("f3", "Gamma", folder = "Lancer"),
            makeConfig("u1", "SoloOne"),
            makeConfig("u2", "SoloTwo")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = setOf("lancer"),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxNode = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer")
        boxNode.assertIsDisplayed()
        // FolderRow + 3 BattleConfigListItem cards are inside the folder box
        boxNode.onChildren().assertCountEquals(4)
        listOf("Alpha", "Beta", "Gamma").forEach { name ->
            boxNode.onChildren().filterToOne(hasAnyDescendant(hasText(name))).assertIsDisplayed()
            composeRule.onNode(
                hasText(name) and hasAnyAncestor(hasTestTag("${FolderBoxTagPrefix}lancer"))
            ).assertIsDisplayed()
        }

        val boxBounds = boxNode.getBoundsInRoot()
        listOf("SoloOne", "SoloTwo").forEach { name ->
            composeRule.onNode(
                hasText(name) and hasAnyAncestor(hasTestTag("${FolderBoxTagPrefix}lancer"))
            ).assertDoesNotExist()
            val unfiledBounds = composeRule.onNodeWithText(name).onParent().getBoundsInRoot()
            assertThat(unfiledBounds.top).isGreaterThan(boxBounds.bottom)
        }
    }

    @Test
    fun `collapsed folder box exists and contains no config`() {
        val configs = listOf(
            makeConfig("f1", "Alpha", folder = "Lancer"),
            makeConfig("f2", "Beta", folder = "Lancer")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = emptySet(),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxNode = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer")
        boxNode.assertIsDisplayed()
        // Only the FolderRow child is inside the collapsed box
        boxNode.onChildren().assertCountEquals(1)
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selection mode works for configs inside a folder box`() {
        val configs = listOf(
            makeConfig("f1", "FolderOne", folder = "Farm"),
            makeConfig("f2", "FolderTwo", folder = "Farm")
        )
        var lastSelected: Set<String> = emptySet()

        composeRule.setContent {
            var selectionMode by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(emptySet<String>()) }

            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = selectionMode,
                    selectedConfigs = selected,
                    expandedFolders = setOf("farm"),
                    folderRevision = 0,
                    action = { action ->
                        when (action) {
                            is BattleConfigListAction.StartSelection -> {
                                selectionMode = true
                                selected = setOf(action.id)
                                lastSelected = selected
                            }
                            is BattleConfigListAction.ToggleSelected -> {
                                selected = if (action.id in selected) selected - action.id else selected + action.id
                                if (selected.isEmpty()) selectionMode = false
                                lastSelected = selected
                            }
                            else -> {}
                        }
                    }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("FolderOne").performTouchInput { longClick() }
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onNodeWithText(
            context.getString(R.string.battle_config_folder_move),
            ignoreCase = true
        ).assertIsDisplayed()
        assertThat(lastSelected).isEqualTo(setOf("f1"))

        composeRule.onNodeWithText("FolderTwo").performClick()
        composeRule.waitForIdle()
        assertThat(lastSelected).isEqualTo(setOf("f1", "f2"))

        composeRule.onNodeWithText("FolderOne").performClick()
        composeRule.waitForIdle()
        assertThat(lastSelected).isEqualTo(setOf("f2"))
    }

    @Test
    fun `two-column width renders expanded odd folder followed by unfiled config without pad spacer`() {
        val configs = listOf(
            makeConfig("f1", "Alpha", folder = "Lancer"),
            makeConfig("f2", "Beta", folder = "Lancer"),
            makeConfig("f3", "Gamma", folder = "Lancer"),
            makeConfig("u1", "Solo")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = setOf("lancer"),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer").getBoundsInRoot()
        val b1 = composeRule.onNodeWithText("Alpha").onParent().getBoundsInRoot()
        val b2 = composeRule.onNodeWithText("Beta").onParent().getBoundsInRoot()
        val b3 = composeRule.onNodeWithText("Gamma").onParent().getBoundsInRoot()
        val u1 = composeRule.onNodeWithText("Solo").onParent().getBoundsInRoot()

        assertDpClose(b1.top, b2.top)
        assertDpClose(b2.left - b1.right, 12.dp)
        assertDpClose(b3.top - b1.bottom, 10.dp)
        assertDpClose(b3.width, b1.width)
        assertDpClose(u1.top - boxBounds.bottom, 10.dp)
        // Grid has only the folder box and the unfiled card (no pad spacer)
        composeRule.onNodeWithTag(ConfigGridTag).onChildren().assertCountEquals(2)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-column width renders expanded folder followed by unfiled config without pad spacer`() {
        val configs = listOf(
            makeConfig("f1", "Alpha", folder = "Lancer"),
            makeConfig("f2", "Beta", folder = "Lancer"),
            makeConfig("f3", "Gamma", folder = "Lancer"),
            makeConfig("u1", "Solo")
        )

        composeRule.setContent {
            FGATheme {
                BattleConfigListContent(
                    configs = configs,
                    selectionMode = false,
                    selectedConfigs = emptySet(),
                    expandedFolders = setOf("lancer"),
                    folderRevision = 0,
                    action = {}
                )
            }
        }
        composeRule.waitForIdle()

        val boxBounds = composeRule.onNodeWithTag("${FolderBoxTagPrefix}lancer").getBoundsInRoot()
        val b1 = composeRule.onNodeWithText("Alpha").onParent().getBoundsInRoot()
        val b2 = composeRule.onNodeWithText("Beta").onParent().getBoundsInRoot()
        val b3 = composeRule.onNodeWithText("Gamma").onParent().getBoundsInRoot()
        val u1 = composeRule.onNodeWithText("Solo").onParent().getBoundsInRoot()

        assertDpClose(b1.left, b2.left)
        assertDpClose(b2.left, b3.left)
        assertDpClose(b2.top - b1.bottom, 10.dp)
        assertDpClose(b3.top - b2.bottom, 10.dp)
        assertDpClose(u1.top - boxBounds.bottom, 10.dp)
        // Grid has only the folder box and the unfiled card (no pad spacer)
        composeRule.onNodeWithTag(ConfigGridTag).onChildren().assertCountEquals(2)
    }
}
