package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.capture.FriendFilter
import io.github.fate_grand_automata.scripts.capture.FriendSkillLevel

private fun FriendSkillLevel.label() = when (this) {
    FriendSkillLevel.All -> R.string.friends_filter_all
    FriendSkillLevel.Ten -> R.string.friends_skill_ten
    FriendSkillLevel.NotTen -> R.string.friends_skill_not_ten
}

private fun FriendFilter.withSlot(append: Boolean, index: Int, level: FriendSkillLevel): FriendFilter {
    val levels = List(if (append) 5 else 3) {
        if (it == index) level else (if (append) appendLevels else skillLevels).getOrElse(it) { FriendSkillLevel.All }
    }
    return if (append) copy(appendLevels = levels) else copy(skillLevels = levels)
}

@Composable
internal fun SkillFilterSlots(filter: FriendFilter, change: (FriendFilter) -> Unit) {
    listOf(false, true).forEach { append ->
        val prefix = if (append) "append" else "skill"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (append) R.string.friends2_appends else R.string.friends2_skills),
                Modifier.width(48.dp), style = MaterialTheme.typography.titleSmall)
            repeat(if (append) 5 else 3) { index ->
                val state = (if (append) filter.appendLevels else filter.skillLevels).getOrElse(index) { FriendSkillLevel.All }
                var menu by remember { mutableStateOf(false) }
                val slot = stringResource(if (append) R.string.friends_append_slot else R.string.friends_skill_slot, index + 1)
                val stateLabel = stringResource(state.label())
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("friends-$prefix-${index + 1}")
                            .semantics { contentDescription = "$slot $stateLabel" },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state == FriendSkillLevel.All)
                            MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                            Text(stateLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    DropdownMenu(menu, { menu = false }) {
                        FriendSkillLevel.entries.forEach { level ->
                            DropdownMenuItem(text = { Text(stringResource(level.label())) },
                                modifier = Modifier.testTag("friends-$prefix-${index + 1}-${level.name}"),
                                onClick = { change(filter.withSlot(append, index, level)); menu = false })
                        }
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SkillFilterChips(filter, change) }
}

/** Used both in the panel and in the applied-filter row. Each chip clears exactly one slot. */
@Composable
internal fun SkillFilterChips(filter: FriendFilter, change: (FriendFilter) -> Unit) {
    listOf(false, true).forEach { append ->
        (if (append) filter.appendLevels else filter.skillLevels).forEachIndexed { index, level ->
            if (level != FriendSkillLevel.All) {
                val slot = stringResource(if (append) R.string.friends_append_slot else R.string.friends_skill_slot, index + 1)
                RemovableFilter("$slot ${stringResource(level.label())}") { change(filter.withSlot(append, index, FriendSkillLevel.All)) }
            }
        }
    }
}
