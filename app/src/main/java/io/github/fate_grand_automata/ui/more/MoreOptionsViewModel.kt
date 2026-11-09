package io.github.fate_grand_automata.ui.more

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.migration.ImportSelection
import io.github.fate_grand_automata.util.migration.OriginalFgaImportService
import io.github.fate_grand_automata.util.migration.OriginalImportProgress
import io.github.fate_grand_automata.util.migration.OriginalImportReport
import io.github.fate_grand_automata.util.portraits.ServantPortraitImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MoreOptionsViewModel @Inject constructor(
    val storageProvider: StorageProvider,
    val prefsCore: PrefsCore,
    val gameAreaPreview: GameAreaPreviewManager,
    private val originalImporter: OriginalFgaImportService,
    private val portraitImporter: ServantPortraitImporter,
    private val supportExtraction: SupportImageExtraction,
) : ViewModel() {
    val storageSummary: MutableState<String?> = mutableStateOf(null)
    internal val extractionState = supportExtraction.extractionState
    internal val iconState = supportExtraction.iconState

    private val _originalImport = MutableStateFlow(OriginalImportState())
    val originalImport = _originalImport.asStateFlow()

    val portraitController = PortraitImportController(storageProvider, portraitImporter, viewModelScope)
    fun openPortraitImport() = portraitController.openPortraitImport()
    fun openOriginalImport() { _originalImport.value = OriginalImportState(open = true) }
    fun closeOriginalImport() {
        if (!_originalImport.value.busy) _originalImport.update { it.copy(open = false) }
    }
    fun selectImportMode(selection: ImportSelection) {
        if (!_originalImport.value.busy) _originalImport.update { it.copy(selection = selection, error = false) }
    }
    fun selectImportFolder(scripts: Boolean, uri: Uri?) {
        if (uri == null || _originalImport.value.busy) return
        _originalImport.update {
            if (scripts) it.copy(scripts = uri, error = false) else it.copy(images = uri, error = false)
        }
    }
    fun overwriteImportedImages(overwrite: Boolean) {
        if (!_originalImport.value.busy) _originalImport.update { it.copy(overwriteImages = overwrite) }
    }
    fun importOriginalData() {
        val request = _originalImport.value
        if (!request.canStart) return
        _originalImport.update { it.copy(busy = true, error = false) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    originalImporter.run(request.selection, request.scripts, request.images, request.overwriteImages) { progress ->
                        ensureActive()
                        _originalImport.update { it.copy(progress = progress) }
                    }
                }
                _originalImport.update { it.copy(report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Original FGA import failed")
                _originalImport.update { it.copy(error = true) }
            } finally {
                _originalImport.update { it.copy(busy = false) }
            }
        }
    }

    init {
        storageSummary.value = storageProvider.rootDirName
    }

    /** Only extracts the default support images; the icon download is a separate action. */
    fun performSupportImageExtraction() = supportExtraction.startExtraction()

    fun downloadLogIcons() = supportExtraction.startIconDownload()

    fun pickedDirectory(uri: Uri?) {
        if (uri != null) {
            storageProvider.setRoot(uri)

            storageSummary.value = storageProvider.rootDirName
        }
    }
}

data class OriginalImportState(
    val open: Boolean = false,
    val selection: ImportSelection = ImportSelection.Both,
    val scripts: Uri? = null,
    val images: Uri? = null,
    val overwriteImages: Boolean = false,
    val busy: Boolean = false,
    val progress: OriginalImportProgress? = null,
    val report: OriginalImportReport? = null,
    val error: Boolean = false
) {
    val canStart get() = !busy && report == null &&
        (!selection.scripts || scripts != null) && (!selection.images || images != null)
}
