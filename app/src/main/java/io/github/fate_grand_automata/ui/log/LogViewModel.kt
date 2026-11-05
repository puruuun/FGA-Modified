package io.github.fate_grand_automata.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.capture.OutsideLogRepository
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
    @ApplicationContext context: Context,
) : ViewModel() {
    val entries = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val outsideEntries = OutsideLogRepository.entries

    init { viewModelScope.launch(Dispatchers.IO) { OutsideLogRepository.init(context.filesDir) } }

    fun delete(id: String) { viewModelScope.launch {
        if (id.startsWith("outside:")) withContext(Dispatchers.IO) { OutsideLogRepository.delete(id.removePrefix("outside:")) }
        else repository.delete(id)
    } }
    fun clear() { viewModelScope.launch {
        repository.clear()
        withContext(Dispatchers.IO) { OutsideLogRepository.clear() }
    } }
}
