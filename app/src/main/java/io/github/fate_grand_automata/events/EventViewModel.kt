package io.github.fate_grand_automata.events

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.GameNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class EventViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val directory = File(context.filesDir, "capture/names")
    private val repository = EventRepository(directory)
    private val gameNames = GameNames(directory)
    val data = MutableStateFlow(EventData())
    val names = MutableStateFlow(GameNameMap())

    fun refresh() = viewModelScope.launch {
        data.value = repository.load()
        data.value = repository.refresh { data.value = it }
    }

    fun loadNames(eventId: Long) = viewModelScope.launch {
        data.value = repository.load()
        names.value = gameNames.eventEquips(data.value.bonuses[eventId].orEmpty().map { it.id })
    }
}
