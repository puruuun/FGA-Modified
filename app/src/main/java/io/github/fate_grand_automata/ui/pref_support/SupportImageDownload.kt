package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.more.PortraitImportController
import io.github.fate_grand_automata.ui.more.PortraitImportHost
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.portraits.*
import javax.inject.Inject

@HiltViewModel
class SupportImageDownloadViewModel @Inject constructor(
    val storage: StorageProvider,
    importer: ServantPortraitImporter,
) : ViewModel() {
    val controller = PortraitImportController(storage, importer, viewModelScope, updateSelections = false)
}

@Composable
internal fun SupportImageDownload(
    kind: SupportCatalogKind,
    onSaved: (String) -> Unit,
    refresh: () -> Unit,
    vm: SupportImageDownloadViewModel = viewModel(),
) {
    SupportImageDownload(kind, onSaved, refresh, vm.controller, vm.storage)
}

@Composable
internal fun SupportImageDownload(
    kind: SupportCatalogKind,
    onSaved: (String) -> Unit,
    refresh: () -> Unit,
    controller: PortraitImportController,
    storage: StorageProvider,
) {
    SupportImageDownloadButton(kind, controller)
    PortraitImportHost(controller, storage) { report ->
        refresh()
        if (report.kind == kind) onSaved(report.folder)
    }
}

@Composable
internal fun SupportImageDownloadButton(kind: SupportCatalogKind, controller: PortraitImportController) {
    TextButton(onClick = { controller.openPortraitImport(kind) }) {
        Text(stringResource(R.string.support_picker_download))
    }
}