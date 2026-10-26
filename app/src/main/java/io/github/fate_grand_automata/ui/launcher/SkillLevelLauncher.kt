package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.util.skillLevelCheckAvailable

/**
 * D-10: one target level per slot, 0 = off (the default), 1 to 10 = enhance up to that level and never above.
 * Beta check mode ignores targets and opens with its checkbox off every time.
 */
@Composable
fun skillLevelLauncher(
    prefs: IPreferences,
    mode: SkillLevelMode,
    modifier: Modifier = Modifier
): ScriptLauncherResponseBuilder {
    val targets = remember { mutableStateListOf(*IntArray(mode.slots).toTypedArray()) }
    var checkOnly by remember { mutableStateOf(false) }
    val off = stringResource(R.string.skill_level_off)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 5.dp)
    ) {
        Text(
            stringResource(if (mode == SkillLevelMode.Skill) R.string.p_script_mode_skill_level else R.string.p_script_mode_append_level),
            style = MaterialTheme.typography.titleLarge
        )

        HorizontalDivider(
            modifier = Modifier
                .padding(5.dp)
                .padding(bottom = 8.dp)
        )

        if (skillLevelCheckAvailable()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(checkOnly, role = Role.Checkbox) { checkOnly = it }
            ) {
                Checkbox(checked = checkOnly, onCheckedChange = null)
                Text(stringResource(R.string.skill_level_check_only))
            }
        }

        if (!checkOnly) targets.forEachIndexed { index, value ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(
                    stringResource(R.string.skill_level_slot_target, index + 1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Stepper(
                    value = value,
                    onValueChange = { targets[index] = it },
                    valueRange = 0..10,
                    valueRepresentation = { if (it == 0) off else it.toString() }
                )
            }
        }
    }

    return ScriptLauncherResponseBuilder(
        canBuild = { checkOnly || targets.any { it > 0 } },
        build = {
            if (mode == SkillLevelMode.Skill) ScriptLauncherResponse.SkillLevel(targets.toList(), checkOnly)
            else ScriptLauncherResponse.AppendLevel(targets.toList(), checkOnly)
        }
    )
}
