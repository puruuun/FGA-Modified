package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.ScreenshotDir
import io.github.fate_grand_automata.ui.battle_config_item.PartySelectionDialogContent
import io.github.fate_grand_automata.ui.battle_config_item.RaidDelay
import io.github.fate_grand_automata.ui.battle_config_item.SupportGroup
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.dialog.multiChoiceList
import io.github.fate_grand_automata.ui.dialog.singleChoiceList
import io.github.fate_grand_automata.ui.launcher.battleLauncher
import io.github.fate_grand_automata.ui.pref_support.MaxSkills
import io.github.fate_grand_automata.ui.prefs.MultiSelectChip
import io.github.fate_grand_automata.ui.prefs.SingleSelectChip
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Before/after set for the standard-component change (#596). Images land in
 * `build/outputs/roborazzi/buttons596/`. Recorded by `:app:recordRoborazziCi`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Buttons596ScreenshotTest(private val dark: Boolean) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun screen(screen: CappedScreen, height: Int = 900) {
        val fixture = CappedScreenFixture(context)
        composeRule.shoot("buttons596", screen.name, 360, height, dark = dark, locale = "ko") {
            fixture.Render(screen)
        }
    }

    private fun shoot(name: String, height: Int = 600, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.shoot("buttons596", name, 360, height, dark = dark, locale = "ko") {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    private fun shootDialog(name: String, open: () -> Unit = {}, content: @androidx.compose.runtime.Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("ko-w360dp-h800dp-${if (dark) "night" else "notnight"}-hdpi")
        composeRule.setContent { FGATheme(darkTheme = dark) { content() } }
        composeRule.waitForIdle()
        open()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("$ScreenshotDir/buttons596/${name}_360dp_ko${if (dark) "_dark" else ""}.png")
    }

    private fun serverConfigs(): List<BattleConfigCore> = listOf(
        "Jp one" to GameServer.Jp.Original, "Jp two" to GameServer.Jp.Original,
        "Kr one" to GameServer.Kr, "Kr two" to GameServer.Kr
    ).mapIndexed { i, (name, server) ->
        BattleConfigCore("b596_$i", context).apply {
            this.name.set(name)
            this.server.set(BattleConfigCore.Server.Set(server))
        }
    }

    @Test fun main() = screen(CappedScreen.Main)
    @Test fun battleConfigList() = screen(CappedScreen.BattleConfigList)
    @Test fun cardPriority() = screen(CappedScreen.CardPriority)
    @Test fun spam() = screen(CappedScreen.Spam)
    @Test fun preferredSupport() = screen(CappedScreen.PreferredSupport, 1400)

    @Test
    fun serverList() = shoot("ServerList") {
        BattleConfigListContent(
            configs = serverConfigs(), selectionMode = false, selectedConfigs = emptySet(),
            expandedFolders = emptySet(), folderRevision = 0, action = {}
        )
    }

    @Test
    fun serverListSelection() = shoot("ServerListSelection") {
        BattleConfigListContent(
            configs = serverConfigs(), selectionMode = true, selectedConfigs = setOf("b596_1", "b596_2"),
            expandedFolders = emptySet(), folderRevision = 0, action = {}
        )
    }

    @Test
    fun supportMaxBoxes() {
        val config = BattleConfigCore("b596_max", context)
        config.support.skill1Max.set(true)
        config.support.skill2Max.set(false)
        config.support.skill3Max.set(true)
        config.support.appendSkillsMax.forEachIndexed { i, pref -> pref.set(i == 0) }
        shoot("SupportMaxBoxes", 300) {
            Column(Modifier.padding(16.dp)) {
                MaxSkills(listOf(config.support.skill1Max, config.support.skill2Max, config.support.skill3Max))
                Text("append", Modifier.padding(top = 16.dp))
                MaxSkills(config.support.appendSkillsMax, compact = true)
            }
        }
    }

    @Test
    fun supportGroup() {
        val config = BattleConfigCore("b596_group", context)
        config.support.supportClass.set(SupportClass.Saber)
        shoot("SupportGroup", 700) {
            Column { SupportGroup(config.support, maxSkillText = "10/x/10", goToPreferred = {}) }
        }
    }

    @Test
    fun party() = shoot("Party", 300) {
        Column(Modifier.padding(16.dp)) { PartySelectionDialogContent(selected = 2, onSelectedChange = {}) }
    }

    @Test
    fun raidDelay() {
        val config = BattleConfigCore("b596_raid", context)
        config.addRaidTurnDelay.set(true)
        shootDialog("RaidDelay", open = { composeRule.onAllNodes(hasClickAction()).onFirst().performClick() }) {
            RaidDelay(config = config)
        }
    }

    @Test
    fun chips() = shoot("Chips", 400) {
        Column {
            SingleSelectChip(
                title = "Single", selected = 2, onSelectedChange = {},
                entries = mapOf(1 to "One", 2 to "Two", 3 to "Three")
            )
            MultiSelectChip(
                title = "Multi", selected = setOf(1, 3), onSelectedChange = {},
                entries = mapOf(1 to "One", 2 to "Two", 3 to "Three")
            )
            SingleSelectChip(
                title = "Single row", selected = "b", onSelectedChange = {}, singleRow = true,
                entries = mapOf("a" to "A", "b" to "B", "c" to "C")
            )
        }
    }

    @Test
    fun singleChoiceDialog() = shootDialog("SingleChoice") {
        val dialog = FgaDialog(true)
        dialog.build {
            title("Choice")
            singleChoiceList(selected = "b", items = listOf("a", "b", "c"), onSelectedChange = {})
        }
    }

    @Test
    fun multiChoiceDialog() = shootDialog("MultiChoice") {
        val dialog = FgaDialog(true)
        dialog.build {
            title("Choices")
            multiChoiceList(selected = setOf("a", "c"), items = listOf("a", "b", "c"), onSelectedChange = {})
        }
    }

    @Test
    fun launcher() {
        val mainPrefs = context.getSharedPreferences("b596_launcher", Context.MODE_PRIVATE)
        mainPrefs.edit().clear().commit()
        val prefsCore = PrefsCore(PrefMaker(mainPrefs), context)
        val prefs = PreferencesImpl(prefsCore, CompletedRunsHolder()).apply { gameServer = GameServer.Jp.Original }
        prefs.addBattleConfig("b596_l1")
        prefsCore.forBattleConfig("b596_l1").apply {
            name.set("Farming")
            server.set(BattleConfigCore.Server.NotSet)
        }
        prefs.selectedBattleConfig = prefs.forBattleConfig("b596_l1")
        prefs.getPerServerConfigPref(GameServer.Jp.Original).shouldLimitRuns = true
        RuntimeEnvironment.setQualifiers("ko-w800dp-h480dp-${if (dark) "night" else "notnight"}-hdpi")
        composeRule.setContent {
            FGATheme(darkTheme = dark) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    battleLauncher(prefs = prefs, modifier = Modifier.fillMaxSize(), prefsCore = prefsCore)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$ScreenshotDir/buttons596/Launcher_800x480dp_ko${if (dark) "_dark" else ""}.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params(): List<Array<Any?>> = listOf(arrayOf<Any?>(false), arrayOf<Any?>(true))
    }
}
