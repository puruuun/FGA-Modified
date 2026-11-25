package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import io.github.fate_grand_automata.scripts.weekly.*
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import io.github.fate_grand_automata.ui.prefs.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.runner.OwnerTestScript
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.ui.FgaScreen

@Composable
fun ScriptLauncher(
    scriptMode: ScriptModeEnum,
    onResponse: (ScriptLauncherResponse) -> Unit,
    prefs: IPreferences,
    prefsCore: PrefsCore,
    /** How much wider the window should be for the open right panel: 1 with none. */
    onSidePanelExpanded: (Float) -> Unit = {},
    prepareWeekly: (AtlasRouteRepository.Dataset, List<RouteItem>, List<String>) -> Unit = { _, _, _ -> },
    prepareProbe: (AtlasRouteRepository.Dataset, List<RouteItem>, Boolean) -> Unit = { _, _, _ -> },
    ownerTests: List<OwnerTestScript> = emptyList(),
    /** A test opens a panel from the start. */
    initialPanel: LauncherPanel = LauncherPanel.None,
) {
    var shownMode by remember(scriptMode) { mutableStateOf(scriptMode) }
    // The launcher that is shown decides, not the detected mode: pressing play on the support selection screen detects
    // SupportImageMaker but shows the same battle config list, and the enhancement launchers' switch button changes what is
    // shown. Launchers without a config list look as they did before the filter existed, whatever open state is stored (#719).
    val featureOffered = rememberSupportFilterOffered(prefsCore)
    val filterOffered = featureOffered && shownMode in CONFIG_LIST_MODES
    // The filter panel comes back open when it was open the last time; only read where the launcher offers the filter (#708).
    var openPanel by remember {
        mutableStateOf(
            when {
                initialPanel != LauncherPanel.None -> initialPanel
                filterOffered && prefsCore.supportFilterPanelOpen.get() -> LauncherPanel.SupportFilter
                else -> LauncherPanel.None
            }
        )
    }
    val weekly = remember { WeeklyPanelState() }
    val weeklyEnabled by prefsCore.weeklyRouteFeatureEnabled.remember()
    val filterOn by prefsCore.supportFilterOn.remember()
    var selectedConfigId by remember { mutableStateOf<String?>(null) }
    // The filter and the weekly missions share the right side: opening one closes the other (#695).
    val panel = if (openPanel == LauncherPanel.SupportFilter && !filterOffered) LauncherPanel.None else openPanel
    fun toggle(target: LauncherPanel) {
        openPanel = if (panel == target) LauncherPanel.None else target
        onSidePanelExpanded(openPanel.widen)
        // Opening the weekly panel closes the filter panel, and that is what is remembered.
        if (filterOffered) prefsCore.supportFilterPanelOpen.set(openPanel == LauncherPanel.SupportFilter)
    }
    // A panel that starts open needs the window widened too.
    LaunchedEffect(Unit) { if (panel != LauncherPanel.None) onSidePanelExpanded(panel.widen) }
    val filterPanel: (@Composable (Modifier) -> Unit)? = if (panel == LauncherPanel.SupportFilter) {
        { width -> SupportFilterLauncherPanel(prefsCore, selectedConfigId, width) }
    } else null
    FgaScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            lateinit var responseBuilder: ScriptLauncherResponseBuilder
            Row(Modifier.weight(1f)) {
                val modifier = Modifier.weight(1f).fillMaxHeight()
                val select = { id: String? -> selectedConfigId = id }

                responseBuilder = when (shownMode) {
                    ScriptModeEnum.Battle -> expandedBattleLauncher(
                        prefs, prefsCore, panel == LauncherPanel.Weekly, weekly, prepareWeekly, prepareProbe, select,
                        filterPanel, modifier
                    )
                    ScriptModeEnum.SupportImageMaker ->
                        battleLauncher(prefs, modifier, prefsCore, onSelectedConfig = select, sidePanel = filterPanel)

                    ScriptModeEnum.FP -> fpLauncher(prefs, modifier)
                    ScriptModeEnum.Lottery -> lotteryLauncher(prefs, modifier)
                    ScriptModeEnum.PresentBox -> giftBoxLauncher(prefs, modifier)
                    ScriptModeEnum.CEEnhance -> ceEnhanceLauncher(modifier)
                    ScriptModeEnum.ServantLevel -> servantLevelLauncher(prefsCore.servantEnhancement, modifier)
                    ScriptModeEnum.SkillLevel -> skillLevelLauncher(prefs, SkillLevelMode.Skill, modifier)
                    ScriptModeEnum.AppendLevel -> skillLevelLauncher(prefs, SkillLevelMode.Append, modifier)
                    ScriptModeEnum.WeeklyRoute ->
                        battleLauncher(prefs, modifier, prefsCore, onSelectedConfig = select, sidePanel = filterPanel)
                }
            }

            HorizontalDivider()

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row {
                    EnhancementLauncherPolicy.switchTarget(shownMode, prefs.gameServer)?.let { target ->
                        TextButton(onClick = { shownMode = target }) {
                            Text(stringResource(if (target == ScriptModeEnum.CEEnhance)
                                R.string.switch_to_ce_enhancement else R.string.switch_to_servant_enhancement))
                        }
                    }
                    if (scriptMode == ScriptModeEnum.SupportImageMaker) {
                        TextButton(onClick = { onResponse(ScriptLauncherResponse.SupportImageMaker) }) {
                            Text(stringResource(R.string.p_script_mode_support_image_maker))
                        }
                    }
                    // Issue #568: empty outside the beta and rust build types (BuildConfig.SCORE_SCAN).
                    ownerTests.filter { it.offeredFor(scriptMode) }.forEach { test ->
                        TextButton(onClick = { onResponse(ScriptLauncherResponse.OwnerTest(test)) }) {
                            Text(test.label(LocalContext.current))
                        }
                    }

                    // The weekly mission route starts from the same screens as a battle, so it is offered next to it.
                    if (scriptMode == ScriptModeEnum.Battle && weeklyEnabled &&
                        (prefs.gameServer is GameServer.Jp || prefs.gameServer is GameServer.Kr)
                    ) {
                        PanelButton(
                            selected = panel == LauncherPanel.Weekly,
                            onClick = { toggle(LauncherPanel.Weekly) },
                            label = stringResource(R.string.weekly_route_title),
                        )
                    }

                    if (filterOffered) {
                        PanelButton(
                            selected = panel == LauncherPanel.SupportFilter,
                            onClick = { toggle(LauncherPanel.SupportFilter) },
                            label = stringResource(if (filterOn) R.string.support_filter_chip_on else R.string.support_filter_chip_off),
                            modifier = Modifier.testTag(SupportFilterChipTag),
                        )
                    }
                }

                Row {
                    TextButton(onClick = { onResponse(ScriptLauncherResponse.Cancel) }) {
                        Text(stringResource(android.R.string.cancel))
                    }

                    TextButton(
                        onClick = { onResponse(responseBuilder.build()) },
                        enabled = responseBuilder.canBuild()
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

/**
 * A button that opens a right panel. Both such buttons look the same, and the one whose panel is open is filled in
 * and reports itself as selected (#708).
 */
@Composable
private fun PanelButton(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else ButtonDefaults.textButtonColors(),
        modifier = modifier.semantics { this.selected = selected },
    ) { Text(label) }
}

/**
 * The launcher's right side holds at most one of these. [widen] is how much wider the window gets for it: the weekly
 * panel as before; the filter panel by about one config list column (1 of the launcher's 2.5 parts), so the list and
 * the options keep their width.
 */
enum class LauncherPanel(val widen: Float) { None(1f), Weekly(1.7f), SupportFilter(1.4f) }

/** The script modes whose launcher lists battle configs, and so has a selected one. */
private val CONFIG_LIST_MODES = setOf(ScriptModeEnum.Battle, ScriptModeEnum.SupportImageMaker, ScriptModeEnum.WeeklyRoute)

/** Adds a sibling to the unchanged left launcher; both use its original shared OK/Cancel row. */
@Composable
private fun expandedBattleLauncher(
    prefs: IPreferences,
    core: PrefsCore,
    open: Boolean,
    weekly: WeeklyPanelState,
    prepare: (AtlasRouteRepository.Dataset, List<RouteItem>, List<String>) -> Unit,
    prepareProbe: (AtlasRouteRepository.Dataset, List<RouteItem>, Boolean) -> Unit,
    onSelectedConfig: (String?) -> Unit,
    filterPanel: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier
): ScriptLauncherResponseBuilder {
    val context = LocalContext.current
    var left = ScriptLauncherResponseBuilder({ false }, { ScriptLauncherResponse.Cancel })
    Row(modifier) {
        left = battleLauncher(
            prefs, Modifier.weight(1f), core, showLimits = !open, onSelectedConfig = onSelectedConfig, sidePanel = filterPanel
        )
        if (open) WeeklyLauncherPanel(if (prefs.gameServer is GameServer.Kr) "KR" else "JP",
            core, weekly, Modifier.weight(0.7f))
    }
    return ScriptLauncherResponseBuilder(
        canBuild = { (left.canBuild() || WeeklyLauncherPolicy.action(open, weekly.chosen.map { it.count }) == WeeklyLauncherPolicy.Action.Empty) && (!open || !weekly.busy) && (!open || !weekly.probe || weekly.data?.let { weekly.probeItems(it, core.weeklyRoute.disabled.get()).isNotEmpty() } == true) },
        build = {
            if (WeeklyLauncherPolicy.action(open, weekly.chosen.map { it.count }, weekly.probe) == WeeklyLauncherPolicy.Action.Empty) {
                Toast.makeText(context, R.string.weekly_route_no_quests, Toast.LENGTH_SHORT).show()
                return@ScriptLauncherResponseBuilder ScriptLauncherResponse.Cancel
            }
            val data = weekly.data
            if (data != null && open && weekly.probe && weekly.probeItems(data, core.weeklyRoute.disabled.get()).isNotEmpty()) {
                prepareProbe(data, weekly.probeItems(data, core.weeklyRoute.disabled.get()), weekly.skipSucceeded)
                ScriptLauncherResponse.WeeklyRoute
            } else if (data != null && !weekly.probe && WeeklyLauncherPolicy.weekly(open, weekly.chosen.map { it.count })) {
                prepare(data, weekly.chosen, weekly.manual)
                ScriptLauncherResponse.WeeklyRoute
            } else left.build()
        }
    )
}
