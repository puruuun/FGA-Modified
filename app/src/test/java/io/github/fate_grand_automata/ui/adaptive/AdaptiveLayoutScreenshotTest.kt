package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.ui.LocalAdaptiveLayout
import io.github.fate_grand_automata.ui.ScreenshotWidths
import io.github.fate_grand_automata.ui.battle_config_item.BattleConfigContent
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.card_priority.CardPriorityListItem
import io.github.fate_grand_automata.ui.card_priority.CardPriorityView
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.screenshotRules
import io.github.fate_grand_automata.ui.shoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the screens the wide-screen layout touches at 360/600/840/1280 dp with the switch off
 * and on, in English and Korean, plus the dark theme of the battle config screen. The switch is
 * provided through [LocalAdaptiveLayout], as the app root does. Real prefs classes over
 * Robolectric's in-memory SharedPreferences stand in for user data, so no real prefs file or
 * DataStore is touched. Images land in `build/outputs/roborazzi/adaptive/`, named
 * `<screen>_<off|on>_<W>x<H>dp[_ko][_dark].png`. Recorded by `:app:recordRoborazziCi`, never part
 * of the CI run.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AdaptiveLayoutScreenshotTest(
    private val widthDp: Int,
    private val adaptive: Boolean,
    private val locale: String?
) {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules = screenshotRules(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shoot(screen: String, heightDp: Int, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.shoot(
            folder = "adaptive",
            name = "${screen}_${if (adaptive) "on" else "off"}",
            widthDp = widthDp,
            heightDp = heightDp,
            dark = dark,
            locale = locale
        ) {
            CompositionLocalProvider(LocalAdaptiveLayout provides adaptive) {
                content()
            }
        }
    }

    private fun sampleConfig(id: String) = BattleConfigCore(id, context).apply {
        name.set("Sample farming config")
        notes.set("Wave 3 boss: use NP chain")
        support.selectionMode.set(SupportSelectionModeEnum.Preferred)
        support.supportClass.set(SupportClass.Caster)
    }

    @Test
    fun battleConfig() {
        val config = sampleConfig("adaptive_battle_config")

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

        val config = sampleConfig("adaptive_battle_config_dark")

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
        val configs = listOf("Farming AP20", "Event lottery", "Grail run", "Dailies").mapIndexed { i, name ->
            BattleConfigCore("adaptive_list_$i", context).apply {
                this.name.set(name)
                if (i >= 2) folder.set("Events")
            }
        }

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
        val config = BattleConfigCore("adaptive_card_priority", context)
        val items: SnapshotStateList<CardPriorityListItem> = config.cardPriority.get()
            .take(3)
            .map { priority ->
                CardPriorityListItem(
                    scores = priority.toMutableStateList(),
                    servantPriority = emptyList<TeamSlot>().toMutableStateList(),
                    servantCards = emptyList<ServantCardKey>().toMutableStateList(),
                    rearrangeCards = mutableStateOf(false),
                    braveChains = mutableStateOf(BraveChainEnum.None),
                    style = mutableStateOf(CardPriorityStyle.Affinity),
                    useServantPriority = mutableStateOf(false)
                )
            }
            .toMutableStateList()

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
        val prefs = PrefsCore(
            PrefMaker(context.getSharedPreferences("adaptive_prefs", Context.MODE_PRIVATE)),
            context
        )

        shoot("more_options", heightDp = 1000) {
            MoreOptionsContent(
                prefs = prefs,
                storageSummary = "FGA",
                extractSummary = "",
                extractSupportImages = {},
                additionalFeatures = {
                    item {
                        prefs.adaptiveLayoutEnabled.SwitchPreference(
                            title = stringResource(R.string.p_adaptive_layout_enabled),
                            summary = stringResource(R.string.p_adaptive_layout_enabled_summary),
                            icon = icon(Icons.Default.AspectRatio)
                        )
                    }
                },
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
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp adaptive={1} locale={2}")
        fun params(): List<Array<Any?>> =
            ScreenshotWidths.flatMap { width ->
                listOf(false, true).flatMap { adaptive ->
                    listOf<String?>(null, "ko").map { locale -> arrayOf<Any?>(width, adaptive, locale) }
                }
            }
    }
}
