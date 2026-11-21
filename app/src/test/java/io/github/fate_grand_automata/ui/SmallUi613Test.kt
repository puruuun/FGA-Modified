package io.github.fate_grand_automata.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureMainButton
import io.github.fate_grand_automata.capture.captureStopBackground
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.ui.battle_config_item.Materials
import io.github.fate_grand_automata.ui.battle_config_item.SupportClassPicker
import io.github.fate_grand_automata.ui.battle_config_item.SupportClassTagPrefix
import io.github.fate_grand_automata.ui.dialog.FacePlaceholderTag
import io.github.fate_grand_automata.ui.dialog.FaceThumbnailTag
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails
import io.github.fate_grand_automata.ui.main.MainBuildStatusRow
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportSelectPreference
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.stringRes
import io.github.fate_grand_automata.util.SupportThumbnailIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** #613: the small UI fixes. Each one keeps what its control does and stores. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class SmallUi613Test {
    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefsCore(name: String): PrefsCore {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context)
    }

    // Build chip

    @Test
    fun `build chip has no leading icon and still opens the build info`() {
        var opened = 0
        compose.setContent {
            FGATheme {
                Column {
                    MainBuildStatusRow(false, false, { opened++ }, {})
                    AssistChip(onClick = {}, label = { Text("Build: ${BuildConfig.VERSION_NAME}") }, modifier = androidx.compose.ui.Modifier.testTag("bareChip"))
                }
            }
        }
        val chip = compose.onNodeWithTag("buildBadge").onChildren().onFirst()
        val withoutIcon = compose.onNodeWithTag("bareChip").fetchSemanticsNode().boundsInRoot.width
        assertEquals(withoutIcon, chip.fetchSemanticsNode().boundsInRoot.width, 1f)
        chip.performClick()
        assertEquals(1, opened)
    }

    // Collection button

    private fun buttonColour(running: Boolean, dark: Boolean): Int {
        compose.setContent { FGATheme(darkTheme = dark) { CaptureMainButton(running) {} } }
        compose.waitForIdle()
        val image = compose.onNode(hasClickAction()).captureToImage().asAndroidBitmap()
        // Left of the icon, inside the container.
        return image.getPixel((6 * 1.5f).toInt(), image.height / 2)
    }

    private fun assertColour(expected: Color, actual: Int) {
        val e = expected.toArgb()
        for (shift in listOf(16, 8, 0)) {
            val d = abs(((e shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
            assertTrue("expected ${Integer.toHexString(e)} but was ${Integer.toHexString(actual)}", d <= 2)
        }
    }

    @Test fun `collecting button is yellow in the light theme`() =
        assertColour(captureStopBackground, buttonColour(running = true, dark = false))

    @Test fun `collecting button is yellow in the dark theme`() =
        assertColour(captureStopBackground, buttonColour(running = true, dark = true))

    @Test fun `start button is primary in the light theme`() =
        assertColour(md_theme_light_primary, buttonColour(running = false, dark = false))

    @Test fun `start button is primary in the dark theme`() =
        assertColour(md_theme_dark_primary, buttonColour(running = false, dark = true))

    // Support class picker

    @Test
    @Config(qualifiers = "en-w1280dp-h800dp-hdpi")
    fun `class diamond is at most 30dp wide on a tablet and the touch target keeps its size`() {
        compose.setContent { FGATheme { SupportClassPicker(selected = SupportClass.Saber, onSelectedChange = {}) } }
        val classes = SupportClass.entries.drop(1)
        val widths = classes.map { cls ->
            val box = compose.onNodeWithTag("$SupportClassTagPrefix${cls.name}")
            val bounds = box.fetchSemanticsNode().boundsInRoot
            val density = box.fetchSemanticsNode().layoutInfo.density.density
            assertTrue("touch height ${bounds.height / density}", bounds.height / density >= 47.5f)
            val diamond = compose.onNode(hasContentDescription(cls.name), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("diamond ${diamond.width / density}", diamond.width / density <= 30.5f)
            assertTrue(diamond.width / density >= 29f)
            bounds.width / density
        }
        assertTrue("shares differ: $widths", widths.all { abs(it - widths.first()) < 1f })
        assertTrue("the ten classes must still fill the row: $widths", widths.sum() > 1200f)
    }

    @Test
    fun `class picker still toggles the class`() {
        var selected = SupportClass.None
        compose.setContent { FGATheme { SupportClassPicker(selected = selected, onSelectedChange = { selected = it }) } }
        compose.onNodeWithTag("${SupportClassTagPrefix}Archer").performClick()
        assertEquals(SupportClass.Archer, selected)
    }

    // Thumbnail index

    // The index only looks at names, so the files need no real image data.
    private fun pngFile(file: File, @Suppress("UNUSED_PARAMETER") color: Int) {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1))
    }

    private fun supportRoot(): File = File(context.cacheDir, "face613-${System.nanoTime()}").apply { mkdirs() }

    private fun index(root: File, sub: String = "servant") =
        SupportThumbnailIndex { DocumentFile.fromFile(File(root, "support/$sub")).takeIf { it.isDirectory } }

    @Test
    fun `index gives the first image of the entry folder and nothing for an empty or missing one`() {
        val root = supportRoot()
        pngFile(File(root, "support/servant/Alpha/b.png"), 0xFF00FF00.toInt())
        pngFile(File(root, "support/servant/Alpha/a.png"), 0xFFFF0000.toInt())
        File(root, "support/servant/Beta").mkdirs()
        File(root, "support/servant/Beta/.nomedia").writeBytes(byteArrayOf())
        val index = index(root)
        runBlocking {
            assertEquals("a.png", File(index.thumbnail("Alpha")!!.path!!).name)
            assertNull(index.thumbnail("Beta"))
            assertNull(index.thumbnail("Missing"))
        }
    }

    @Test
    fun `index reads a ce stored as a single file and never creates a folder`() {
        val root = supportRoot()
        pngFile(File(root, "support/ce/Craft.png"), 0xFF0000FF.toInt())
        runBlocking {
            assertNotNull(index(root, "ce").thumbnail("Craft.png"))
            assertNull(index(File(root, "nowhere")).thumbnail("Alpha"))
        }
        assertFalse(File(root, "nowhere").exists())
        assertFalse(File(root, "support/servant").exists())
    }

    @Test
    fun `index lists the folders off the calling thread`() {
        val root = supportRoot()
        pngFile(File(root, "support/servant/Alpha/a.png"), 0)
        val caller = Thread.currentThread()
        var listedOn: Thread? = null
        val index = SupportThumbnailIndex {
            listedOn = Thread.currentThread()
            DocumentFile.fromFile(File(root, "support/servant"))
        }
        runBlocking { index.thumbnail("Alpha") }
        assertNotNull(listedOn)
        assertTrue("the folder was listed on the calling thread", listedOn !== caller)
    }

    @Test
    fun `index finds a renamed folder for a legacy number name like the support search does`() {
        val root = supportRoot()
        pngFile(File(root, "support/servant/No.444 Renamed Servant/a.png"), 0)
        pngFile(File(root, "support/servant/No.5 Other/b.png"), 0)
        val index = index(root)
        runBlocking {
            assertEquals("No.444 Renamed Servant", File(index.thumbnail("No.444")!!.path!!).parentFile!!.name)
            assertNull(index.thumbnail("No.445"))
            assertNull(index.thumbnail("Renamed"))
        }
    }

    // Support picker

    private fun faceFixture(): Triple<File, Map<String, String>, FaceThumbnails> {
        val root = supportRoot()
        pngFile(File(root, "support/servant/Alpha/a.png"), 0xFFFF0000.toInt())
        pngFile(File(root, "support/servant/Gamma/g.png"), 0xFF0000FF.toInt())
        File(root, "support/servant/Beta").mkdirs()
        val index = index(root)
        return Triple(root, linkedMapOf("Alpha" to "Alpha", "Beta" to "Beta", "Gamma" to "Gamma"), FaceThumbnails { name ->
            // A real file Uri would make Coil decode on its own thread, which aborts the JVM under Robolectric on Linux.
            index.thumbnail(name)?.let { android.graphics.drawable.ColorDrawable(0xFF3F51B5.toInt()) }
        })
    }

    @Test
    fun `picker rows show a face or a placeholder, toggle on a row tap and store the same set`() {
        val (_, entries, faces) = faceFixture()
        val pref = prefsCore("face613_a").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha", "Gamma"))
        compose.setContent { FGATheme { pref.SupportSelectPreference("Servants", entries, thumbnails = faces) } }
        compose.onNodeWithText("Servants").performClick()
        try { compose.waitUntil(5_000) { compose.onAllNodesWithTag(FaceThumbnailTag, useUnmergedTree = true).fetchSemanticsNodes().size == 2 } }
        catch (e: Throwable) { throw AssertionError(compose.onNode(isDialog()).printToString(), e) }
        compose.onAllNodesWithTag(FaceThumbnailTag, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag(FacePlaceholderTag, useUnmergedTree = true).assertCountEquals(1)

        val alpha = compose.onNode(hasText("Alpha") and isToggleable() and hasAnyAncestor(isDialog()))
        val beta = compose.onNode(hasText("Beta") and isToggleable() and hasAnyAncestor(isDialog()))
        alpha.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOn()
        beta.assertIsOff()
        assertTrue(beta.fetchSemanticsNode().touchBoundsInRoot.height / 1.5f >= 47.5f)

        beta.performClick()
        beta.assertIsOn()
        alpha.performClick()
        alpha.assertIsOff()
        assertEquals(setOf("Alpha", "Gamma"), pref.get())
        compose.onNodeWithText("OK").performClick()
        assertEquals(setOf("Beta", "Gamma"), pref.get())
    }

    @Test
    fun `picker stores and resolves faces by key when the key differs from the display name`() {
        val (_, _, faces) = faceFixture()
        val entries = linkedMapOf("Alpha" to "알파", "Beta" to "베타", "Gamma" to "감마")
        val pref = prefsCore("face613_key").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha", "Gamma"))
        compose.setContent { FGATheme { pref.SupportSelectPreference("Servants", entries, thumbnails = faces) } }
        compose.onNodeWithText("Servants").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(FaceThumbnailTag, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        compose.onNode(hasText("알파") and isToggleable() and hasAnyAncestor(isDialog())).assertIsOn()
        compose.onNode(hasText("베타") and isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("OK").performClick()
        assertEquals(setOf("Alpha", "Beta", "Gamma"), pref.get())
    }

    @Test
    fun `picker without images keeps its set when cancelled and shows no placeholder for a plain list`() {
        val pref = prefsCore("face613_b").forBattleConfig("t").support.preferredNoblePhantasms
        pref.set(setOf("One"))
        compose.setContent { FGATheme { pref.SupportSelectPreference("NPs", mapOf("One" to "One", "Two" to "Two")) } }
        compose.onNodeWithText("NPs").performClick()
        compose.onAllNodesWithTag(FacePlaceholderTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(FaceThumbnailTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasText("Two") and isToggleable()).performClick()
        compose.onNodeWithText("CANCEL").performClick()
        assertEquals(setOf("One"), pref.get())
    }

    // Materials dialog

    @Test
    fun `materials dialog shows the item icons, toggles, stores the same set and clears all`() {
        val pref = prefsCore("mat613").forBattleConfig("t").materials
        val first = MaterialEnum.entries[0]
        val second = MaterialEnum.entries[1]
        pref.set(setOf(first))
        compose.setContent { FGATheme { pref.Materials() } }
        // The summary strip shows the icons too, so the dialog's own are the ones under the dialog root.
        compose.onNodeWithText(context.getString(R.string.p_mats).uppercase()).performClick()
        val firstName = context.getString(first.stringRes)
        val icon = compose.onNode(hasContentDescription(firstName) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        assertEquals(32f, icon.fetchSemanticsNode().boundsInRoot.width / 1.5f, 0.5f)

        val row1 = compose.onNode(hasText(firstName) and isToggleable() and hasAnyAncestor(isDialog()))
        val row2 = compose.onNode(hasText(context.getString(second.stringRes)) and isToggleable() and hasAnyAncestor(isDialog()))
        row1.assertIsOn()
        row2.assertIsOff()
        row2.performClick()
        row2.assertIsOn()
        compose.onNodeWithText("OK").performClick()
        assertEquals(setOf(first, second), pref.get())
    }

    @Test
    fun `materials clear all empties the choice, is enabled only with a choice and stores on OK`() {
        val pref = prefsCore("mat613b").forBattleConfig("t").materials
        pref.set(setOf(MaterialEnum.entries[0], MaterialEnum.entries[2]))
        compose.setContent { FGATheme { pref.Materials() } }
        compose.onNodeWithText(context.getString(R.string.p_mats).uppercase()).performClick()
        val clear = compose.onNodeWithText("Clear all")
        clear.assertIsEnabled().performClick()
        clear.assertIsNotEnabled()
        assertEquals(2, pref.get().size)
        compose.onNodeWithText("OK").performClick()
        assertTrue(pref.get().isEmpty())
    }

    // Checks that need a preferred servant

    private fun support(name: String) = prefsCore(name).let { it to it.forBattleConfig("t").support }

    private fun render(prefs: PrefsCore) {
        val vm = SupportViewModel(StorageProvider(prefs, context))
        compose.setContent { FGATheme { PreferredSupport(prefs.forBattleConfig("t").support, vm) } }
        compose.waitForIdle()
    }

    private val np get() = context.getString(R.string.p_np_level_check)
    private val score62 get() = context.getString(R.string.p_score_62_check)
    private val grand16 get() = context.getString(R.string.p_grand_score_16_check)

    private fun visible(label: String, shown: Boolean) {
        if (shown) compose.onNodeWithText(label).assertExists() else compose.onNodeWithText(label).assertDoesNotExist()
    }

    private fun combo(servants: Boolean, grand: Boolean) {
        val (prefs, config) = support("chk613_${servants}_$grand")
        if (servants) config.preferredServants.set(setOf("Alpha"))
        config.grandServant.set(grand)
        render(prefs)
        visible(np, servants)
        visible(score62, servants)
        visible(grand16, servants && grand)
    }

    @Test fun `no servant hides the np, class score and grand score checks`() = combo(servants = false, grand = true)
    @Test fun `a servant without grand shows np and class score but not grand score`() = combo(servants = true, grand = false)
    @Test fun `a servant with grand shows all three checks`() = combo(servants = true, grand = true)

    @Test
    fun `preferred ces alone do not show the checks`() {
        val (prefs, config) = support("chk613_ce")
        config.preferredCEs.set(setOf("Craft"))
        render(prefs)
        visible(np, false)
        visible(score62, false)
    }

    @Test
    fun `opening the screen turns off checks that a stored config has without a servant`() {
        val (prefs, config) = support("chk613_open1")
        config.npLevelCheck.set(true)
        config.score62Check.set(true)
        config.grandScore16Check.set(true)
        config.grandServant.set(true)
        render(prefs)
        assertFalse(config.npLevelCheck.get())
        assertFalse(config.score62Check.get())
        assertFalse(config.grandScore16Check.get())
    }

    @Test
    fun `opening the screen turns off grand score when grand is off and keeps the other checks`() {
        val (prefs, config) = support("chk613_open2")
        config.preferredServants.set(setOf("Alpha"))
        config.grandServant.set(false)
        config.npLevelCheck.set(true)
        config.score62Check.set(true)
        config.grandScore16Check.set(true)
        render(prefs)
        assertFalse(config.grandScore16Check.get())
        assertTrue(config.npLevelCheck.get())
        assertTrue(config.score62Check.get())
    }

    @Test
    fun `a consistent config is left as it is`() {
        val (prefs, config) = support("chk613_ok")
        config.preferredServants.set(setOf("Alpha"))
        config.grandServant.set(true)
        config.npLevelCheck.set(true)
        config.score62Check.set(true)
        config.grandScore16Check.set(true)
        render(prefs)
        assertTrue(config.npLevelCheck.get())
        assertTrue(config.score62Check.get())
        assertTrue(config.grandScore16Check.get())
    }

    @Test
    fun `emptying the servants turns the checks off and they come back off`() {
        val (prefs, config) = support("chk613_empty")
        config.preferredServants.set(setOf("Alpha"))
        config.grandServant.set(true)
        config.npLevelCheck.set(true)
        config.score62Check.set(true)
        config.grandScore16Check.set(true)
        render(prefs)
        compose.runOnIdle { config.preferredServants.set(emptySet()) }
        compose.waitForIdle()
        assertFalse(config.npLevelCheck.get())
        assertFalse(config.score62Check.get())
        assertFalse(config.grandScore16Check.get())
        visible(np, false)

        compose.runOnIdle { config.preferredServants.set(setOf("Alpha")) }
        compose.waitForIdle()
        visible(np, true)
        assertFalse(config.npLevelCheck.get())
        assertFalse(config.score62Check.get())
    }

    @Test
    fun `turning grand off turns only the grand score off`() {
        val (prefs, config) = support("chk613_grandoff")
        config.preferredServants.set(setOf("Alpha"))
        config.grandServant.set(true)
        config.npLevelCheck.set(true)
        config.score62Check.set(true)
        config.grandScore16Check.set(true)
        render(prefs)
        compose.runOnIdle { config.grandServant.set(false) }
        compose.waitForIdle()
        assertFalse(config.grandScore16Check.get())
        assertTrue(config.npLevelCheck.get())
        assertTrue(config.score62Check.get())
        visible(grand16, false)
    }
}
