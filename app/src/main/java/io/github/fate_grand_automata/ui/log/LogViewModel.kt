package io.github.fate_grand_automata.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.capture.OutsideLogRepository
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: BattleLogRepository,
    private val prefs: PrefsCore,
    @ApplicationContext context: Context,
) : ViewModel() {
    val entries = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val outsideEntries = OutsideLogRepository.entries
    val openOutsideEntries = CaptureTracker.openOutsideEntries
    val retention = prefs.logRetention.limit.asFlow().stateIn(viewModelScope,
        SharingStarted.WhileSubscribed(5_000), prefs.logRetention.limit.get())

    init { viewModelScope.launch(Dispatchers.IO) {
        OutsideLogRepository.init(context.filesDir) { LogRetentionPrefsCore.normalize(prefs.logRetention.limit.get()) }
    } }

    /** The UI calls this only after any destructive lowering has been confirmed. */
    fun setRetention(limit: Int) { viewModelScope.launch(Dispatchers.IO) {
        require(limit in LogRetentionPrefsCore.choices)
        prefs.logRetention.limit.setAndCommit(limit)
        repository.trim()
        OutsideLogRepository.trim()
    } }

    /** Read when the user opens "전투별 내역"; the rows are not kept in memory. */
    suspend fun battlesOf(id: String) = repository.battles(id)

    fun delete(id: String) { viewModelScope.launch {
        if (id.startsWith("outside:")) withContext(Dispatchers.IO) { CaptureTracker.deleteOutsideEntry(id.removePrefix("outside:")) }
        else repository.delete(id)
    } }
    fun clear() { viewModelScope.launch {
        repository.clear()
        withContext(Dispatchers.IO) { CaptureTracker.clearOutsideEntries() }
    } }
}
