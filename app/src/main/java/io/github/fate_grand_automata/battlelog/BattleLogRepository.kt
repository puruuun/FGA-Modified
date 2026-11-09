package io.github.fate_grand_automata.battlelog

import android.content.Context
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import io.github.fate_grand_automata.scripts.capture.BattleGains
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The battle log keeps only per-entry summaries in memory (issue #280); each entry's per-battle rows live in their
 * own file and are read on demand. Every operation catches [Throwable] so a log problem, even an
 * [OutOfMemoryError], can never reach the automation: the last good in-memory state is kept instead.
 */
@Singleton
class BattleLogRepository internal constructor(
    private val storage: BattleLogStorage,
    private val retention: () -> Int = { LogRetentionPrefsCore.DEFAULT },
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(FileBattleLogStorage(context.filesDir),
        { LogRetentionPrefsCore.normalize(LogRetentionPrefsCore.fromContext(context).limit.get()) })

    internal constructor(directory: File, retention: () -> Int = { LogRetentionPrefsCore.DEFAULT }) :
        this(FileBattleLogStorage(directory), retention)

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<BattleLogEntry>>(emptyList())
    private var loaded = false

    /** Summaries only: `gains.battles` is empty, `gains.capturedBattles` and `gains.lastQuestId` are filled in. */
    val entries: Flow<List<BattleLogEntry>> = flow {
        withContext(Dispatchers.IO) { mutex.withLock { load() } }
        emitAll(state)
    }

    suspend fun add(entry: BattleLogEntry) = update(
        prepare = {
            // Detail first, then the index: a crash in between leaves only an orphan file that the next write sweeps.
            val battles = entry.gains?.battles.orEmpty()
            if (battles.isNotEmpty()) storage.writeDetail(entry.id, battles)
            else if (entry.gains?.storedBattleCount.let { it == null || it == 0 }) storage.deleteDetail(entry.id)
        }
    ) {
        (it.filterNot { previous -> previous.id == entry.id } + entry.summarized())
            .sortedByDescending { record -> record.endedAt }.take(retention())
    }

    suspend fun delete(id: String) = update { it.filterNot { entry -> entry.id == id } }

    suspend fun clear() = update { emptyList() }
    suspend fun trim() = update { it.take(retention()) }

    /** The entry's per-battle rows, or null when they could not be read. Never cached. */
    suspend fun battles(id: String): List<BattleGains>? = withContext(Dispatchers.IO) {
        guard("read battle details", null) { storage.readDetail(id) }
    }

    private suspend fun update(prepare: () -> Unit = {}, transform: (List<BattleLogEntry>) -> List<BattleLogEntry>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                guard("write battle log", Unit) {
                    // Do not overwrite unread data after an I/O failure or a failed corrupt-file backup.
                    if (!load()) return@guard
                    prepare()
                    val next = transform(state.value)
                    storage.writeIndex(next)
                    state.value = next
                    guard("sweep battle details", Unit) { storage.sweepDetails(next.mapTo(HashSet()) { it.id }) }
                }
            }
        }

    /** Called only on IO, under the mutex. A failed read can be retried by the next operation. */
    private fun load(): Boolean {
        if (loaded) return true
        guard("read battle log", Unit) {
            val index = try {
                storage.readIndex()
            } catch (e: SerializationException) {
                Timber.w(e, "Corrupt battle log index; preserving original")
                storage.backupCorruptIndex()
                null
            }
            if (index != null) {
                // An earlier migration wrote the index but could not delete the old file; its content is already in.
                if (storage.hasLegacy()) guard("delete old battle log", Unit) { storage.deleteLegacy() }
                state.value = index.sortedByDescending { it.endedAt }.take(retention())
                loaded = true
            } else if (storage.hasLegacy()) migrate()
            else {
                state.value = emptyList()
                loaded = true
            }
        }
        return loaded
    }

    /** One-time move from battle-log.json. The old file is deleted only after the new index is written. */
    private fun migrate() {
        val summaries = LinkedHashMap<String, BattleLogEntry>()
        try {
            storage.streamLegacy { summaries[it.id] = it }
        } catch (e: SerializationException) {
            Timber.w(e, "Corrupt battle log; preserving original")
            storage.backupCorruptLegacy()
            storage.sweepDetails(emptySet())
            state.value = emptyList()
            loaded = true
            return
        }
        val next = summaries.values.sortedByDescending { it.endedAt }.take(retention())
        storage.writeIndex(next)
        state.value = next
        loaded = true
        guard("finish battle log migration", Unit) {
            storage.sweepDetails(next.mapTo(HashSet()) { it.id })
            storage.deleteLegacy()
        }
    }

    private inline fun <T> guard(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        Timber.w(e, "Could not %s", what)
        fallback
    }
}
