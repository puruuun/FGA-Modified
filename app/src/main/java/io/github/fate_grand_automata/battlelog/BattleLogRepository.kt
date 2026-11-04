package io.github.fate_grand_automata.battlelog

import android.content.Context
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BattleLogRepository internal constructor(private val directory: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.filesDir)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<BattleLogEntry>>(emptyList())
    private var loaded = false
    private val file get() = File(directory, "battle-log.json")

    val entries: Flow<List<BattleLogEntry>> = flow {
        withContext(Dispatchers.IO) { mutex.withLock { load() } }
        emitAll(state)
    }

    suspend fun add(entry: BattleLogEntry) = update {
        (it.filterNot { previous -> previous.id == entry.id } + entry)
            .sortedByDescending { record -> record.endedAt }.take(MAX_ENTRIES)
    }

    suspend fun delete(id: String) = update { it.filterNot { entry -> entry.id == id } }

    suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<BattleLogEntry>) -> List<BattleLogEntry>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Do not overwrite unread data after an I/O failure or a failed corrupt-file backup.
                if (!load()) return@withLock
                try {
                    val next = transform(state.value)
                    val temp = File(directory, "battle-log.json.tmp")
                    temp.outputStream().use { stream ->
                        stream.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    // renameTo replaces atomically on Android; some filesystems refuse to overwrite.
                    if (!temp.renameTo(file) && !(file.delete() && temp.renameTo(file))) throw IOException("Could not replace battle log")
                    state.value = next
                } catch (e: Exception) {
                    Timber.w(e, "Could not write battle log")
                }
            }
        }

    /** Called only on IO, under the mutex. A failed read can be retried by the next operation. */
    private fun load(): Boolean {
        if (loaded) return true
        try {
            state.value = if (file.exists()) {
                try {
                    json.decodeFromString<List<BattleLogEntry>>(file.readText())
                        .sortedByDescending { it.endedAt }.take(MAX_ENTRIES)
                } catch (e: SerializationException) {
                    Timber.w(e, "Corrupt battle log; preserving original")
                    val backup = File(directory, "battle-log.corrupt.json").let {
                        if (it.exists()) File(directory, "battle-log.corrupt-${UUID.randomUUID()}.json") else it
                    }
                    if (!file.renameTo(backup)) throw IOException("Could not preserve corrupt battle log", e)
                    emptyList()
                }
            } else emptyList()
            loaded = true
        } catch (e: Exception) {
            Timber.w(e, "Could not read battle log")
        }
        return loaded
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
