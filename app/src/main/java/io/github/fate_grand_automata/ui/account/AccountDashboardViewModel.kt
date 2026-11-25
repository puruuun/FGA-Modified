package io.github.fate_grand_automata.ui.account

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncManager
import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
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
    private val progressLoader by lazy {
        DashboardProgressLoader(AtlasRouteRepository(File(appContext.cacheDir, "atlas-weekly")), EventInfoStore(File(appContext.cacheDir, "dashboard-events")),
            allowDownload = { !ChaldeaSyncManager.isScriptRunning })
    }

    private val questRetries = MutableStateFlow(0)
    private val questLoader by lazy {
        QuestRecordsLoader(
            readTable = { id -> withContext(Dispatchers.IO) { CaptureTracker.loadState(id)?.rawTables?.get("userQuest") } },
            questWars = { server, allowDownload, force -> cache.questWars(server, allowDownload, force) },
            scriptRunning = ChaldeaSyncManager.scriptActive,
            isScriptRunning = { ChaldeaSyncManager.isScriptRunning },
        )
    }

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
        var lastRevision: Long? = null
        var lastServer: String? = null
        var lastData: DashboardData? = null
        CaptureTracker.accounts
            .onStart { CaptureTracker.init(appContext) }
            .map { accounts -> accounts.find { it.id == userId } }
            .distinctUntilChangedBy { p -> listOf(p?.revision, p?.displayName, p?.friendCount, p?.friendUpdatedAt, p?.server) }
            .mapLatest { profile ->
                val server = profile?.server ?: "KR"
                val data = if (profile != null && lastData != null && profile.revision == lastRevision && server == lastServer) {
                    lastData
                } else {
                    val state = if (profile == null) null else CaptureTracker.loadState(userId)
                    val tables = state?.let(::DashboardStateTables)
                    val atlas = if (tables == null) names.value else cache.dashboardTables(listOf(tables), hasJpAccount = server == "JP")
                    names.value = atlas
                    projection.getTables(tables, atlas).also {
                        lastRevision = profile?.revision
                        lastServer = server
                        lastData = it
                    }
                }
                DashboardVersion(data, profile?.displayName, profile?.friendCount, server, loading = false)
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardVersion(loading = true))
    }

    /**
     * The "in progress" block lags behind [account] on purpose: the dashboard renders from the stored state first and
     * the block fills in when the master data has been read, or downloaded when it was not cached.
     */
    internal fun progress(userId: String): Flow<DashboardProgress> = account(userId)
        .map { it.data?.progressInput to it.server }
        .distinctUntilChanged()
        .flatMapLatest { (input, server) -> progressLoader.load(input, server) }
        .catch { emit(DashboardProgress()) }
        .flowOn(Dispatchers.IO)

    /**
     * The quest records block's data. A cold flow, not shared: it runs only while the block collects it, so leaving the
     * tab drops the records (#730).
     */
    internal fun questRecords(userId: String): Flow<QuestRecordsState> {
        val keys = CaptureTracker.accounts
            .onStart { CaptureTracker.init(appContext) }
            .mapNotNull { accounts -> accounts.find { it.id == userId } }
            .map { QuestRecordsKey(it.revision, it.server) }
        return questLoader.load(userId, keys, questRetries)
            .catch { emit(QuestRecordsState(records = emptyList(), names = QuestNameStatus.Missing)) }
            .flowOn(Dispatchers.IO)
    }

    internal fun retryQuestNames() { questRetries.value++ }
}
