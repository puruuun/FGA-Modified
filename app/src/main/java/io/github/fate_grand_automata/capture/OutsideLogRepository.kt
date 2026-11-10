package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import timber.log.Timber

/** Entry times are milliseconds, matching BattleLogEntry. Period times are Unix seconds. */
@Serializable
data class OutsideLogEntry(
    val id: String,
    val accountId: String,
    val accountName: String,
    val startedAt: Long,
    val endedAt: Long,
    val period: OutsidePeriod,
)

/** Separate file and schema: the battle log and its historical JSON remain untouched. */
object OutsideLogRepository {
    private val state = MutableStateFlow<List<OutsideLogEntry>>(emptyList())
    val entries = state.asStateFlow()
    private var store: OutsideLogStore? = null

    @Synchronized
    fun init(filesDirectory: File, retention: () -> Int = { LogRetentionPrefsCore.DEFAULT }) {
        if (store == null) store = OutsideLogStore(filesDirectory, retention) { state.value = it }
        runCatching { store!!.load() }.onFailure { warn(it) }
    }

    @Synchronized
    internal fun add(entry: OutsideLogEntry) { runCatching { checkNotNull(store).add(entry) }.onSuccess { changed() }.onFailure { warn(it) } }
    @Synchronized
    fun delete(id: String) { runCatching { store?.delete(id) }.onSuccess { changed() }.onFailure { warn(it) } }
    @Synchronized
    fun clear() { runCatching { store?.clear() }.onSuccess { changed() }.onFailure { warn(it) } }
    @Synchronized
    fun trim() { runCatching { store?.trim() }.onSuccess { changed() }.onFailure { warn(it) } }

    /** Told after every successful add, delete, clear or trim; the AP + logs widget redraws from it. A failing listener is ignored. */
    @Volatile var changeListener: (() -> Unit)? = null
    private fun changed() { runCatching { changeListener?.invoke() } }

    /** The entries the app already holds in memory, or null when the store has not been initialized in this process. */
    @Synchronized
    internal fun loadedEntries(): List<OutsideLogEntry>? = if (store != null) state.value else null

    /** Read-only look at the stored file for a widget that woke the process: never moves, rewrites or deletes anything. */
    internal fun readStored(filesDirectory: File): List<OutsideLogEntry> {
        val file = File(filesDirectory, "outside-log.json")
        if (!file.isFile) return emptyList()
        return Json { ignoreUnknownKeys = true }.decodeFromString<List<OutsideLogEntry>>(file.readText()).sortedByDescending { it.endedAt }
    }

    private fun warn(error: Throwable) { Timber.w("Capture: outside log I/O failed: %s", error.javaClass.simpleName) }
}

internal class OutsideLogStore(
    private val directory: File,
    private val retention: () -> Int = { LogRetentionPrefsCore.DEFAULT },
    private val publish: (List<OutsideLogEntry>) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(directory, "outside-log.json")
    private var loaded = false
    var entries: List<OutsideLogEntry> = emptyList()
        private set

    fun load() {
        if (loaded) return
        entries = if (!file.exists()) emptyList() else try {
            json.decodeFromString<List<OutsideLogEntry>>(file.readText()).sortedByDescending { it.endedAt }.take(retention())
        } catch (e: kotlinx.serialization.SerializationException) {
            // Preserve unreadable history before starting a new file; I/O failures remain retryable.
            Files.move(file.toPath(), File(directory, "outside-log.corrupt-${UUID.randomUUID()}.json").toPath())
            emptyList()
        }
        loaded = true
        publish(entries)
    }

    fun add(entry: OutsideLogEntry) = update {
        (it.filterNot { old -> old.id == entry.id } + entry).sortedByDescending { it.endedAt }.take(retention())
    }
    fun delete(id: String) = update { it.filterNot { entry -> entry.id == id } }
    fun clear() = update { emptyList() }
    fun trim() = update { it.take(retention()) }

    private fun update(transform: (List<OutsideLogEntry>) -> List<OutsideLogEntry>) {
        load()
        val next = transform(entries)
        writeCaptureFile(file, json.encodeToString(next))
        entries = next
        publish(next)
    }
}

/** Write fully before publishing; never remove the original on a failed write. */
internal fun writeCaptureFile(file: File, text: String) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.outputStream().use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** Same atomic replace as [writeCaptureFile], streamed so a large file never exists as a String (issue #258). */
internal fun writeCaptureStream(file: File, write: (java.io.OutputStream) -> Unit) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.outputStream().buffered().use {
        write(it)
        it.flush()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
