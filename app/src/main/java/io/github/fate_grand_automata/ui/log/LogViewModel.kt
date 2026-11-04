package io.github.fate_grand_automata.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(private val repository: BattleLogRepository) : ViewModel() {
    val entries = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) { viewModelScope.launch { repository.delete(id) } }
    fun clear() { viewModelScope.launch { repository.clear() } }
}
