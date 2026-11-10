package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.RefillResourceEnum
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.ui.battle_config_list.ConfigEntry
import io.github.fate_grand_automata.ui.battle_config_list.ConfigFolders
import io.github.fate_grand_automata.ui.battle_config_list.FolderItem
import io.github.fate_grand_automata.ui.scrollbar
import io.github.fate_grand_automata.util.stringRes

/** The battle configs usable on the current game server, in the order the launcher lists them. */
fun eligibleBattleConfigs(prefs: IPreferences): List<IBattleConfig> = prefs.battleConfigs
    .filter {
        when (it.server) {
            // always show if no server is set
            null -> true
            // ignore betterFgo for En and Jp
            is GameServer.En -> prefs.gameServer is GameServer.En
            is GameServer.Jp -> prefs.gameServer is GameServer.Jp
            GameServer.Cn, GameServer.Kr, GameServer.Tw -> it.server == prefs.gameServer
        }
    }

@Composable
fun battleLauncher(
    prefs: IPreferences,
    modifier: Modifier = Modifier,
    prefsCore: io.github.fate_grand_automata.prefs.core.PrefsCore,
    showLimits: Boolean = true
): ScriptLauncherResponseBuilder {
    val captureRunningAtOpen = remember { io.github.fate_grand_automata.capture.CaptureTracker.running.value }
    val configs = remember { eligibleBattleConfigs(prefs) }
    // Each config's name lives in its own SharedPreferences file, so reading it per row would mean
    // a locked SharedPreferences access for every item scrolled into view. Snapshot them once.
    val configNames = remember(configs) { configs.map { it.name } }

    val configFolders = remember(configs) { configs.map { prefsCore.forBattleConfig(it.id).folder.get() } }
    val folderEntries = remember(configs) {
        ConfigFolders.group(configs.indices.map { FolderItem(configs[it].id, configNames[it], configFolders[it]) })
    }

    var selectedConfigId by remember {
        mutableStateOf(prefs.selectedBattleConfig.id.takeIf { id -> configs.any { it.id == id } })
    }
    // Every folder starts collapsed except the one that holds the selected config.
    var expandedFolders by remember {
        mutableStateOf(
            folderEntries.filterIsInstance<ConfigEntry.Folder>()
                .filter { folder -> folder.items.any { it.id == selectedConfigId } }
                .map { it.key }
                .toSet()
        )
    }
    val visibleRows = remember(folderEntries, expandedFolders) {
        buildList {
            folderEntries.forEach { entry ->
                when (entry) {
                    is ConfigEntry.Folder -> {
                        add(LauncherRow.FolderHeader(entry))
                        if (entry.key in expandedFolders) {
                            entry.items.forEach { add(LauncherRow.Config(it, indent = true)) }
                        }
                    }
                    is ConfigEntry.Unfiled -> add(LauncherRow.Config(entry.item, indent = false))
                }
            }
        }
    }

    val perServerConfigPref by remember {
        mutableStateOf(
            prefs.getPerServerConfigPref(prefs.gameServer)
        )
    }

    var refillResources by remember { mutableStateOf(perServerConfigPref.resources.toSet()) }

    val hideSQInAPResources by remember { mutableStateOf(prefs.hideSQInAPResources) }
    if (hideSQInAPResources) {
        refillResources = refillResources.minus(RefillResourceEnum.SQ)
    }

    //TODO remove
    if (refillResources.size > 1) {
        refillResources = setOf(refillResources.first())
    }

    val availableRefills = RefillResourceEnum.entries
        .filterNot { it == RefillResourceEnum.SQ && hideSQInAPResources }


    var copperApple by remember { mutableIntStateOf(perServerConfigPref.copperApple) }
    var blueApple by remember { mutableIntStateOf(perServerConfigPref.blueApple) }
    var silverApple by remember { mutableIntStateOf(perServerConfigPref.silverApple) }
    var goldApple by remember { mutableIntStateOf(perServerConfigPref.goldApple) }
    var rainbowApple by remember { mutableIntStateOf(perServerConfigPref.rainbowApple) }

    val refillCount by remember {
        mutableStateOf(
            derivedStateOf {
                when {
                    RefillResourceEnum.Copper in refillResources -> copperApple
                    RefillResourceEnum.Bronze in refillResources -> blueApple
                    RefillResourceEnum.Silver in refillResources -> silverApple
                    RefillResourceEnum.Gold in refillResources -> goldApple
                    RefillResourceEnum.SQ in refillResources -> rainbowApple
                    else -> 0
                }
            }
        )
    }

    var shouldLimitRuns by remember { mutableStateOf(perServerConfigPref.shouldLimitRuns) }
    var limitRuns by remember { mutableIntStateOf(perServerConfigPref.limitRuns) }
    var shouldLimitMats by remember { mutableStateOf(perServerConfigPref.shouldLimitMats) }
    var limitMats by remember { mutableIntStateOf(perServerConfigPref.limitMats) }
    var shouldLimitCEs by remember { mutableStateOf(perServerConfigPref.shouldLimitCEs) }
    var limitCEs by remember { mutableIntStateOf(perServerConfigPref.limitCEs) }
    var waitApRegen by remember { mutableStateOf(perServerConfigPref.waitForAPRegen) }
    var useStormPod by remember { mutableStateOf(perServerConfigPref.useStormPod) }
    var useTeapot by remember { mutableStateOf(perServerConfigPref.useTeapot) }
    val ignoreQuestItemUsage = remember { prefs.ignoreQuestItemUsage }
    val questItemsSupported = remember { prefs.gameServer is GameServer.Jp || prefs.gameServer is GameServer.Kr }
    val questItemsEnabled = !ignoreQuestItemUsage && questItemsSupported

    data class BondDraft(val track: Boolean, val stop: Boolean, val ignored: Set<Int>)
    val bondDrafts = remember { androidx.compose.runtime.mutableStateMapOf<String, BondDraft>() }
    val selected = configs.firstOrNull { it.id == selectedConfigId }
    val bondCore = remember(selected?.id) { selected?.let { prefsCore.forBattleConfig(it.id).bond } }
    val bond = selected?.let { config -> bondDrafts[config.id] ?: bondCore!!.let {
        BondDraft(it.track.get(), it.stopOnMax.get(), it.ignoredCharacters.get())
    } }
    fun changeBond(value: BondDraft) { selected?.let { bondDrafts[it.id] = value } }

    var resetAllButton by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            perServerConfigPref.shouldLimitRuns = shouldLimitRuns
            perServerConfigPref.limitRuns = limitRuns
            perServerConfigPref.shouldLimitMats = shouldLimitMats
            perServerConfigPref.limitMats = limitMats
            perServerConfigPref.shouldLimitCEs = shouldLimitCEs
            perServerConfigPref.limitCEs = limitCEs
            perServerConfigPref.copperApple = copperApple
            perServerConfigPref.blueApple = blueApple
            perServerConfigPref.silverApple = silverApple
            perServerConfigPref.goldApple = goldApple
            perServerConfigPref.rainbowApple = rainbowApple
            perServerConfigPref.waitForAPRegen = waitApRegen
            perServerConfigPref.useStormPod = useStormPod
            perServerConfigPref.useTeapot = useTeapot
            if (refillResources.isNotEmpty()) {
                perServerConfigPref.selectedApple = refillResources.first()
            }
            perServerConfigPref.updateResources(refillResources)

            configs.firstOrNull { it.id == selectedConfigId }?.let {
                prefs.selectedBattleConfig = it
            }

            prefs.resetCompletedRuns()
        }
    }

    LaunchedEffect(shouldLimitRuns, limitRuns, shouldLimitMats, limitMats, shouldLimitCEs, limitCEs) {
        resetAllButton = shouldLimitRuns || limitRuns > 1 ||
                shouldLimitMats || limitMats > 1 ||
                shouldLimitCEs || limitCEs > 1
    }

    Row(
        modifier = modifier
            .padding(start = 5.dp, end = 5.dp, top = 5.dp)
    ) {
        if (configs.isNotEmpty()) {
            // Scrolling the selected config into view
            val configListState = rememberLazyListState()
            LaunchedEffect(true) {
                val index = visibleRows.indexOfFirst { it is LauncherRow.Config && it.item.id == selectedConfigId }
                if (index != -1) {
                    configListState.scrollToItem(index)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .scrollbar(
                        state = configListState,
                        hiddenAlpha = 0.3f,
                        horizontal = false,
                        knobColor = MaterialTheme.colorScheme.secondary
                    ),
                state = configListState
            ) {
                items(visibleRows, key = { it.key }) { row ->
                    when (row) {
                        is LauncherRow.FolderHeader -> {
                            val expanded = row.folder.key in expandedFolders
                            val heldName = if (!expanded) {
                                row.folder.items.firstOrNull { it.id == selectedConfigId }?.name
                            } else null
                            LauncherFolderRow(
                                name = row.folder.displayName,
                                expanded = expanded,
                                selectedName = heldName,
                                onClick = {
                                    expandedFolders = if (expanded) expandedFolders - row.folder.key
                                    else expandedFolders + row.folder.key
                                }
                            )
                        }

                        is LauncherRow.Config -> BattleConfigItem(
                            name = row.item.name,
                            isSelected = selectedConfigId == row.item.id,
                            onSelected = { selectedConfigId = row.item.id },
                            modifier = if (row.indent) Modifier.padding(start = 16.dp) else Modifier
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.battle_config_list_no_items),
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(5.dp, 2.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                .width(1.dp)
        ) { }

        val mainConfigState = rememberLazyListState()
        LazyColumn(
            modifier = Modifier
                .weight(1.5f)
                .scrollbar(
                    state = mainConfigState,
                    hiddenAlpha = 0.3f,
                    horizontal = false,
                    knobColor = MaterialTheme.colorScheme.secondary,
                    // needs to be adjusted when adding new items
                    fixedKnobRatio = 0.55f
                )
                .padding(start = 5.dp),
            state = mainConfigState
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${stringResource(R.string.p_refill)}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Stepper(
                        value = refillCount.value,
                        onValueChange = { value ->
                            when {
                                RefillResourceEnum.Copper in refillResources -> copperApple = value
                                RefillResourceEnum.Bronze in refillResources -> blueApple = value
                                RefillResourceEnum.Silver in refillResources -> silverApple = value
                                RefillResourceEnum.Gold in refillResources -> goldApple = value
                                RefillResourceEnum.SQ in refillResources -> rainbowApple = value
                            }
                        },
                        valueRange = 0..999,
                        enabled = refillResources.isNotEmpty()
                    )
                }
            }

            item {
                LazyRow(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {

                    items(availableRefills) {
                        it.RefillResource(
                            isSelected = it in refillResources,
                            toggle = {
                                // TODO change back to refillResources.toggle()

                                // if the tapped resource is the only one in the list, disable it. otherwise only select the tapped resource
                                refillResources = if (it in refillResources) emptySet() else setOf(it)
                            }
                        )
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { waitApRegen = !waitApRegen }
                ) {
                    Checkbox(
                        checked = waitApRegen,
                        onCheckedChange = { waitApRegen = it },
                        modifier = Modifier
                            .alpha(if (waitApRegen) 1f else 0.7f)
                            .padding(end = 5.dp)
                    )

                    Text(
                        stringResource(R.string.p_wait_ap_regen_text),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
            }

            if (!ignoreQuestItemUsage) item {
                Text(
                    stringResource(R.string.p_quest_item_usage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuestItemCheckbox(
                        useStormPod, questItemsEnabled, stringResource(R.string.p_use_storm_pod),
                        modifier = Modifier.weight(1f), maxLines = 2
                    ) { useStormPod = it }
                    QuestItemCheckbox(
                        useTeapot, questItemsEnabled, stringResource(R.string.p_use_teapot),
                        modifier = Modifier.weight(1f), maxLines = 2
                    ) { useTeapot = it }
                }
                Text(
                    stringResource(when {
                        !questItemsSupported -> R.string.p_quest_item_usage_servers
                        else -> R.string.p_quest_item_usage_summary
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
            }

            item {
                if (prefs.bondTrackingFeatureEnabled && bond != null) {
                    Text(stringResource(R.string.p_bond), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 8.dp))
                    if (!captureRunningAtOpen) {
                        QuestItemCheckbox(bond.track, true, stringResource(R.string.p_bond_track)) { changeBond(bond.copy(track = it)) }
                    }
                    QuestItemCheckbox(bond.stop, true, stringResource(R.string.p_bond_stop)) { changeBond(bond.copy(stop = it)) }
                    if (bond.stop) {
                        Text(stringResource(R.string.p_bond_ignore_characters), style = MaterialTheme.typography.bodySmall)
                        Row {
                            for (slot in 1..5) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.p_bond_position, slot), style = MaterialTheme.typography.bodySmall)
                                    Checkbox(checked = slot in bond.ignored, onCheckedChange = { checked ->
                                        changeBond(bond.copy(ignored = if (checked) bond.ignored + slot else bond.ignored - slot))
                                    })
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                }
            }

            if (showLimits) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.p_limit).uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Card(
                                shape = CircleShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                enabled = resetAllButton,
                                onClick = {
                                    shouldLimitRuns = false
                                    limitRuns = 1
                                    shouldLimitCEs = false
                                    limitCEs = 1
                                    shouldLimitMats = false
                                    limitMats = 1
                                }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .padding(10.dp, 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.reset_all).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        color = if (resetAllButton) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer.copy(0.38f)
                                    )
                                }
                            }
                        }

                    }

                }

                item {
                    LimitItem(
                        shouldLimit = shouldLimitRuns,
                        onShouldLimitChange = { shouldLimitRuns = it },
                        text = stringResource(R.string.p_runs),
                        count = limitRuns,
                        onCountChange = { limitRuns = it }
                    )
                }

                item {
                    LimitItem(
                        shouldLimit = shouldLimitMats,
                        onShouldLimitChange = { shouldLimitMats = it },
                        text = stringResource(R.string.p_mats),
                        count = limitMats,
                        onCountChange = { limitMats = it }
                    )
                }

                item {
                    LimitItem(
                        shouldLimit = shouldLimitCEs,
                        onShouldLimitChange = { shouldLimitCEs = it },
                        text = stringResource(R.string.p_ces),
                        count = limitCEs,
                        onCountChange = { limitCEs = it }
                    )
                }
            }
        }
    }

    return ScriptLauncherResponseBuilder(
        canBuild = { selectedConfigId != null },
        build = {
            selected?.let { prefs.selectedBattleConfig = it }
            bond?.let { draft ->
                bondCore!!.track.set(draft.track)
                bondCore.stopOnMax.set(draft.stop)
                bondCore.ignoredCharacters.set(draft.ignored)
            }
            perServerConfigPref.useStormPod = useStormPod
            perServerConfigPref.useTeapot = useTeapot
            ScriptLauncherResponse.Battle
        }
    )
}

private sealed class LauncherRow(val key: String) {
    class FolderHeader(val folder: ConfigEntry.Folder) : LauncherRow("folder:${folder.key}")
    class Config(val item: FolderItem, val indent: Boolean) : LauncherRow(item.id)
}

@Composable
private fun LauncherFolderRow(
    name: String,
    expanded: Boolean,
    selectedName: String?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(3.dp)
            .clickable(onClick = onClick)
            .padding(8.dp, 3.dp)
            .fillMaxWidth()
    ) {
        androidx.compose.material3.Icon(
            if (expanded) androidx.compose.material.icons.Icons.Default.KeyboardArrowDown
            else androidx.compose.material.icons.Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            if (selectedName != null) "$name · $selectedName" else name,
            style = MaterialTheme.typography.titleSmall,
            color = if (selectedName != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuestItemCheckbox(
    checked: Boolean,
    enabled: Boolean,
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    maxLines: Int = Int.MAX_VALUE,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable(enabled = enabled) { onChange(!checked) }
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange, modifier = Modifier.padding(end = 5.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LimitItem(
    shouldLimit: Boolean,
    onShouldLimitChange: (Boolean) -> Unit,
    text: String,
    count: Int,
    onCountChange: (Int) -> Unit,
    valueRange: IntRange = 1..999
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onShouldLimitChange(!shouldLimit) }
        ) {
            Checkbox(
                checked = shouldLimit,
                onCheckedChange = onShouldLimitChange,
                modifier = Modifier
                    .alpha(if (shouldLimit) 1f else 0.7f)
            )

            Text(
                "$text:",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Stepper(
            value = count,
            onValueChange = onCountChange,
            valueRange = valueRange,
            enabled = shouldLimit
        )
    }
}

@Composable
fun BattleConfigItem(
    name: String,
    isSelected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(3.dp)
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onSelected)
            .padding(11.dp, 3.dp)
            .fillMaxWidth()
    ) {
        Text(
            name,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.Unspecified
        )
    }
}

@Composable
fun RefillResourceEnum.RefillResource(
    isSelected: Boolean,
    toggle: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 3.dp)
            .padding(end = 6.dp)
            .border(
                width = 1.dp,
                brush = SolidColor(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = toggle)
            .padding(5.dp, 3.dp)
    ) {
        Text(
            stringResource(stringRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified
        )
    }
}
