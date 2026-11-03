package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.LocalAdaptiveLayout
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.card_priority.CardPriorityView
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
import io.github.fate_grand_automata.ui.screenshotRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layout adapts to the window with no switch, so a phone must look exactly as it did before.
 * This renders the screens the adaptive layout touches at 360x800 dp once with the legacy
 * full-width single-column path ([LocalAdaptiveLayout] false) and once with the adaptive one
 * (true) and asserts the pixels are identical. It is named so that Main CI runs it, unlike the
 * `*ScreenshotTest` classes, which only record images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CompactLayoutIdentityTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun render(widthDp: Int, heightDp: Int, content: @Composable () -> Unit): Pair<androidx.compose.ui.graphics.PixelMap, androidx.compose.ui.graphics.PixelMap> {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-hdpi")

        var adaptive by mutableStateOf(false)
        composeRule.setContent {
            FGATheme(darkTheme = false) {
                CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                    content()
                }
            }
        }

        composeRule.waitForIdle()
        val legacy = composeRule.onRoot().captureToImage().toPixelMap()

        adaptive = true
        composeRule.waitForIdle()
        val adapted = composeRule.onRoot().captureToImage().toPixelMap()

        return legacy to adapted
    }

    private fun assertIdentical(content: @Composable () -> Unit) {
        val (legacy, adapted) = render(WidthDp, HeightDp, content)

        assertEquals("width", legacy.width, adapted.width)
        assertEquals("height", legacy.height, adapted.height)
        assertTrue("the image is not empty", legacy.width > 0 && legacy.height > 0)
        assertTrue(
            "a 360 dp window renders differently with the adaptive layout",
            legacy.buffer.contentEquals(adapted.buffer)
        )
    }

    /** Guards the check itself: a 1280 dp window must differ, or the comparison proves nothing. */
    @Test
    fun adaptiveLayoutChangesAWideWindow() {
        val config = sampleConfig(context, "identity_wide_control")

        val (legacy, adapted) = render(1280, HeightDp) {
            BattleConfigContent(
                config = config,
                maxSkillText = "",
                onExport = {},
                onCopy = {},
                onDelete = {},
                navigate = {}
            )
        }

        assertTrue("a wide window should render differently", !legacy.buffer.contentEquals(adapted.buffer))
    }

    @Test
    fun battleConfig() {
        val config = sampleConfig(context, "identity_battle_config")

        assertIdentical {
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

    @Test
    fun battleConfigList() {
        val configs = sampleConfigList(context, "identity_list")

        assertIdentical {
            BattleConfigListContent(
                configs = configs,
                selectionMode = false,
                selectedConfigs = emptySet(),
                expandedFolders = setOf("events"),
                folderRevision = 0,
                action = {}
            )
        }
    }

    @Test
    fun cardPriority() {
        val config = sampleConfig(context, "identity_card_priority")
        val items = sampleCardPriorityItems(config)

        assertIdentical {
            CardPriorityView(
                items = items,
                cardPrioritySplit = config.cardPrioritySplit,
                profileIndex = 0
            )
        }
    }

    @Test
    fun moreOptions() {
        val prefs = samplePrefs(context)

        assertIdentical {
            MoreOptionsContent(
                prefs = prefs,
                storageSummary = "FGA",
                extractSummary = "",
                extractSupportImages = {},
                additionalFeatures = {},
                goToFineTune = {},
                goToAddedFeatureTuning = {},
                goToLicense = {},
                goToWeeklySettings = {},
                pickDirectory = {}
            )
        }
    }

    private companion object {
        const val WidthDp = 360
        const val HeightDp = 800
    }
}
