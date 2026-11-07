package io.github.fate_grand_automata.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

internal data class DashboardVersion(val data: DashboardData? = null, val displayName: String? = null, val friendCount: Int? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountDashboardViewModel @Inject constructor(
    repository: BattleLogRepository, @ApplicationContext context: Context,
) : ViewModel() {
    internal val names = MutableStateFlow(GameNameMap())
    internal val battles = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val versions = mutableMapOf<String, StateFlow<DashboardVersion>>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            CaptureTracker.init(context)
            val cache = GameNames(File(context.filesDir, "capture/names"))
            CaptureTracker.accountStates.collectLatest { states ->
                names.value = cache.dashboard(states.values)
            }
        }
    }

    internal fun account(userId: String): StateFlow<DashboardVersion> = versions.getOrPut(userId) {
        val cache = DashboardProjectionCache()
        combine(CaptureTracker.accountStates, CaptureTracker.accounts, names, CaptureTracker.rosters) { states, accounts, atlas, rosters ->
            listOf(states[userId], accounts.find { it.id == userId }?.displayName, atlas, rosters[userId]?.friends?.size)
        }.mapLatest { (state, displayName, atlas, friends) ->
            DashboardVersion(cache.get(state as AccountState?, atlas as GameNameMap), displayName as String?, friends as Int?)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardVersion())
    }
}
