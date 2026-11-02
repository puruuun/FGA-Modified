package io.github.fate_grand_automata.ui.skill_maker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.ui.FGATitle
import io.github.fate_grand_automata.ui.skill_maker.utils.TargetButton

enum class EnemyLayout { Normal, Six, Raid }

/** The layout a target code belongs to; the screen opens on it. No target opens the normal layout. */
internal fun enemyLayoutOf(code: String?): EnemyLayout = when {
    code == null -> EnemyLayout.Normal
    EnemyTarget.sixList.any { it.autoSkillCode == code } -> EnemyLayout.Six
    EnemyTarget.raidList.any { it.autoSkillCode == code } -> EnemyLayout.Raid
    else -> EnemyLayout.Normal
}

// Three rows have to fit a 640x360 dp landscape screen, so the raid buttons are lower.
private val RaidButtonHeight = 48.dp

/** Picking a target is a screen of its own, like the servant target screen. */
@Composable
internal fun SkillMakerEnemyTarget(
    selected: String?,
    onSelect: (String) -> Unit
) {
    var layout by rememberSaveable { mutableStateOf(enemyLayoutOf(selected)) }

    @Composable
    fun Target(target: EnemyTarget, width: Dp = 120.dp, height: Dp = 75.dp) {
        val isSelected = target.autoSkillCode == selected
        TargetButton(
            onClick = { onSelect(target.autoSkillCode) },
            color = colorResource(if (isSelected) R.color.colorAccent else R.color.colorEnemyTarget),
            text = "t${target.autoSkillCode}",
            width = width,
            height = height
        )
    }

    @Composable
    fun TargetRow(targets: List<EnemyTarget>, height: Dp = 75.dp) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            targets.forEach { Target(it, height = height) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        FGATitle(stringResource(R.string.skill_maker_enemy_dialog_title))

        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
            ) {
                // Laid out like the battle screen
                when (layout) {
                    EnemyLayout.Normal -> TargetRow(EnemyTarget.list)
                    EnemyLayout.Six -> {
                        TargetRow(EnemyTarget.sixList.take(3))
                        TargetRow(EnemyTarget.sixList.drop(3))
                    }
                    EnemyLayout.Raid -> {
                        Target(EnemyTarget.RaidBoss, width = 376.dp, height = RaidButtonHeight)
                        TargetRow(EnemyTarget.raidList.subList(1, 4), RaidButtonHeight)
                        TargetRow(EnemyTarget.raidList.drop(4), RaidButtonHeight)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val options = listOf(
                EnemyLayout.Normal to R.string.skill_maker_enemy_layout_normal,
                EnemyLayout.Six to R.string.skill_maker_enemy_layout_six,
                EnemyLayout.Raid to R.string.skill_maker_enemy_layout_raid
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .align(Alignment.TopCenter)
            ) {
                options.forEach { (value, label) ->
                    SegmentedButton(
                        selected = layout == value,
                        onClick = { layout = value },
                        // Square segments without the check mark
                        shape = RectangleShape,
                        icon = {}
                    ) {
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}
