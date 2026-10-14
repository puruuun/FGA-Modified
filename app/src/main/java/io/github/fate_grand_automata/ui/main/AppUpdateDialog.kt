package io.github.fate_grand_automata.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.BuildConfig
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.openLinkIntent

@Composable
fun AppUpdateDialog(vm: MainScreenViewModel) {
    val update by vm.availableUpdate
    val context = LocalContext.current
    update?.let { release ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text(stringResource(R.string.github_update_title)) },
            text = {
                Text(stringResource(R.string.github_update_message, BuildConfig.VERSION_NAME, release.version))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissUpdate()
                    context.openLinkIntent(release.releaseUrl)
                }) { Text(stringResource(R.string.github_update_open)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.github_update_later)) }
            }
        )
    }
}
