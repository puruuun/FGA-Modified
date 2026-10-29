package io.github.fate_grand_automata.ui.fine_tune

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.Heading

@Composable
fun AddedFeatureTuningScreen(vm: AddedFeatureTuningViewModel) {
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(R.string.added_tuning_title))
        TextButton(onClick = vm::resetAll, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.added_tuning_reset_all))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item {
                Text(stringResource(R.string.added_tuning_summary), modifier = Modifier.padding(bottom = 16.dp))
            }
            vm.groups.forEach { group ->
                item {
                    Text(stringResource(group.name), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.reset(group) }) {
                        Text(stringResource(R.string.added_tuning_reset_section))
                    }
                }
                group.items.forEach { setting ->
                    item {
                        Card(
                            modifier = Modifier.padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            setting.FineTuneSetter()
                        }
                    }
                }
            }
        }
    }
}
