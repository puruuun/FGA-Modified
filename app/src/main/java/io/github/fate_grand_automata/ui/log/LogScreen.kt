package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading

@Composable
fun LogScreen() {
    CappedWidth {
        Column(modifier = Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.p_logs))
            Text(
                text = stringResource(R.string.logs_coming_soon),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
