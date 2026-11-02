package io.github.fate_grand_automata.ui.skill_maker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.ui.skill_maker.utils.TargetButton

enum class EnemyLayout { Normal, Six, Raid }

/** The layout a target code belongs to; the dialog opens on it. No target opens the normal layout. */
internal fun enemyLayoutOf(code: String?): EnemyLayout = when {
    code == null -> EnemyLayout.Normal
    EnemyTarget.sixList.any { it.autoSkillCode == code } -> EnemyLayout.Six
    EnemyTarget.raidList.any { it.autoSkillCode == code } -> EnemyLayout.Raid
    else -> EnemyLayout.Normal
}

@Composable
internal fun EnemyTargetDialog(
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var layout by rememberSaveable { mutableStateOf(enemyLayoutOf(selected)) }

    @Composable
    fun Target(target: EnemyTarget, modifier: Modifier = Modifier, width: Dp = 120.dp) {
        val isSelected = target.autoSkillCode == selected
        Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
            TargetButton(
                onClick = { onSelect(target.autoSkillCode) },
                color = colorResource(if (isSelected) R.color.colorAccent else R.color.colorEnemyTarget),
                text = "t${target.autoSkillCode}",
                width = width
            )
        }
    }

    @Composable
    fun TargetRow(targets: List<EnemyTarget>) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            targets.forEach { Target(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_maker_enemy_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Laid out like the battle screen
                when (layout) {
                    EnemyLayout.Normal -> TargetRow(EnemyTarget.list)
                    EnemyLayout.Six -> {
                        TargetRow(EnemyTarget.sixList.take(3))
                        TargetRow(EnemyTarget.sixList.drop(3))
                    }
                    EnemyLayout.Raid -> {
                        Target(EnemyTarget.RaidBoss, Modifier.fillMaxWidth(), width = 376.dp)
                        TargetRow(EnemyTarget.raidList.drop(1))
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    listOf(
                        EnemyLayout.Normal to R.string.skill_maker_enemy_layout_normal,
                        EnemyLayout.Six to R.string.skill_maker_enemy_layout_six,
                        EnemyLayout.Raid to R.string.skill_maker_enemy_layout_raid
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = layout == value,
                            onClick = { layout = value },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
