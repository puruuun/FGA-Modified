package io.github.fate_grand_automata.ui.launcher

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureLauncherFixture
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #613: the wait checkbox sits in the AP recovery title row and writes the same value as the old row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [32], qualifiers = "en-w1280dp-h800dp-hdpi")
class LauncherWaitApRegenRowTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules = screenshotRules(compose)

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val server = GameServer.Jp.Original

    private fun open(initial: Boolean, visible: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(true)): CaptureLauncherFixture {
        val fixture = CaptureLauncherFixture(context, server)
        fixture.prefs.getPerServerConfigPref(server).waitForAPRegen = initial
        compose.setContent { Surface(Modifier.fillMaxSize()) { if (visible.value) battleLauncher(fixture.prefs, prefsCore = fixture.core) } }
        compose.waitForIdle()
        return fixture
    }

    @Test
    fun `the checkbox is on the title row, between the label and the stepper`() {
        open(initial = false)
        val title = compose.onNodeWithText("${context.getString(R.string.p_refill)}:").fetchSemanticsNode().boundsInRoot
        val wait = compose.onNodeWithTag(LauncherWaitApRegenTag).fetchSemanticsNode().boundsInRoot
        val stepper = compose.onAllNodesWithText("-").onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("wait ${wait.center.y} not on the title row ${title.top}..${title.bottom}", wait.center.y in title.top..title.bottom)
        assertTrue(wait.left >= title.right)
        assertTrue(wait.right <= stepper.left)
        assertEquals(wait.center.y, stepper.center.y, 4f)
    }

    @Test
    fun `it shows the short label, describes itself with the full text and the old row is gone`() {
        open(initial = false)
        compose.onNodeWithText(context.getString(R.string.p_wait_ap_regen_short)).assertExistsText()
        compose.onNodeWithTag(LauncherWaitApRegenTag)
            .assertContentDescriptionEquals(context.getString(R.string.p_wait_ap_regen_text))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        compose.onNodeWithText(context.getString(R.string.p_wait_ap_regen_text)).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsText() = also { fetchSemanticsNode() }

    @Test
    fun `tapping the box or the label toggles waitForAPRegen and it is stored when the launcher closes`() {
        val visible = mutableStateOf(true)
        val fixture = open(initial = false, visible = visible)
        val wait = compose.onNodeWithTag(LauncherWaitApRegenTag)
        wait.assertIsOff()
        wait.performClick()
        wait.assertIsOn()
        compose.onNodeWithText(context.getString(R.string.p_wait_ap_regen_short)).performClick()
        wait.assertIsOff()
        wait.performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertTrue(fixture.prefs.getPerServerConfigPref(server).waitForAPRegen)
    }

    @Test
    fun `an enabled value is shown on and can be turned off`() {
        val visible = mutableStateOf(true)
        val fixture = open(initial = true, visible = visible)
        val wait = compose.onNodeWithTag(LauncherWaitApRegenTag)
        wait.assertIsOn().performClick()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertFalse(fixture.prefs.getPerServerConfigPref(server).waitForAPRegen)
    }

    private fun styleOf(text: String): androidx.compose.ui.text.TextStyle {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style
    }

    @Test
    fun `the bond ignore header has the style and indent of the bond header`() {
        open(initial = false)
        val bond = context.getString(R.string.p_bond)
        val ignore = context.getString(R.string.p_bond_ignore_characters)
        compose.onNodeWithText(ignore).performScrollTo()
        val bondStyle = styleOf(bond)
        val ignoreStyle = styleOf(ignore)
        assertEquals(bondStyle.fontSize, ignoreStyle.fontSize)
        assertEquals(bondStyle.color, ignoreStyle.color)
        assertEquals(
            compose.onNodeWithText(bond).fetchSemanticsNode().boundsInRoot.left,
            compose.onNodeWithText(ignore).fetchSemanticsNode().boundsInRoot.left, 0.5f
        )
    }

    @Test
    fun `the reset all button is a 32dp button with a 48dp touch target`() {
        open(initial = false)
        val button = compose.onNodeWithTag(LauncherResetAllTag)
        button.performScrollTo()
        val node = button.fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        assertEquals(32f, node.boundsInRoot.height / density, 0.5f)
        assertTrue("touch ${node.touchBoundsInRoot.height / density}", node.touchBoundsInRoot.height / density >= 47.5f)
    }
}
