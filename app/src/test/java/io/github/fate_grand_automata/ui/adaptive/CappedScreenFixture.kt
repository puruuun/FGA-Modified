package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.ui.battle_config_list.BattleConfigListContent
import io.github.fate_grand_automata.ui.card_priority.CardPriorityListItem
import io.github.fate_grand_automata.ui.card_priority.CardPriorityView
import io.github.fate_grand_automata.ui.fine_tune.AddedFeatureTuningScreen
import io.github.fate_grand_automata.ui.fine_tune.AddedFeatureTuningViewModel
import io.github.fate_grand_automata.ui.fine_tune.FineTuneScreen
import io.github.fate_grand_automata.ui.fine_tune.FineTuneSettingsViewModel
import io.github.fate_grand_automata.ui.log.LogScreenContent
import io.github.fate_grand_automata.ui.main.MainScreenContent
import io.github.fate_grand_automata.ui.more.LicenseScreen
import io.github.fate_grand_automata.ui.more.MoreOptionsContent
import io.github.fate_grand_automata.ui.more.WeeklyRouteSettingsScreen
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.spam.SpamScreen
import io.github.fate_grand_automata.ui.spam.SpamScreenViewModel
import io.github.fate_grand_automata.util.StorageProvider

enum class CappedScreen {
    BattleConfigList, CardPriority, MoreOptions, WeeklyRouteSettings, License,
    FineTune, AddedFeatureTuning, PreferredSupport, Spam, Main, Logs
}

/** Waits for the weekly screen's local cache read, so neither identity checks nor shots race it. */
internal fun ComposeContentTestRule.awaitCappedScreen(screen: CappedScreen) {
    if (screen == CappedScreen.WeeklyRouteSettings) {
        waitUntil(5_000) {
            onAllNodes(hasText("JP")).fetchSemanticsNodes().isNotEmpty() &&
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                    .fetchSemanticsNodes().isEmpty()
        }
    }
}

/** Real screens and in-memory prefs; no services, Hilt activity, device, or network access. */
internal class CappedScreenFixture(context: Context) {
    private val prefs = PrefsCore(PrefMaker(context.getSharedPreferences("width_cap", Context.MODE_PRIVATE)), context)
    private val configs = listOf("Farming AP20", "Event lottery", "Grail run", "Dailies").mapIndexed { i, name ->
        BattleConfigCore("width_cap_$i", context).apply {
            this.name.set(name)
            if (i >= 2) folder.set("Events")
        }
    }
    private val config = configs.first()
    private val cardPriorities = config.cardPriority.get().take(3).map { scores ->
        CardPriorityListItem(
            scores = scores.toMutableStateList(),
            servantPriority = emptyList<TeamSlot>().toMutableStateList(),
            servantCards = emptyList<ServantCardKey>().toMutableStateList(),
            rearrangeCards = mutableStateOf(false),
            braveChains = mutableStateOf(BraveChainEnum.None),
            style = mutableStateOf(CardPriorityStyle.Affinity),
            useServantPriority = mutableStateOf(false)
        )
    }.toMutableStateList()
    private val fineTune = FineTuneSettingsViewModel(prefs)
    private val addedFeatureTuning = AddedFeatureTuningViewModel(prefs)
    private val support = SupportViewModel(StorageProvider(prefs, context))
    private val spam = SpamScreenViewModel(PreferencesImpl(prefs, CompletedRunsHolder()), prefs, config.id)

    @Composable
    fun Render(screen: CappedScreen) {
        when (screen) {
            CappedScreen.BattleConfigList -> BattleConfigListContent(
                configs = configs, selectionMode = false, selectedConfigs = emptySet(),
                expandedFolders = setOf("events"), folderRevision = 0, action = {}
            )
            CappedScreen.CardPriority -> CardPriorityView(cardPriorities, config.cardPrioritySplit)
            CappedScreen.MoreOptions -> MoreOptionsContent(
                prefs = prefs, storageSummary = "FGA", extractSummary = "", extractSupportImages = {},
                additionalFeatures = {}, goToFineTune = {}, goToAddedFeatureTuning = {},
                goToLicense = {}, goToWeeklySettings = {}, pickDirectory = {}
            )
            CappedScreen.WeeklyRouteSettings -> WeeklyRouteSettingsScreen(prefs)
            CappedScreen.License -> LicenseScreen()
            CappedScreen.FineTune -> FineTuneScreen(fineTune)
            CappedScreen.AddedFeatureTuning -> AddedFeatureTuningScreen(addedFeatureTuning)
            CappedScreen.PreferredSupport -> PreferredSupport(config.support, support)
            CappedScreen.Spam -> SpamScreen(spam)
            CappedScreen.Main -> MainScreenContent(
                navigate = {}, overlayServiceStarted = false, toggleOverlayService = {},
                accessibilityServiceStarted = false, toggleAccessibilityService = {}
            )
            CappedScreen.Logs -> LogScreenContent(emptyList(), onDelete = {}, onClear = {})
        }
    }
}
