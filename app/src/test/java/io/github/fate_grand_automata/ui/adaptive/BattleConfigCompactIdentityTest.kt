package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalAdaptiveLayout
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards that a phone-sized window renders the battle config exactly like the legacy single
 * column, and that a wide window really differs. Runs in CI (the name does not end in
 * `ScreenshotTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BattleConfigCompactIdentityTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Renders the screen once with the legacy path and once with the default, in one composition. */
    private fun renderBoth(widthDp: Int, heightDp: Int): Pair<Bitmap, Bitmap> {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-hdpi")
        val config = BattleConfigCore("identity_$widthDp", context).apply {
            name.set("Sample farming config")
            notes.set("Wave 3 boss: use NP chain")
        }
        var adaptive by mutableStateOf(false)

        composeRule.setContent {
            FGATheme(darkTheme = false) {
                CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                    BattleConfigContent(
                        config = config,
                        maxSkillText = "",
                        onExport = {},
                        onCopy = {},
                        onDelete = {},
                        navigate = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val legacy = capture()

        adaptive = true
        composeRule.waitForIdle()
        return legacy to capture()
    }

    private fun capture(): Bitmap =
        composeRule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    private fun Bitmap.identicalTo(other: Bitmap) = width == other.width && height == other.height && sameAs(other)

    @Test
    fun compactWindowIsIdenticalToLegacyLayout() {
        val (legacy, default) = renderBoth(360, 800)

        assertTrue("360 dp render must match the single-column layout", legacy.identicalTo(default))
    }

    @Test
    fun wideWindowDiffersFromLegacyLayout() {
        val (legacy, default) = renderBoth(1280, 800)

        assertFalse("1280 dp render must use two columns", legacy.identicalTo(default))
    }
}
