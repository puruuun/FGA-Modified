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

internal data class DashboardVersion(
    val data: DashboardData? = null,
    val displayName: String? = null,
    val friendCount: Int? = null,
    val server: String = "KR",
    val loading: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountDashboardViewModel @Inject constructor(
    repository: BattleLogRepository, @ApplicationContext context: Context,
) : ViewModel() {
    internal val names = MutableStateFlow(GameNameMap())
    internal val battles = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val versions = mutableMapOf<String, StateFlow<DashboardVersion>>()

    private var customCache: GameNames? = null
    private val cache by lazy { customCache ?: GameNames(File(context.filesDir, "capture/names")) }
    private val appContext = context

    internal constructor(repository: BattleLogRepository, context: Context, namesCache: GameNames) : this(repository, context) {
        customCache = namesCache
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { CaptureTracker.init(appContext) }
    }

    /**
     * The account's state is read from disk when this account changes and is dropped after the projection and the
     * names are built (issue #258); only the current accounts stay resident in the tracker.
     */
    internal fun account(userId: String): StateFlow<DashboardVersion> = versions.getOrPut(userId) {
        val projection = DashboardProjectionCache()
        var lastStateTime: Long? = null
        var lastServer: String? = null
        var lastData: DashboardData? = null
        CaptureTracker.accounts
            .onStart { CaptureTracker.init(appContext) }
            .map { accounts -> accounts.find { it.id == userId } }
            .distinctUntilChangedBy { p -> listOf(p?.lastUpdateTime, p?.displayName, p?.friendCount, p?.friendUpdatedAt, p?.server) }
            .mapLatest { profile ->
                val server = profile?.server ?: "KR"
                val data = if (profile != null && lastData != null && profile.lastUpdateTime == lastStateTime && server == lastServer) {
                    lastData
                } else {
                    val state = if (profile == null) null else CaptureTracker.loadState(userId)
                    val tables = state?.let(::DashboardStateTables)
                    val atlas = if (tables == null) names.value else cache.dashboardTables(listOf(tables), hasJpAccount = server == "JP")
                    names.value = atlas
                    projection.getTables(tables, atlas).also {
                        lastStateTime = profile?.lastUpdateTime
                        lastServer = server
                        lastData = it
                    }
                }
                DashboardVersion(data, profile?.displayName, profile?.friendCount, server, loading = false)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardVersion(loading = true))
    }
}
