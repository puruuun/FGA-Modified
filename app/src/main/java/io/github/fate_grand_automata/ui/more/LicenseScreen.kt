package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.Heading

/** Name of the repository LICENSE as bundled into the APK assets by the `copyLicenseAsset` task. */
const val LICENSE_ASSET = "LICENSE"

@Composable
fun LicenseScreen() {
    val context = LocalContext.current
    val licenseText = remember {
        try {
            context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Heading(stringResource(R.string.p_license))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.license_header),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = licenseText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
