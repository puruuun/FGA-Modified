package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.ui.ScreenshotWidths
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.card_priority.CardPriorityView
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens the adaptive layout touches at 360/600/840/1280 dp, in English and Korean,
 * plus the dark theme of the battle config screen. The layout follows the window, so there is no
 * switch to vary. Real prefs classes over Robolectric's in-memory SharedPreferences stand in for
 * user data, so no real prefs file or DataStore is touched. Images land in
 * `build/outputs/roborazzi/adaptive/`, named `<screen>_<W>x<H>dp[_ko][_dark].png`. Recorded by
 * `:app:recordRoborazziCi`, never part of the CI run. The list-detail panes are in
 * [ListDetailScreenshotTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AdaptiveLayoutScreenshotTest(
    private val widthDp: Int,
    private val locale: String?
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shoot(screen: String, heightDp: Int, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.shoot(
            folder = "adaptive",
            name = screen,
            widthDp = widthDp,
            heightDp = heightDp,
            dark = dark,
            locale = locale,
            content = content
        )
    }

    @Test
    fun battleConfig() {
        val config = sampleConfig(context, "adaptive_battle_config")

        shoot("battle_config", heightDp = 1500) {
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
    fun battleConfigDark() {
        if (locale != null) return // dark mode is covered in English only

        val config = sampleConfig(context, "adaptive_battle_config_dark")

        shoot("battle_config", heightDp = 1500, dark = true) {
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
        val configs = sampleConfigList(context, "adaptive_list")

        shoot("battle_config_list", heightDp = 800) {
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
        val config = sampleConfig(context, "adaptive_card_priority")
        val items = sampleCardPriorityItems(config)

        shoot("card_priority", heightDp = 1000) {
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

        shoot("more_options", heightDp = 1000) {
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

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp locale={1}")
        fun params(): List<Array<Any?>> =
            ScreenshotWidths.flatMap { width ->
                listOf<String?>(null, "ko").map { locale -> arrayOf<Any?>(width, locale) }
            }
    }
}
