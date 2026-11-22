package io.github.fate_grand_automata.ui.pref_support

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.dialog.FaceChip
import io.github.fate_grand_automata.ui.dialog.FaceDeleteMarkTag
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails
import io.github.fate_grand_automata.ui.dialog.ImageDelete
import io.github.fate_grand_automata.ui.prefs.DeleteActionTag
import io.github.fate_grand_automata.ui.prefs.DeleteCloseTag
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.util.SupportImageExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** #627: the chip picker and the long-press delete of support images. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w360dp-h800dp-hdpi")
class SupportPickerChipsTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefsCore(name: String): PrefsCore {
        val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        return PrefsCore(PrefMaker(shared), context)
    }

    // Stands for the user's image folder. It reports back only entries it really removed, like the real storage.
    private class Store(names: Collection<String>, val builtIn: Set<String> = emptySet(), val failing: Set<String> = emptySet()) {
        val files = names.toMutableSet()
        var list by mutableStateOf(names.associateWith { it })
        var deleteCalls = 0

        fun asDelete() = ImageDelete<String>(
            isProtected = { it in builtIn },
            delete = { requested ->
                deleteCalls++
                val gone = requested.filter { it in files && it !in failing }.toSet()
                files -= gone
                list = files.sorted().associateWith { it }
                gone
            }
        )
    }

    private val faces = FaceThumbnails { null }

    private fun open(store: Store, pref: io.github.fate_grand_automata.prefs.core.Pref<Set<String>>) {
        compose.setContent {
            FGATheme { pref.SupportSelectPreference("Servants", store.list, thumbnails = faces, imageDelete = store.asDelete()) }
        }
        compose.onNodeWithText("Servants").performClick()
    }

    private fun chip(name: String) = compose.onNode(hasText(name) and isToggleable() and hasAnyAncestor(isDialog()))
    private fun longPress(name: String) = chip(name).performTouchInput { longClick() }
    // The title row of delete mode has a DELETE button too; the confirmation dialog is the last one shown.
    private fun confirmDelete() = compose.onAllNodes(hasText("DELETE")).onLast().performClick()
    private fun toastText() = ShadowToast.getTextOfLatestToast()

    // Chip layout

    @Test
    fun `a chip toggles its choice and the dialog stores the set on OK`() {
        val pref = prefsCore("chips627_toggle").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha"))
        open(Store(listOf("Alpha", "Beta")), pref)
        chip("Alpha").assertIsOn()
        chip("Beta").assertIsOff().performClick()
        chip("Beta").assertIsOn()
        assertEquals(setOf("Alpha"), pref.get())
        compose.onNodeWithText("OK").performClick()
        assertEquals(setOf("Alpha", "Beta"), pref.get())
    }

    @Test
    fun `a selected chip is filled with the selected colour, an unselected one is not, and a chip wraps its name`() {
        var fill = 0
        compose.setContent {
            FGATheme {
                fill = MaterialTheme.colorScheme.secondaryContainer.toArgb()
                Column {
                    FaceChip(isSelected = true, onClick = {}, modifier = Modifier_tag("on")) { Text("A") }
                    FaceChip(isSelected = false, onClick = {}, modifier = Modifier_tag("off")) { Text("A") }
                }
            }
        }
        // 6dp in from the left edge is inside the chip's own padding, left of the name.
        fun pixel(tag: String): Int {
            val node = compose.onNodeWithTag(tag)
            val bitmap = node.captureToImage().asAndroidBitmap()
            return bitmap.getPixel((6 * 1.5f).toInt(), bitmap.height / 2)
        }
        assertEquals(fill, pixel("on"))
        assertNotEquals(fill, pixel("off"))
        val width = compose.onNodeWithTag("on").fetchSemanticsNode().boundsInRoot.width / 1.5f
        assertTrue("chip is $width dp wide", width < 120f)
        // No selection circle after the name: the chip ends where its padding does.
        assertTrue(compose.onNodeWithTag("on").fetchSemanticsNode().boundsInRoot.height / 1.5f >= 47.5f)
    }

    @Test
    fun `a choice without a face shows only its name`() {
        val pref = prefsCore("chips627_noface").forBattleConfig("t").support.preferredNoblePhantasms
        compose.setContent { FGATheme { pref.SupportSelectPreference("NPs", mapOf("One" to "One")) } }
        compose.onNodeWithText("NPs").performClick()
        compose.onAllNodesWithTag(io.github.fate_grand_automata.ui.dialog.FacePlaceholderTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(io.github.fate_grand_automata.ui.dialog.FaceThumbnailTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(FaceDeleteMarkTag, useUnmergedTree = true).assertCountEquals(0)
        chip("One").assertIsOff()
    }

    private fun bounds(name: String) = chip(name).fetchSemanticsNode().boundsInRoot

    @Test
    fun `servant chips take one line each and fill the dialog width`() {
        val pref = prefsCore("chips627_lines").forBattleConfig("t").support.preferredServants
        open(Store(listOf("A", "B")), pref)
        val a = bounds("A")
        val b = bounds("B")
        assertTrue("same line: ${a.top} ${b.top}", b.top > a.top + 1f)
        assertEquals(a.left, b.left, 0.5f)
        assertEquals(a.width, b.width, 0.5f)
        assertTrue("chip is ${a.width / 1.5f} dp wide", a.width / 1.5f > 250f)
    }

    @Test
    fun `the picker without faces keeps flowing chips on one line`() {
        val pref = prefsCore("chips627_flow").forBattleConfig("t").support.preferredNoblePhantasms
        compose.setContent { FGATheme { pref.SupportSelectPreference("NPs", mapOf("One" to "One", "Two" to "Two")) } }
        compose.onNodeWithText("NPs").performClick()
        val one = bounds("One")
        val two = bounds("Two")
        assertEquals(one.top, two.top, 0.5f)
        assertTrue(two.left > one.right - 1f)
        assertTrue(one.width / 1.5f < 120f)
    }

    // Delete mode

    @Test
    fun `a long press starts delete mode with that chip marked and no tap changes the preference`() {
        val pref = prefsCore("chips627_start").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha"))
        open(Store(listOf("Alpha", "Beta", "Gamma")), pref)
        longPress("Beta")
        compose.onNodeWithText("1 selected").assertExists()
        compose.onAllNodesWithTag(FaceDeleteMarkTag, useUnmergedTree = true).assertCountEquals(1)
        chip("Beta").assertIsOn()
        chip("Gamma").performClick()
        chip("Gamma").assertIsOn()
        compose.onNodeWithText("2 selected").assertExists()
        chip("Alpha").performClick()
        compose.onNodeWithText("3 selected").assertExists()
        assertEquals(setOf("Alpha"), pref.get())
        // The dialog buttons are hidden while deleting.
        compose.onNodeWithText("OK").assertDoesNotExist()
        compose.onNodeWithText("CANCEL").assertDoesNotExist()
        // Taps in delete mode marked chips; they did not tick or untick the choices.
        compose.onNodeWithTag(DeleteCloseTag).performClick()
        chip("Alpha").assertIsOn()
        chip("Beta").assertIsOff()
        chip("Gamma").assertIsOff()
    }

    @Test
    fun `unmarking the last chip leaves delete mode`() {
        val pref = prefsCore("chips627_last").forBattleConfig("t").support.preferredServants
        open(Store(listOf("Alpha", "Beta")), pref)
        longPress("Beta")
        chip("Beta").performClick()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("OK").assertExists()
        chip("Alpha").performClick()
        chip("Alpha").assertIsOn()
    }

    @Test
    fun `the close icon leaves delete mode and deletes nothing`() {
        val pref = prefsCore("chips627_close").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha"))
        val store = Store(listOf("Alpha", "Beta"))
        open(store, pref)
        longPress("Beta")
        compose.onNodeWithTag(DeleteCloseTag).performClick()
        compose.onNodeWithText("OK").assertExists()
        compose.onAllNodesWithTag(FaceDeleteMarkTag, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(0, store.deleteCalls)
        assertEquals(setOf("Alpha", "Beta"), store.files)
        assertEquals(setOf("Alpha"), pref.get())
    }

    @Test
    fun `back leaves delete mode and keeps the dialog, the files and the preference`() {
        val pref = prefsCore("chips627_back").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha"))
        val store = Store(listOf("Alpha", "Beta"))
        open(store, pref)
        longPress("Beta")
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("OK").assertExists()
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        assertEquals(0, store.deleteCalls)
        assertEquals(setOf("Alpha", "Beta"), store.files)
        assertEquals(setOf("Alpha"), pref.get())
    }

    @Test
    fun `delete asks first, then removes the files, the list entries and the stored selection`() {
        val pref = prefsCore("chips627_delete").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha", "Beta"))
        val store = Store(listOf("Alpha", "Beta", "Gamma", "Delta"))
        open(store, pref)
        chip("Delta").performClick() // an unconfirmed tick that must survive the deletion
        longPress("Beta")
        chip("Gamma").performClick()
        compose.onNodeWithTag(DeleteActionTag).performClick()

        compose.onNodeWithText("Are you sure you want to delete 2 image(s)?").assertExists()
        assertEquals(0, store.deleteCalls)
        assertEquals(setOf("Alpha", "Beta", "Gamma", "Delta"), store.files)

        confirmDelete()
        compose.waitForIdle()
        assertEquals(setOf("Alpha", "Delta"), store.files)
        assertEquals(setOf("Alpha"), pref.get())
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithText("Gamma").assertDoesNotExist()
        chip("Delta").assertIsOn()
        compose.onNodeWithText("OK").assertExists()
        compose.onNodeWithText("OK").performClick()
        assertEquals(setOf("Alpha", "Delta"), pref.get())
    }

    @Test
    fun `cancelling the confirmation deletes nothing and stays in delete mode`() {
        val pref = prefsCore("chips627_cancel").forBattleConfig("t").support.preferredServants
        val store = Store(listOf("Alpha", "Beta"))
        open(store, pref)
        longPress("Beta")
        compose.onNodeWithTag(DeleteActionTag).performClick()
        compose.onNodeWithText("CANCEL").performClick()
        assertEquals(0, store.deleteCalls)
        assertEquals(setOf("Alpha", "Beta"), store.files)
        compose.onNodeWithText("1 selected").assertExists()
    }

    @Test
    fun `a failed delete keeps the file and the stored selection, shows an error and leaves delete mode`() {
        val pref = prefsCore("chips627_fail").forBattleConfig("t").support.preferredServants
        pref.set(setOf("Alpha", "Beta"))
        val store = Store(listOf("Alpha", "Beta", "Gamma"), failing = setOf("Beta"))
        open(store, pref)
        longPress("Beta")
        chip("Gamma").performClick()
        compose.onNodeWithTag(DeleteActionTag).performClick()
        confirmDelete()
        compose.waitForIdle()

        assertEquals(setOf("Alpha", "Beta"), store.files)
        assertEquals(setOf("Alpha", "Beta"), pref.get())
        assertEquals(context.getString(R.string.support_picker_delete_failed), toastText())
        compose.onNodeWithText("OK").assertExists()
        compose.onNodeWithText("Gamma").assertDoesNotExist()
        compose.onNodeWithText("Beta").assertExists()
    }

    @Test
    fun `a built-in entry cannot be marked by a long press or a tap`() {
        val pref = prefsCore("chips627_builtin").forBattleConfig("t").support.preferredServants
        val store = Store(listOf("Alpha", "Beta"), builtIn = setOf("Alpha"))
        open(store, pref)
        longPress("Alpha")
        assertEquals(context.getString(R.string.support_picker_builtin_locked), toastText())
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("OK").assertExists()

        longPress("Beta")
        ShadowToast.reset()
        chip("Alpha").performClick()
        assertEquals(context.getString(R.string.support_picker_builtin_locked), toastText())
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithTag(DeleteActionTag).performClick()
        compose.onNodeWithText("Are you sure you want to delete 1 image(s)?").assertExists()
    }

    @Test
    fun `the pickers that list no image files have no delete mode`() {
        val pref = prefsCore("chips627_nodelete").forBattleConfig("t").support.preferredNoblePhantasms
        compose.setContent { FGATheme { pref.SupportSelectPreference("NPs", mapOf("One" to "One")) } }
        compose.onNodeWithText("NPs").performClick()
        longPress("One")
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        compose.onNodeWithText("OK").assertExists()
    }

    // The built-in images the extractor writes back

    @Test
    fun `the built-in entries are the asset servant folders and CE files`() {
        val servants = SupportImageExtractor.builtInEntries(context.assets, SupportImageKind.Servant)
        val ces = SupportImageExtractor.builtInEntries(context.assets, SupportImageKind.CE)
        assertTrue(servants.toString(), "Arcueid" in servants)
        assertTrue(ces.toString(), "Black Grail.png" in ces)
        assertTrue("My own folder" !in servants)
    }

    private fun Modifier_tag(tag: String) = androidx.compose.ui.Modifier.testTag(tag)
}
