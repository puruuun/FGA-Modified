package io.github.fate_grand_automata.ui.battle_config_list

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.ui.*
import io.github.fate_grand_automata.ui.battle_config_item.Material
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.simpleStringRes
import io.github.fate_grand_automata.util.stringRes

@Composable
fun BattleConfigListScreen(
    vm: BattleConfigListViewModel = viewModel(),
    navigate: (String) -> Unit
) {
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedConfigs by vm.selectedConfigs.collectAsState()

    BackHandler(
        enabled = selectionMode,
        onBack = { vm.endSelection() }
    )

    val context = LocalContext.current

    val battleConfigsExport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { dirUri ->
        vm.exportBattleConfigs(context, dirUri)
    }

    val battleConfigImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        vm.importBattleConfigs(context, uris)
    }

    val deleteConfirmDialog = FgaDialog()
    deleteConfirmDialog.build {
        title(stringResource(R.string.battle_config_list_delete_confirm_title))
        message(stringResource(R.string.battle_config_list_delete_confirm_message, selectedConfigs.size))

        buttons(
            onSubmit = { vm.deleteSelected() }
        )
    }

    val configs by vm.battleConfigItems.collectAsState(emptyList())
    val expandedFolders by vm.expandedFolders.collectAsState()
    val folderRevision by vm.folderRevision.collectAsState()

    var showMoveDialog by remember { mutableStateOf(false) }
    if (showMoveDialog && selectionMode) {
        val existingFolders = remember(configs, folderRevision) {
            ConfigFolders.group(configs.map { FolderItem(it.id, it.name.get(), it.folder.get()) })
                .filterIsInstance<ConfigEntry.Folder>()
                .map { it.displayName }
        }
        MoveToFolderDialog(
            existingFolders = existingFolders,
            onDismiss = { showMoveDialog = false },
            onApply = {
                showMoveDialog = false
                vm.moveSelectedToFolder(it)
            }
        )
    }

    BattleConfigListContent(
        configs = configs,
        selectionMode = selectionMode,
        selectedConfigs = selectedConfigs,
        expandedFolders = expandedFolders,
        folderRevision = folderRevision,
        action = {
            when (it) {
                BattleConfigListAction.MoveToFolder -> showMoveDialog = true
                is BattleConfigListAction.ToggleFolder -> vm.toggleFolder(it.key)
                BattleConfigListAction.AddNew -> {
                    navigate(vm.newConfig().id)
                }

                BattleConfigListAction.Delete -> deleteConfirmDialog.show()
                is BattleConfigListAction.Edit -> {
                    navigate(it.id)
                }

                BattleConfigListAction.Export -> battleConfigsExport.launch(Uri.EMPTY)
                BattleConfigListAction.Import -> battleConfigImport.launch(
                    //octet-stream as backup in case Android doesn't detect json
                    arrayOf("application/json", "application/octet-stream")
                )

                is BattleConfigListAction.ToggleSelected -> vm.toggleSelected(it.id)
                is BattleConfigListAction.StartSelection -> vm.startSelection(it.id)
            }
        }
    )
}

internal sealed class BattleConfigListAction {
    object Export : BattleConfigListAction()
    object Import : BattleConfigListAction()
    object Delete : BattleConfigListAction()
    object AddNew : BattleConfigListAction()
    object MoveToFolder : BattleConfigListAction()
    class ToggleFolder(val key: String) : BattleConfigListAction()
    class ToggleSelected(val id: String) : BattleConfigListAction()
    class StartSelection(val id: String) : BattleConfigListAction()
    class Edit(val id: String) : BattleConfigListAction()
}

