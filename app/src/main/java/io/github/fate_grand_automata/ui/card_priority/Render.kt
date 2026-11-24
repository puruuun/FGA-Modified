package io.github.fate_grand_automata.ui.card_priority

import io.github.fate_grand_automata.ui.FGASwitchColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.ChainTypeEnum
import io.github.fate_grand_automata.scripts.models.battle.ChainPriorityItem
import io.github.fate_grand_automata.scripts.modules.ChainPriorityAvailability
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.ui.FGAListItemColors
import io.github.fate_grand_automata.ui.drag_sort.DragSort
import io.github.fate_grand_automata.ui.drag_sort.DragSortItemStyle
import io.github.fate_grand_automata.ui.prefs.listDialog
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.stringRes

@Composable
fun CardPriorityListItem.Render(
    cardSwapDetection: Pref<Boolean>,
    chainShown: Boolean = ChainPriorityAvailability.SHOWN
) {
    var style by style
    var useServantPriority by useServantPriority
    var useChainPriority by useChainPriority
    val servantStyle = style == CardPriorityStyle.Servant
    // Hidden until the planner shows the feature (#654); a Servant style entry ignores it like brave chains.
    val chainPriorityOn = chainShown && useChainPriority && !servantStyle

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ReorderPanel(
            header = { PriorityDirectionHeader() },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            if (servantStyle) {
                ServantCardDragSort(servantCards)
            } else {
                CardPriorityDragSort(scores)
            }
        }

        if (servantStyle) {
            Text(
                stringResource(R.string.card_priority_servant_style_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Card(
            modifier = Modifier
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            val styleDialog = listDialog(
                selected = style,
                onSelectedChange = { style = it },
                entries = CardPriorityStyle.entries.associateWith { stringResource(it.stringRes) },
                title = stringResource(R.string.card_priority_style)
            )

            ListItem(
                modifier = Modifier.clickable { styleDialog.show() },
                headlineContent = { Text(stringResource(R.string.card_priority_style)) },
                supportingContent = { Text(stringResource(style.stringRes)) },
                colors = FGAListItemColors()
            )

            // Servant style ignores the rest of this card for this entry only.
            Column(
                modifier = Modifier.alpha(if (servantStyle) 0.38f else 1f)
            ) {
                ListItem(
                    modifier = Modifier
                        .clickable(enabled = !servantStyle) { useServantPriority = !useServantPriority },
                    headlineContent = { Text(stringResource(R.string.p_battle_config_use_servant_priority)) },
                    trailingContent = {
                        Switch(
                            checked = useServantPriority,
                            onCheckedChange = { useServantPriority = it },
                            enabled = !servantStyle,
                            colors = FGASwitchColors()
                        )
                    },
                    colors = FGAListItemColors()
                )

                if (chainShown) {
                    ListItem(
                        modifier = Modifier
                            .clickable(enabled = !servantStyle) { useChainPriority = !useChainPriority },
                        headlineContent = { Text(stringResource(R.string.p_battle_config_use_chain_priority)) },
                        trailingContent = {
                            Switch(
                                checked = useChainPriority,
                                onCheckedChange = { useChainPriority = it },
                                enabled = !servantStyle,
                                colors = FGASwitchColors()
                            )
                        },
                        colors = FGAListItemColors()
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var braveChains by braveChains
                    // A saved Always is shown as what it acts as here (None while hidden, With NP when Chain Priority
                    // is off for the entry). The saved value is kept.
                    val shownBraveChains =
                        ChainPriorityAvailability.effectiveBraveChain(braveChains, chainShown, chainPriorityOn)

                    val braveChainDialog = listDialog(
                        selected = shownBraveChains,
                        // Choosing the shown value again keeps the saved one
                        onSelectedChange = { if (it != shownBraveChains) braveChains = it },
                        entries = BraveChainEnum.entries
                            .filter { chainPriorityOn || it != BraveChainEnum.Always }
                            .associateWith { stringResource(it.stringRes) },
                        title = stringResource(R.string.p_brave_chains)
                    )

                    ListItem(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !servantStyle) { braveChainDialog.show() },
                        headlineContent = { Text(stringResource(R.string.p_brave_chains)) },
                        supportingContent = { Text(stringResource(shownBraveChains.stringRes)) },
                        colors = FGAListItemColors()
                    )

                    var rearrange by rearrangeCards

                    ListItem(
                        modifier = Modifier
                            .weight(1.1f)
                            .clickable(enabled = !servantStyle) { rearrange = !rearrange },
                        headlineContent = { Text(stringResource(R.string.p_rearrange_cards)) },
                        trailingContent = {
                            Checkbox(
                                checked = rearrange,
                                onCheckedChange = { rearrange = it },
                                enabled = !servantStyle
                            )
                        },
                        colors = FGAListItemColors()
                    )
                }
            }
        }

        if (chainPriorityOn) {
            ChainPriority(
                priorities = chainPriority
            )
        }

        if (useServantPriority && !servantStyle) {
            ServantPriority(
                priorities = servantPriority
            )
        }

        Card(
            modifier = Modifier
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            var swapDetection by cardSwapDetection.remember()

            ListItem(
                modifier = Modifier.clickable { swapDetection = !swapDetection },
                headlineContent = { Text(stringResource(R.string.card_swap_detection)) },
                supportingContent = { Text(stringResource(R.string.card_swap_detection_summary)) },
                trailingContent = {
                    Switch(
                        checked = swapDetection,
                        onCheckedChange = { swapDetection = it },
                        colors = FGASwitchColors()
                    )
                },
                colors = FGAListItemColors()
            )
        }
    }
}

@Composable
private fun ServantPriority(
    priorities: SnapshotStateList<TeamSlot>
) {
    ReorderPanel(
        header = {
            Text(
                "Servant Priority".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        DragSort(
            items = priorities,
            key = { it.position },
            style = {
                DragSortItemStyle(
                    backgroundColor = colorResource(
                        when (it.position) {
                            1 -> R.color.colorArts
                            2 -> R.color.colorQuick
                            3 -> R.color.colorBuster
                            4 -> R.color.colorArtsResist
                            5 -> R.color.colorQuickResist
                            else -> R.color.colorBusterResist
                        }
                    ),
                    contentColor = Color.White,
                    text = "${it.position}"
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ChainPriority(
    priorities: SnapshotStateList<ChainPriorityItem>
) {
    ReorderPanel(
        hint = stringResource(R.string.chain_priority_hint),
        header = {
            Text(
                stringResource(R.string.p_chain_priority).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        DragSort(
            items = priorities,
            key = { it.type },
            style = {
                if (it.isEnabled) {
                    DragSortItemStyle(
                        backgroundColor = colorResource(
                            when (it.type) {
                                ChainTypeEnum.Arts -> R.color.colorArts
                                ChainTypeEnum.Quick -> R.color.colorQuick
                                ChainTypeEnum.Buster -> R.color.colorBuster
                                ChainTypeEnum.Mighty -> R.color.colorPrimaryDark
                                ChainTypeEnum.Avoid -> R.color.colorAvoid
                            }
                        ),
                        contentColor = Color.White,
                        text = stringResource(it.type.stringRes)
                    )
                } else {
                    // A chain that is off keeps its place but is never tried.
                    DragSortItemStyle(
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        text = stringResource(it.type.stringRes)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            onClick = { index ->
                val item = priorities[index]
                priorities[index] = item.copy(isEnabled = !item.isEnabled)
            }
        )
    }
}
