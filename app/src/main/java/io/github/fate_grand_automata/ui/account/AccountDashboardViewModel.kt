package io.github.fate_grand_automata.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.capture.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

internal data class DashboardVersion(val data: DashboardData? = null, val displayName: String? = null,
    val friendCount: Int? = null, val server: String = "KR")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountDashboardViewModel @Inject constructor(
    repository: BattleLogRepository, @ApplicationContext context: Context,
) : ViewModel() {
    internal val names = MutableStateFlow(GameNameMap())
    internal val battles = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val versions = mutableMapOf<String, StateFlow<DashboardVersion>>()

    private val cache by lazy { GameNames(File(context.filesDir, "capture/names")) }
    private val appContext = context

    init {
        viewModelScope.launch(Dispatchers.IO) { CaptureTracker.init(appContext) }
    }

    /**
     * The account's state is read from disk when this account changes and is dropped after the projection and the
     * names are built (issue #258); only the current account stays resident in the tracker.
     */
    internal fun account(userId: String): StateFlow<DashboardVersion> = versions.getOrPut(userId) {
        val projection = DashboardProjectionCache()
        CaptureTracker.accounts.map { accounts -> accounts.find { it.id == userId } to accounts.any { it.server == "JP" } }
            .distinctUntilChangedBy { (p, jp) -> listOf(p?.lastUpdateTime, p?.displayName, p?.friendCount, p?.friendUpdatedAt, p?.server, jp) }
            .mapLatest { (profile, hasJpAccount) ->
                val state = if (profile == null) null else CaptureTracker.loadState(userId)
                val atlas = if (state == null) names.value else cache.dashboard(listOf(state), hasJpAccount = hasJpAccount)
                names.value = atlas
                DashboardVersion(projection.get(state, atlas), profile?.displayName, profile?.friendCount, profile?.server ?: "KR")
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardVersion())
    }
}
