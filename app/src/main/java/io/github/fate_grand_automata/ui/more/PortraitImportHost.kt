package io.github.fate_grand_automata.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.util.OpenDocTreePersistable
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.portraits.PortraitImportReport

@Composable
fun PortraitImportHost(
    controller: PortraitImportController,
    storage: StorageProvider,
    onDirectoryPicked: (Uri) -> Unit = storage::setRoot,
    onSaved: (PortraitImportReport) -> Unit = {},
) {
    val state by controller.portraitImport.collectAsStateWithLifecycle()
    var destinationName by remember { mutableStateOf(storage.rootDirName) }
    val pickDirectory = rememberLauncherForActivityResult(OpenDocTreePersistable()) { uri ->
        if (uri != null) {
            onDirectoryPicked(uri)
            destinationName = storage.rootDirName
        }
    }
    LaunchedEffect(state.open) { destinationName = storage.rootDirName }
    val context = LocalContext.current
    val currentOnSaved by rememberUpdatedState(onSaved)
    if (state.open) {
        ServantPortraitImportDialog(
            state = state,
            destinationName = destinationName,
            onNumber = controller::changePortraitNumber,
            onKind = controller::changePortraitKind,
            onSelect = controller::selectPortrait,
            onSearch = { controller.searchPortraits(context.resources.configuration.locales[0].toLanguageTag()) },
            onStart = { controller.importPortraits { currentOnSaved(it) } },
            onPickDirectory = { pickDirectory.launch(Uri.EMPTY) },
            onDismiss = controller::closePortraitImport,
        )
    }
}