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
    internal fun add(entry: OutsideLogEntry) { checkNotNull(store).add(entry) }
    @Synchronized
    fun delete(id: String) { runCatching { store?.delete(id) }.onFailure { warn(it) } }
    @Synchronized
    fun clear() { runCatching { store?.clear() }.onFailure { warn(it) } }
    @Synchronized
    fun trim() { runCatching { store?.trim() }.onFailure { warn(it) } }

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
