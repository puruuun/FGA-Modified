package io.github.fate_grand_automata.ui.battle_config_item

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.util.portraits.ServantPortraitFolder
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.enums.canAlsoCheckAll
import io.github.fate_grand_automata.ui.DimmedIcon
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.DropdownPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedCEName
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedServantName
import io.github.fate_grand_automata.util.stringRes
import java.io.File

@Composable
fun SupportGroup(
    config: SupportPrefsCore,
    maxSkillText: String,
    goToPreferred: () -> Unit
) {
    val supportMode by config.selectionMode.remember()

    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 16.dp)
        ) {
            PreferenceGroupHeader(
                title = stringResource(R.string.p_battle_config_support)
            )

            var supportClass by config.supportClass.remember()

            SupportClassPicker(
                selected = supportClass,
                onSelectedChange = { supportClass = it }
            )

            val canAlsoCheckAll = supportClass.canAlsoCheckAll && supportMode != SupportSelectionModeEnum.Manual

            AnimatedVisibility(canAlsoCheckAll) {
                config.alsoCheckAll.SwitchPreference(
                    title = stringResource(R.string.p_battle_config_support_also_check_all)
                )
            }

            val preferredMode = supportMode == SupportSelectionModeEnum.Preferred

            Row {
                config.selectionMode.DropdownPreference(
                    title = stringResource(R.string.p_battle_config_support_selection_mode),
                    entries = SupportSelectionModeEnum.entries
                        .associateWith { stringResource(it.stringRes) },
                    modifier = Modifier.weight(1f)
                )

                if (preferredMode) {
                    config.fallbackTo.DropdownPreference(
                        title = stringResource(R.string.p_battle_config_support_fallback_selection_mode),
                        entries = listOf(
                            SupportSelectionModeEnum.First,
                            SupportSelectionModeEnum.Manual
                        ).associateWith { stringResource(it.stringRes) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(preferredMode) {
                Column {
                    val servants by config.preferredServants.remember()
                    val ces by config.preferredCEs.remember()
                    val cesFormatted by remember {
                        derivedStateOf {
                            ces
                                .map { ServantPortraitFolder.displayName(it) }
                                .toSet()
                        }
                    }
                    val friendNames by config.friendNames.remember()
                    val friendNamesFormatted by remember {
                        derivedStateOf {
                            friendNames
                                .map { File(it).nameWithoutExtension }
                                .toSet()
                        }
                    }

                    Preference(
                        title = { Text(stringResource(R.string.p_support_mode_preferred)) },
                        summary = {
                            PreferredSummary(
                                config = config,
                                maxSkillText = maxSkillText,
                                servants = servants,
                                ces = cesFormatted,
                                friendNames = friendNamesFormatted
                            )
                        },
                        onClick = goToPreferred
                    )

                    AnimatedVisibility(servants.isEmpty() && ces.isEmpty()) {
                        PreferenceError(
                            stringResource(R.string.support_selection_preferred_not_set)
                        )
                    }
                }
            }
        }
    }
}

const val SupportClassTagPrefix = "supportClass:"

@Composable
fun SupportClassPicker(
    selected: SupportClass,
    onSelectedChange: (SupportClass) -> Unit
) {
    Card(
        modifier = Modifier
            .padding(16.dp, 5.dp)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        // Exception to the 48dp touch target rule, by owner decision: the ten classes stay on one row as before,
        // each taking an equal share of the width.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp)
        ) {
            SupportClass.entries.drop(1).forEach {
                val isSelected = selected == it
                val transition = updateTransition(isSelected, label = "Selected")
                val alpha by transition.animateFloat(label = "alpha") { selected ->
                    if (selected) 1f else 0.4f
                }
                val borderClass by transition.animateColor(label = "border color") { selected ->
                    if (selected)
                        MaterialTheme.colorScheme.primary
                    else Color.Transparent
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                onSelectedChange(
                                    if (isSelected)
                                        SupportClass.None
                                    else it
                                )
                            }
                        )
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                        .testTag("$SupportClassTagPrefix${it.name}")
                ) {
                    AsyncImage(
                        it.drawable,
                        contentDescription = it.name,
                        contentScale = ContentScale.Crop,
                        alpha = alpha,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 40.dp)
                            .aspectRatio(1f)
                            .border(2.dp, borderClass, DiamondShape)
                            .clip(DiamondShape)
                    )
                }
            }
        }
    }
}

val SupportClass.drawable
    @DrawableRes get() = when (this) {
        SupportClass.None -> R.drawable.ic_dots_horizontal
        SupportClass.All -> R.drawable.support_all
        SupportClass.Saber -> R.drawable.support_saber
        SupportClass.Archer -> R.drawable.support_archer
        SupportClass.Lancer -> R.drawable.support_lancer
        SupportClass.Rider -> R.drawable.support_rider
        SupportClass.Caster -> R.drawable.support_caster
        SupportClass.Assassin -> R.drawable.support_assassin
        SupportClass.Berserker -> R.drawable.support_berserker
        SupportClass.Extra -> R.drawable.support_extra
        SupportClass.Mix -> R.drawable.support_mix
    }

val DiamondShape = CutCornerShape(50)

@Composable
fun PreferredSummary(
    config: SupportPrefsCore,
    maxSkillText: String,
    servants: Set<String>,
    ces: Set<String>,
    friendNames: Set<String>
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    // Kept out of the recomposition path for the same reason as in PreferredSupportScreen:
    // `Set` is unstable to the Compose compiler, so this composable recomposes readily and
    // would otherwise re-localize and re-join both lists every time.
    val servantText = remember(servants, configuration) {
        servants.joinToString { getLocalizedServantName(context, it) }
    }
    val ceText = remember(ces, configuration) {
        ces.joinToString { getLocalizedCEName(context, it) }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 5.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 2.dp)
        ) {
            DimmedIcon(
                icon(R.drawable.ic_crown),
                contentDescription = "crown"
            )

            val text = if (servants.isNotEmpty()) servantText
            else stringResource(R.string.battle_config_support_any)

            Text(
                text,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            )

            if (servants.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(
                        maxSkillText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(5.dp, 1.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 2.dp)
        ) {
            DimmedIcon(
                icon(R.drawable.ic_card),
                contentDescription = "card"
            )

            val text = if (ces.isNotEmpty()) ceText
            else stringResource(R.string.battle_config_support_any)

            Text(
                text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )

            if (ces.isNotEmpty()) {
                val mlb by config.mlb.remember()

                if (mlb) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "MLB",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        val friendsOnly by config.friendsOnly.remember()

        if (friendsOnly) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(vertical = 2.dp)
            ) {
                DimmedIcon(
                    icon(R.drawable.ic_friend),
                    contentDescription = "friend"
                )

                val text = if (friendNames.isNotEmpty())
                    friendNames.joinToString()
                else stringResource(R.string.battle_config_support_any)

                Text(
                    text,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                )
            }
        }
    }
}