package io.github.fate_grand_automata.ui.more

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.CaptureAccount
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.captureFeaturesAvailable
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.rememberChaldeaAccountExport
import io.github.fate_grand_automata.util.OpenDocTreePersistable

@Composable
fun AdditionalTools(vm: MoreOptionsViewModel = viewModel()) {
    val context = LocalContext.current
    val accounts by CaptureTracker.accounts.collectAsStateWithLifecycle()
    LaunchedEffect(context) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CaptureTracker.init(context) } }
    val export = rememberChaldeaAccountExport()
    val originalImport by vm.originalImport.collectAsStateWithLifecycle()
    PortraitImportHost(vm.portraitController, vm.storageProvider, onDirectoryPicked = vm::pickedDirectory)
    val importScripts = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.selectImportFolder(scripts = true, uri = it)
    }
    val importImages = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.selectImportFolder(scripts = false, uri = it)
    }
    if (originalImport.open) {
        OriginalFgaImportDialog(
            state = originalImport,
            destinationName = vm.storageProvider.rootDirName,
            onDismiss = vm::closeOriginalImport,
            onSelection = vm::selectImportMode,
            onPickScripts = { importScripts.launch(originalImport.scripts ?: Uri.EMPTY) },
            onPickImages = { importImages.launch(originalImport.images ?: Uri.EMPTY) },
            onOverwrite = vm::overwriteImportedImages,
            onImport = vm::importOriginalData
        )
    }

    val captureEnabled by vm.prefsCore.captureTrackerEnabled.asFlow().collectAsStateWithLifecycle(initialValue = vm.prefsCore.captureTrackerEnabled.get())
    AdditionalToolsContent(accounts, vm::openPortraitImport, vm::openOriginalImport, export, captureEnabled)
}

@Composable
internal fun AdditionalToolsContent(
    accounts: List<CaptureAccount>,
    importPortraits: () -> Unit,
    importOriginalData: () -> Unit,
    exportAccount: (String) -> Unit,
    captureEnabled: Boolean = true,
) {
    var choosingAccount by rememberSaveable { mutableStateOf(false) }
    val canExport = captureFeaturesAvailable(captureEnabled, Build.VERSION.SDK_INT) && accounts.isNotEmpty()
    Column {
        Preference(title = stringResource(R.string.portrait_import_title), summary = stringResource(R.string.portrait_import_summary),
            icon = icon(Icons.Default.Image), onClick = importPortraits)
        Preference(title = stringResource(R.string.original_import_title), summary = stringResource(R.string.original_import_summary),
            icon = icon(R.drawable.ic_folder_edit), onClick = importOriginalData)
        if (canExport) Preference(title = stringResource(R.string.tools_chaldea_export), icon = icon(Icons.Default.FileUpload),
            onClick = { if (accounts.size == 1) exportAccount(accounts.single().id) else choosingAccount = true })
    }
    if (choosingAccount && canExport) {
        AlertDialog(
            onDismissRequest = { choosingAccount = false },
            title = { Text(stringResource(R.string.tools_choose_account)) },
            text = {
                LazyColumn {
                    items(accounts, key = { it.id }) { account ->
                        Preference(
                            title = account.displayName,
                            onClick = { choosingAccount = false; exportAccount(account.id) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosingAccount = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
