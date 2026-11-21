package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.capture.CaptureMainState
import io.github.fate_grand_automata.capture.CaptureServiceButtons
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.ScreenshotDir
import io.github.fate_grand_automata.ui.battle_config_item.Materials
import io.github.fate_grand_automata.ui.battle_config_item.SupportClassPicker
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails // THUMBS613
import io.github.fate_grand_automata.ui.launcher.LauncherResetAllTag
import io.github.fate_grand_automata.ui.launcher.battleLauncher
import io.github.fate_grand_automata.ui.main.MainBuildStatusRow
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportSelectPreference
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.SupportThumbnailIndex // THUMBS613
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Before/after set for #613 at 360x800dp and 1280x800dp, light and dark. Images land in
 * `build/outputs/roborazzi/ui613/`. Recorded by `:app:recordRoborazziCi`, never part of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class Small613ScreenshotTest(private val dark: Boolean, private val width: Int) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dir = "ui613"

    private fun prefs(name: String): PrefsCore {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context)
    }

    private fun shoot(name: String, height: Int = 800, beforeCapture: () -> Unit = {}, content: @Composable () -> Unit) {
        composeRule.shoot(dir, name, width, height, dark = dark, locale = "ko", beforeCapture = beforeCapture) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    private fun shootDialog(name: String, open: () -> Unit, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("ko-w${width}dp-h800dp-${if (dark) "night" else "notnight"}-hdpi")
        composeRule.setContent { FGATheme(darkTheme = dark) { content() } }
        composeRule.waitForIdle()
        open()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        repeat(10) {
            Thread.sleep(200)
            composeRule.waitForIdle()
        }
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("$ScreenshotDir/$dir/${name}_${width}x800dp_ko${if (dark) "_dark" else ""}.png")
    }

    @Composable
    private fun mainRow(running: Boolean) {
        Column {
            MainBuildStatusRow(serviceStarted = true, overlayServiceStarted = false, openBuild = {}, toggleService = {})
            CaptureServiceButtons(CaptureMainState(showButton = true, running = running), {}) {
                ExtendedFloatingActionButton(onClick = {}) { Text("서비스 시작") }
            }
        }
    }

    @Test fun mainCollectionOff() = shoot("MainCollectionOff", 300) { mainRow(false) }
    @Test fun mainCollectionOn() = shoot("MainCollectionOn", 300) { mainRow(true) }

    @Test
    fun supportClassPicker() = shoot("SupportClassPicker", 200) {
        SupportClassPicker(selected = SupportClass.Saber, onSelectedChange = {})
    }

    private fun png(file: File, color: Int) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun servantPicker() {
        val root = File(context.cacheDir, "s613-${System.nanoTime()}").apply { mkdirs() }
        val names = listOf("Altria Pendragon", "Artoria Pendragon (Lancer)", "Mash Kyrielight", "Emiya", "Scathach", "Merlin")
        val colors = listOf(0xFF3F51B5, 0xFF009688, 0xFFE91E63, 0xFFFF9800, 0xFF795548)
        names.dropLast(1).forEachIndexed { i, n -> png(File(root, "support/servant/$n/face.png"), colors[i].toInt()) }
        File(root, "support/servant/${names.last()}").mkdirs()
        val index = SupportThumbnailIndex { DocumentFile.fromFile(File(root, "support/servant")) } // THUMBS613
        // Robolectric on Windows cannot run ImageDecoder, so the files are resolved by the real index but drawn from a bitmap.
        val faces = FaceThumbnails { name -> index.thumbnail(name)?.let { android.graphics.drawable.BitmapDrawable(context.resources, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[names.indexOf(name) % colors.size].toInt()) }) } } // THUMBS613
        val pref = prefs("s613").forBattleConfig("t").support.preferredServants
        pref.set(setOf(names[1], names[3]))
        shootDialog("ServantPicker", open = { composeRule.onNodeWithText("서번트 선호").performClick() }) {
            pref.SupportSelectPreference("서번트 선호", names.associateWith { it }, thumbnails = faces) // THUMBS613
        }
    }

    @Test
    fun materialsDialog() {
        val pref = prefs("m613").forBattleConfig("t").materials
        pref.set(setOf(MaterialEnum.entries[0], MaterialEnum.entries[3]))
        shootDialog("MaterialsDialog", open = { composeRule.onNodeWithText("재료").performClick() }) {
            Surface(color = MaterialTheme.colorScheme.background) { pref.Materials() }
        }
    }

    private fun support(name: String, servants: Boolean, grand: Boolean): Pair<PrefsCore, SupportViewModel> {
        val core = prefs(name)
        core.forBattleConfig("t").support.apply {
            if (servants) preferredServants.set(setOf("Altria Pendragon", "Emiya"))
            grandServant.set(grand)
            npLevelCheck.set(true)
            score62Check.set(true)
            grandScore16Check.set(true)
        }
        return core to SupportViewModel(StorageProvider(core, context))
    }

    private fun preferred(name: String, servants: Boolean, grand: Boolean) {
        val (core, vm) = support("p613_$name", servants, grand)
        shoot(name, 1500) { PreferredSupport(core.forBattleConfig("t").support, vm) }
    }

    @Test fun preferredNoServants() = preferred("PreferredNoServants", servants = false, grand = true)
    @Test fun preferredServantsGrandOff() = preferred("PreferredServantsGrandOff", servants = true, grand = false)
    @Test fun preferredServantsGrandOn() = preferred("PreferredServantsGrandOn", servants = true, grand = true)

    @Test
    fun launcherBondStop() {
        val fixture = CaptureLauncherFixture(context, GameServer.Jp.Original)
        shoot("Launcher") { battleLauncher(fixture.prefs, prefsCore = fixture.core) }
    }

    @Test
    fun launcherLimits() {
        val fixture = CaptureLauncherFixture(context, GameServer.Jp.Original)
        shoot("LauncherLimits", beforeCapture = { composeRule.onNodeWithTag(LauncherResetAllTag).performScrollTo() }) {
            battleLauncher(fixture.prefs, prefsCore = fixture.core)
        }
    }

    @Test
    fun launcherBond() {
        val fixture = CaptureLauncherFixture(context, GameServer.Jp.Original)
        val ignore = "MAX 정지를 무시할 캐릭터"
        shoot("LauncherBond", beforeCapture = {
            composeRule.onNodeWithText("인연 MAX 확인 시 정지").performScrollTo()
            composeRule.onNodeWithText(ignore).performScrollTo()
        }) {
            battleLauncher(fixture.prefs, prefsCore = fixture.core)
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0} width={1}")
        fun params(): List<Array<Any?>> = listOf(false, true).flatMap { dark -> listOf(360, 1280).map { arrayOf<Any?>(dark, it) } }
    }
}
