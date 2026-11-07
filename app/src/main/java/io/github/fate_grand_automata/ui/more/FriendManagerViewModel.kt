package io.github.fate_grand_automata.ui.more

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FriendManagerViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    /** The current account's roster, or null until a friend list was seen. */
    val roster: StateFlow<FriendRoster?> = combine(CaptureTracker.currentId, CaptureTracker.rosters) { id, rosters ->
        id?.let { rosters[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val names = MutableStateFlow(GameNameMap())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            CaptureTracker.init(context)
            val cache = GameNames(File(context.filesDir, "capture/names"))
            roster.collectLatest { current ->
                if (current == null) return@collectLatest
                val slots = current.friends.values.flatMap { it.slots }
                names.value = cache.friends(slots.map { it.svtId }.toSet(), slots.map { it.ceId }.filter { it != 0L }.toSet())
            }
        }
    }
}
