package io.github.fate_grand_automata.ui.more

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.util.portraits.SupportCatalogEntry
import io.github.fate_grand_automata.util.portraits.SupportCatalogKind
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.SupportImageExtractor
import io.github.fate_grand_automata.util.migration.ImportSelection
import io.github.fate_grand_automata.util.migration.OriginalFgaImportService
import io.github.fate_grand_automata.util.migration.OriginalImportProgress
import io.github.fate_grand_automata.util.migration.OriginalImportReport
import io.github.fate_grand_automata.util.portraits.PortraitImportError
import io.github.fate_grand_automata.util.portraits.PortraitImportException
import io.github.fate_grand_automata.util.portraits.PortraitImportProgress
import io.github.fate_grand_automata.util.portraits.PortraitImportReport
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
    private val originalImporter: OriginalFgaImportService,
    private val portraitImporter: ServantPortraitImporter
) : ViewModel() {
    val storageSummary: MutableState<String?> = mutableStateOf(null)
    val extractSummary: MutableState<String> = mutableStateOf("")

    private val _originalImport = MutableStateFlow(OriginalImportState())
    val originalImport = _originalImport.asStateFlow()

    private val _portraitImport = MutableStateFlow(PortraitImportState())
    val portraitImport = _portraitImport.asStateFlow()

    private fun portraitRegion(): String {
        val server = GameServers.deserialize(prefsCore.gameServerRaw.get()) ?: TapperService.instance?.detectedFgoServer
        return when (val name = server?.simple?.uppercase(java.util.Locale.ROOT)) {
            "EN" -> "NA"
            "JP", "KR", "CN", "TW" -> name
            else -> "JP"
        }
    }
    fun openPortraitImport() { _portraitImport.value = PortraitImportState(open = true, region = portraitRegion()) }
    fun closePortraitImport() {
        if (!_portraitImport.value.busy) _portraitImport.update { it.copy(open = false) }
    }
    fun changePortraitKind(kind: SupportCatalogKind) {
        if (!_portraitImport.value.busy) _portraitImport.update {
            PortraitImportState(open = true, kind = kind, region = it.region)
        }
    }
    fun changePortraitRegion(region: String) {
        if (region !in io.github.fate_grand_automata.util.portraits.SupportImageCatalog.regions || _portraitImport.value.busy) return
        _portraitImport.update { it.copy(region = region, candidates = emptyList(), selected = null, searched = false, report = null, error = null) }
    }
    fun changePortraitNumber(query: String) {
        if (!_portraitImport.value.busy && query.length <= 120) _portraitImport.update {
            it.copy(query = query, error = null, report = null, candidates = emptyList(), selected = null, searched = false)
        }
    }
    fun selectPortrait(entry: SupportCatalogEntry) {
        if (!_portraitImport.value.busy && entry in _portraitImport.value.candidates)
            _portraitImport.update { it.copy(selected = entry, report = null, error = null) }
    }
    fun searchPortraits(language: String) {
        val request = _portraitImport.value
        if (!request.canSearch) return
        val region = request.region
        _portraitImport.update { it.copy(busy = true, searching = true, error = null, report = null,
            progress = null, selected = null, candidates = emptyList(), searched = false, region = region) }
        viewModelScope.launch {
            try {
                val entries = portraitImporter.search(request.kind, request.query, language, region)
                _portraitImport.update { it.copy(candidates = entries, selected = entries.singleOrNull(), searched = true) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Timber.w(e, "Support catalog search failed")
                _portraitImport.update { it.copy(error = (e as? PortraitImportException)?.reason ?: PortraitImportError.Metadata) }
            } finally { _portraitImport.update { it.copy(busy = false, searching = false) } }
        }
    }
    fun importPortraits() {
        val request = _portraitImport.value
        val entry = request.selected ?: return
        if (!request.canStart || storageProvider.rootDirName == null) return
        _portraitImport.update { it.copy(busy = true, error = null, report = null, progress = null) }
        viewModelScope.launch {
            try {
                val report = portraitImporter.run(entry, request.region) { progress ->
                    _portraitImport.update { it.copy(progress = progress) }
                }
                _portraitImport.update { it.copy(report = report) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Timber.w(e, "Support image import failed")
                _portraitImport.update { it.copy(error = (e as? PortraitImportException)?.reason ?: PortraitImportError.Metadata) }
            } finally { _portraitImport.update { it.copy(busy = false) } }
        }
    }

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

    fun performSupportImageExtraction(context: Context) = viewModelScope.launch {
        // TODO: Translate
        extractSummary.value = "Extracting ..."

        val msg = try {
            SupportImageExtractor(context, storageProvider).extract()

            context.getString(R.string.support_imgs_extracted)
        } catch (e: Exception) {
            context.getString(R.string.support_imgs_extract_failed).also { msg ->
                Timber.e(e, msg)
            }
        }

        extractSummary.value = msg
    }

    fun pickedDirectory(uri: Uri?) {
        if (uri != null) {
            storageProvider.setRoot(uri)

            storageSummary.value = storageProvider.rootDirName
        }
    }
}

data class PortraitImportState(
    val open: Boolean = false,
    val kind: SupportCatalogKind = SupportCatalogKind.Servant,
    val query: String = "",
    val region: String = "JP",
    val busy: Boolean = false,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val candidates: List<SupportCatalogEntry> = emptyList(),
    val selected: SupportCatalogEntry? = null,
    val progress: PortraitImportProgress? = null,
    val report: PortraitImportReport? = null,
    val error: PortraitImportError? = null
) {
    val canSearch get() = !busy && query.isNotBlank()
    val canStart get() = !busy && selected != null
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
