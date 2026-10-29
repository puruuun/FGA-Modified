package io.github.fate_grand_automata.ui.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum

@Composable
fun ceEnhanceLauncher(modifier: Modifier = Modifier): ScriptLauncherResponseBuilder {
    Column(modifier.padding(horizontal = 16.dp).padding(top = 5.dp)) {
        Text(stringResource(R.string.ce_enhancement), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.ce_enhancement_filter_note),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        HorizontalDivider()
    }
    return ScriptLauncherResponseBuilder({ true }, { EnhancementLauncherPolicy.response(ScriptModeEnum.CEEnhance) })
}