@SuppressLint("UnrememberedMutableState")
@Composable
internal fun BattleConfigListContent(
    configs: List<BattleConfigCore>,
    selectionMode: Boolean,
    selectedConfigs: Set<String>,
    expandedFolders: Set<String>,
    folderRevision: Int,
    action: (BattleConfigListAction) -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box {
        CappedWidth {
            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Heading(
                        stringResource(R.string.p_battle_config)
                    ) {
                        HeadingButton(
                            text = stringResource(
                                if (selectionMode)
                                    R.string.battle_config_item_export
                                else R.string.battle_config_list_export_all
                            ),
                            onClick = { action(BattleConfigListAction.Export) }
                        )

                        Crossfade(selectionMode) {
                            if (it) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HeadingButton(
                                        text = stringResource(R.string.battle_config_folder_move),
                                        onClick = { action(BattleConfigListAction.MoveToFolder) },
                                        modifier = Modifier.padding(end = 5.dp)
                                    )
                                    HeadingButton(
                                        text = stringResource(R.string.battle_config_list_delete),
                                        onClick = { action(BattleConfigListAction.Delete) },
                                        isDanger = true,
                                        icon = icon(Icons.Default.Delete)
                                    )
                                }
                            } else {
                                HeadingButton(
                                    text = stringResource(R.string.battle_config_list_import),
                                    onClick = { action(BattleConfigListAction.Import) }
                                )
                            }
                        }
                    }

                    val servers by derivedStateOf {
                        configs
                            .mapNotNull { it.server.get().asGameServer() }
                            .distinct()
                    }

                    if (servers.isEmpty()) {
                        ConfigList(
                            configs = configs,
                            selectionMode = selectionMode,
                            action = action,
                            selectedConfigs = selectedConfigs,
                            expandedFolders = expandedFolders,
                            folderRevision = folderRevision,
                            modifier = Modifier
                                .weight(1f)
                        )
                    } else {
                        Tabbed(
                            items = listOf<BattleConfigCore.Server>(BattleConfigCore.Server.NotSet) + servers.map { BattleConfigCore.Server.Set(it) },
                            heading = {
                                Text(
                                    when (it) {
                                        BattleConfigCore.Server.NotSet -> "ALL"
                                        is BattleConfigCore.Server.Set -> stringResource(it.server.stringRes)
                                    }
                                )
                            },
                            content = { current ->
                                val filteredConfigs by derivedStateOf {
                                    configs
                                        .filter {
                                            val server = it.server.get().asGameServer()

                                            current is BattleConfigCore.Server.NotSet
                                                    || server == current.asGameServer()
                                        }
                                }

                                ConfigList(
                                    configs = filteredConfigs,
                                    selectionMode = selectionMode,
                                    action = action,
                                    selectedConfigs = selectedConfigs,
                                    expandedFolders = expandedFolders,
                                    folderRevision = folderRevision
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(if (isLandscape) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            val enterAnimation = if (isLandscape)
                slideInHorizontally(initialOffsetX = { it / 2 })
            else slideInVertically(initialOffsetY = { it / 2 })

            val exitAnimation = if (isLandscape)
                slideOutHorizontally(targetOffsetX = { it * 2 })
            else slideOutVertically(targetOffsetY = { it * 2 })

            AnimatedVisibility(
                !selectionMode,
                enter = enterAnimation,
                exit = exitAnimation
            ) {
                FloatingActionButton(
                    onClick = { action(BattleConfigListAction.AddNew) },
                    modifier = Modifier
                        .scale(if (isLandscape) 0.7f else 1f),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create new config",
                        modifier = Modifier
                            .size(40.dp)
                            .padding(7.dp)
                    )
                }
            }
        }
    }
}

internal const val ConfigGridTag = "battle_config_grid"
internal const val ServerHeaderTagPrefix = "server_header_"
internal const val FolderBoxTagPrefix = "config-folder-box:"

private val TwoColumnBreakpoint = 600.dp
private val GridHorizontalPadding = 16.dp
private val GridTopPadding = 16.dp
private val ColumnGap = 12.dp
private val RowGap = 10.dp
private val FolderBoxCornerRadius = 12.dp
private val FolderBoxPadding = 8.dp

@Composable
private fun ConfigList(
    configs: List<BattleConfigCore>,
    selectionMode: Boolean,
    action: (BattleConfigListAction) -> Unit,
    selectedConfigs: Set<String>,
    expandedFolders: Set<String>,
    folderRevision: Int,
    modifier: Modifier = Modifier
) {
    val configsByServer = remember(configs, folderRevision) {
        configs.groupBy { it.server.get().asGameServer() }.map { (server, list) ->
            val byId = list.associateBy { it.id }
            val entries = ConfigFolders.group(list.map { FolderItem(it.id, it.name.get(), it.folder.get()) })
            Triple(server, entries, byId)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= TwoColumnBreakpoint

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (twoColumns) 2 else 1),
            modifier = Modifier
                .fillMaxSize()
                .testTag(ConfigGridTag),
            contentPadding = PaddingValues(
                start = GridHorizontalPadding,
                top = GridTopPadding,
                end = GridHorizontalPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalArrangement = Arrangement.spacedBy(RowGap)
        ) {
            if (configs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.battle_config_list_no_items),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                configsByServer.forEach { (server, entries, byId) ->
                    if (configsByServer.size > 1) {
                        val serverKey = server?.serialize() ?: "not_set"
                        stickyHeader(key = "header:$serverKey") {
                            ServerGroupHeader(
                                text = stringResource(server?.simpleStringRes ?: R.string.p_not_set),
                                tag = "$ServerHeaderTagPrefix$serverKey"
                            )
                        }
                    }

                    @Composable
                    fun configItem(core: BattleConfigCore, modifier: Modifier = Modifier) {
                        BattleConfigListItem(
                            core,
                            onClick = {
                                if (selectionMode) {
                                    action(BattleConfigListAction.ToggleSelected(core.id))
                                } else {
                                    action(BattleConfigListAction.Edit(core.id))
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    action(BattleConfigListAction.StartSelection(core.id))
                                }
                            },
                            isSelectionMode = selectionMode,
                            isSelected = selectionMode && core.id in selectedConfigs,
                            modifier = modifier
                        )
                    }

                    entries.forEach { entry ->
                        when (entry) {
                            is ConfigEntry.Folder -> {
                                val expanded = entry.key in expandedFolders
                                item(
                                    key = "folder:${server?.toString()}:${entry.key}",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    val selectedCount = if (selectionMode && !expanded)
                                        entry.items.count { it.id in selectedConfigs }
                                    else 0
                                    val folderConfigs = if (expanded) {
                                        entry.items.mapNotNull { byId[it.id] }
                                    } else emptyList()
                                    FolderBox(
                                        folderKey = entry.key,
                                        name = entry.displayName,
                                        count = entry.items.size,
                                        expanded = expanded,
                                        selectedCount = selectedCount,
                                        twoColumns = twoColumns,
                                        configs = folderConfigs,
                                        onToggle = { action(BattleConfigListAction.ToggleFolder(entry.key)) },
                                        configItem = { core, itemModifier -> configItem(core, itemModifier) }
                                    )
                                }
                            }

                            is ConfigEntry.Unfiled -> item(key = entry.item.id) {
                                byId[entry.item.id]?.let { configItem(it) }
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(
                            modifier = Modifier.height(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderBox(
    folderKey: String,
    name: String,
    count: Int,
    expanded: Boolean,
    selectedCount: Int,
    twoColumns: Boolean,
    configs: List<BattleConfigCore>,
    onToggle: () -> Unit,
    configItem: @Composable (BattleConfigCore, Modifier) -> Unit
) {
    val shape = RoundedCornerShape(FolderBoxCornerRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$FolderBoxTagPrefix$folderKey")
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), shape)
            .padding(FolderBoxPadding),
        verticalArrangement = Arrangement.spacedBy(RowGap)
    ) {
        FolderRow(
            name = name,
            count = count,
            expanded = expanded,
            selectedCount = selectedCount,
            onClick = onToggle
        )
        if (expanded) {
            val columnCount = if (twoColumns) 2 else 1
            configs.chunked(columnCount).forEach { rowConfigs ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ColumnGap)
                ) {
                    rowConfigs.forEach { core ->
                        configItem(core, Modifier.weight(1f))
                    }
                    if (rowConfigs.size < columnCount) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerGroupHeader(
    text: String,
    tag: String
) {
    Column(
        modifier = Modifier
            .fullBleedHorizontal(GridHorizontalPadding)
            .testTag(tag)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondary)
            .padding(horizontal = 32.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Left,
            color = MaterialTheme.colorScheme.onSecondary
        )
    }
}

private fun Modifier.fullBleedHorizontal(horizontalPadding: Dp): Modifier =
    layout { measurable, constraints ->
        val paddingPx = horizontalPadding.roundToPx()
        val expandedConstraints = if (constraints.hasBoundedWidth) {
            constraints.copy(
                minWidth = (constraints.minWidth + paddingPx * 2).coerceAtLeast(0),
                maxWidth = constraints.maxWidth + paddingPx * 2
            )
        } else {
            constraints
        }
        val placeable = measurable.measure(expandedConstraints)
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-paddingPx, 0)
        }
    }

@Composable
private fun BattleConfigItemSelected(
    isSelectionMode: Boolean,
    isSelected: Boolean
) {
    AnimatedVisibility(isSelectionMode) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(end = 16.dp)
                .border(
                    1.dp,
                    if (isSelected) Color.Transparent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    CircleShape
                )
                .background(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent
                )
                .size(15.dp)
        ) {
            AnimatedVisibility(isSelected) {
                Icon(
                    rememberVectorPainter(Icons.Default.Check),
                    contentDescription = "Select",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier
                        .size(10.dp)
                )
            }
        }
    }
}

@Composable
private fun BattleConfigListItem(
    it: BattleConfigCore,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name by it.name.remember()
    val materialsSet by it.materials.remember()
    val mats = materialsSet.take(3)

    // Without this, holding a list item would leave it highlighted because of recomposition happening before ripple ending
    val longClickState = rememberUpdatedState(onLongClick)

    Card(
        shape = RoundedCornerShape(25),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 5.dp else 1.dp),
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { longClickState.value.invoke() }
                )
                .padding(16.dp, 5.dp)
        ) {
            BattleConfigItemSelected(
                isSelectionMode = isSelectionMode,
                isSelected = isSelected
            )

            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            mats.forEach {
                Material(it)
            }
        }
    }
}