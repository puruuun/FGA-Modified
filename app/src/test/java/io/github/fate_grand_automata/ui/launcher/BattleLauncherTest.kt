package io.github.fate_grand_automata.ui.launcher

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h480dp")
class BattleLauncherTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private data class ConfigSpec(
        val id: String,
        val name: String,
        val folder: String = ""
    )

    private fun setupLauncherPrefs(
        specs: List<ConfigSpec>,
        selectedId: String? = null
    ): Pair<PrefsCore, PreferencesImpl> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mainPrefs = context.getSharedPreferences("launcher_test_prefs", Context.MODE_PRIVATE)
        mainPrefs.edit().clear().commit()
        specs.forEach { spec ->
            context.getSharedPreferences(spec.id, Context.MODE_PRIVATE).edit().clear().commit()
        }

        val prefsCore = PrefsCore(PrefMaker(mainPrefs), context)
        val prefs = PreferencesImpl(prefsCore, CompletedRunsHolder()).apply {
            gameServer = GameServer.Jp.Original
        }

        specs.forEach { spec ->
            prefs.addBattleConfig(spec.id)
            prefsCore.forBattleConfig(spec.id).apply {
                name.set(spec.name)
                folder.set(spec.folder)
                server.set(BattleConfigCore.Server.NotSet)
            }
        }
        if (selectedId != null) {
            prefs.selectedBattleConfig = prefs.forBattleConfig(selectedId)
        }
        return prefsCore to prefs
    }

    private fun assertDpClose(actual: Dp, expected: Dp, tolerance: Dp = 1.dp) {
        assertThat(actual.value).isBetween(expected.value - tolerance.value, expected.value + tolerance.value)
    }

    @Test
    fun `expanded folder of 3 plus 2 unfiled tags only folder rows and joins segments`() {
        val (prefsCore, prefs) = setupLauncherPrefs(
            specs = listOf(
                ConfigSpec("f1", "Alpha", folder = "Lancer"),
                ConfigSpec("f2", "Beta", folder = "Lancer"),
                ConfigSpec("f3", "Gamma", folder = "Lancer"),
                ConfigSpec("u1", "SoloOne"),
                ConfigSpec("u2", "SoloTwo")
            ),
            selectedId = "f1"
        )

        composeRule.setContent {
            FGATheme {
                battleLauncher(
                    prefs = prefs,
                    modifier = Modifier.fillMaxSize(),
                    prefsCore = prefsCore
                )
            }
        }
        composeRule.waitForIdle()

        val headerTag = "${LauncherFolderBoxTagPrefix}lancer"
        val itemTag = "${LauncherFolderBoxItemTagPrefix}lancer"

        val headerNode = composeRule.onNodeWithTag(headerTag)
        headerNode.assertIsDisplayed()
        composeRule.onAllNodesWithTag(itemTag).assertCountEquals(3)

        listOf("Alpha", "Beta", "Gamma").forEach { name ->
            composeRule.onNode(hasText(name) and hasTestTag(itemTag)).assertIsDisplayed()
        }
        listOf("SoloOne", "SoloTwo").forEach { name ->
            composeRule.onNodeWithText(name).assertIsDisplayed()
            composeRule.onNode(hasText(name) and hasTestTag(itemTag)).assertDoesNotExist()
        }

        // Box segments join without LazyColumn item spacing; clickable bounds reflect each row's 3dp inner padding
        val headerBounds = headerNode.getBoundsInRoot()
        val b1 = composeRule.onNode(hasText("Alpha") and hasTestTag(itemTag)).getBoundsInRoot()
        val b2 = composeRule.onNode(hasText("Beta") and hasTestTag(itemTag)).getBoundsInRoot()
        val b3 = composeRule.onNode(hasText("Gamma") and hasTestTag(itemTag)).getBoundsInRoot()
        val u1 = composeRule.onNodeWithText("SoloOne").getBoundsInRoot()

        assertDpClose(b1.top - headerBounds.bottom, 6.dp)
        assertDpClose(b2.top - b1.bottom, 6.dp)
        assertDpClose(b3.top - b2.bottom, 6.dp)
        // Folder items get only the 4dp box horizontal padding rather than the old 16dp indent
        assertDpClose(b1.left - u1.left, 4.dp)
        assertDpClose(b2.left, b1.left)
        assertDpClose(b3.left, b1.left)
    }

    @Test
    fun `collapsed folder shows only its header`() {
        val (prefsCore, prefs) = setupLauncherPrefs(
            specs = listOf(
                ConfigSpec("f1", "Alpha", folder = "Lancer"),
                ConfigSpec("f2", "Beta", folder = "Lancer"),
                ConfigSpec("u1", "Solo")
            ),
            selectedId = "u1"
        )

        composeRule.setContent {
            FGATheme {
                battleLauncher(
                    prefs = prefs,
                    modifier = Modifier.fillMaxSize(),
                    prefsCore = prefsCore
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("${LauncherFolderBoxTagPrefix}lancer").assertIsDisplayed()
        composeRule.onAllNodesWithTag("${LauncherFolderBoxItemTagPrefix}lancer").assertCountEquals(0)
        composeRule.onNodeWithText("Alpha").assertDoesNotExist()
        composeRule.onNodeWithText("Beta").assertDoesNotExist()
        composeRule.onNodeWithText("Solo").assertIsDisplayed()
    }

    @Test
    fun `selecting a config inside a folder box updates selected config`() {
        val (prefsCore, prefs) = setupLauncherPrefs(
            specs = listOf(
                ConfigSpec("f1", "FolderOne", folder = "Farm"),
                ConfigSpec("f2", "FolderTwo", folder = "Farm")
            ),
            selectedId = "f1"
        )

        lateinit var responseBuilder: ScriptLauncherResponseBuilder
        composeRule.setContent {
            FGATheme {
                responseBuilder = battleLauncher(
                    prefs = prefs,
                    modifier = Modifier.fillMaxSize(),
                    prefsCore = prefsCore
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            hasText("FolderTwo") and hasTestTag("${LauncherFolderBoxItemTagPrefix}farm")
        ).performClick()
        composeRule.waitForIdle()

        assertThat(responseBuilder.canBuild()).isTrue()
        composeRule.runOnUiThread {
            responseBuilder.build()
        }
        assertThat(prefs.selectedBattleConfig.id).isEqualTo("f2")
    }

    @Test
    fun `selected config inside a collapsed folder shows its held name`() {
        val (prefsCore, prefs) = setupLauncherPrefs(
            specs = listOf(
                ConfigSpec("f1", "Alpha", folder = "Lancer"),
                ConfigSpec("f2", "Beta", folder = "Lancer")
            ),
            selectedId = "f1"
        )

        composeRule.setContent {
            FGATheme {
                battleLauncher(
                    prefs = prefs,
                    modifier = Modifier.fillMaxSize(),
                    prefsCore = prefsCore
                )
            }
        }
        composeRule.waitForIdle()

        val headerTag = "${LauncherFolderBoxTagPrefix}lancer"
        composeRule.onNodeWithTag(headerTag).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("${LauncherFolderBoxItemTagPrefix}lancer").assertCountEquals(0)
        composeRule.onNode(hasText("Lancer · Alpha") and hasTestTag(headerTag)).assertIsDisplayed()
    }
}
