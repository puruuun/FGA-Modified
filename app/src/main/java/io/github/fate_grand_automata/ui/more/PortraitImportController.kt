package io.github.fate_grand_automata.ui.more

import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.portraits.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** Shared search/save state for Additional tools and the support pickers. */
class PortraitImportController(
    private val scope: CoroutineScope,
    private val hasStorage: () -> Boolean,
    private val search: suspend (SupportCatalogKind, String, String) -> List<SupportCatalogEntry>,
    private val save: suspend (SupportCatalogEntry, (PortraitImportProgress) -> Unit) -> PortraitImportReport,
) {
    constructor(storage: StorageProvider, importer: ServantPortraitImporter, scope: CoroutineScope,
                updateSelections: Boolean = true) : this(
        scope, { storage.rootDirName != null }, importer::search,
        { entry, progress -> importer.run(entry, updateSelections, progress) }
    )
    private val _portraitImport = MutableStateFlow(PortraitImportState())
    val portraitImport = _portraitImport.asStateFlow()

    fun openPortraitImport(kind: SupportCatalogKind = SupportCatalogKind.Servant) { _portraitImport.value = PortraitImportState(open = true, kind = kind) }
    fun closePortraitImport() {
        if (!_portraitImport.value.busy) _portraitImport.update { it.copy(open = false) }
    }
    fun changePortraitKind(kind: SupportCatalogKind) {
        if (!_portraitImport.value.busy) _portraitImport.update {
            PortraitImportState(open = true, kind = kind)
        }
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
        _portraitImport.update { it.copy(busy = true, searching = true, error = null, report = null,
            progress = null, selected = null, candidates = emptyList(), searched = false) }
        scope.launch {
            try {
                val entries = search(request.kind, request.query, language)
                _portraitImport.update { it.copy(candidates = entries, selected = entries.singleOrNull(), searched = true) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Timber.w(e, "Support catalog search failed")
                _portraitImport.update { it.copy(error = (e as? PortraitImportException)?.reason ?: PortraitImportError.Metadata) }
            } finally { _portraitImport.update { it.copy(busy = false, searching = false) } }
        }
    }
    fun importPortraits(onSaved: (PortraitImportReport) -> Unit = {}) {
        val request = _portraitImport.value
        val entry = request.selected ?: return
        if (!request.canStart || !hasStorage()) return
        _portraitImport.update { it.copy(busy = true, error = null, report = null, progress = null) }
        scope.launch {
            try {
                val report = save(entry) { progress ->
                    _portraitImport.update { it.copy(progress = progress) }
                }
                _portraitImport.update { it.copy(report = report) }
                if (report.saved > 0) onSaved(report)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Timber.w(e, "Support image import failed")
                _portraitImport.update { it.copy(error = (e as? PortraitImportException)?.reason ?: PortraitImportError.Metadata) }
            } finally { _portraitImport.update { it.copy(busy = false) } }
        }
    }

}

data class PortraitImportState(
    val open: Boolean = false,
    val kind: SupportCatalogKind = SupportCatalogKind.Servant,
    val query: String = "",
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

