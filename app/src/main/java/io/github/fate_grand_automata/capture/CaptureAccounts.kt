package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountRouting
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CaptureAccount(
    val id: String,
    val displayName: String,
    val summary: AccountSummary,
    val lastUpdateTime: Long,
)

/** App-private account storage. All calls are serialized by CaptureTracker, including disk writes. */
internal class CaptureAccounts(private val warn: (String) -> Unit = {}) {
    val states = mutableMapOf<String, AccountState>()
    private val metadata = mutableMapOf<String, Metadata>()
    private val dirty = mutableSetOf<String>()
    var currentId: String? = null
        private set
    val state get() = states[currentId]
    private var directory: File? = null

    @Serializable
    private data class Metadata(val customName: String? = null)

    data class Response(val id: String, val previous: AccountState?, val next: AccountState?)

    fun load(captureDirectory: File) {
        if (directory != null) return
        directory = File(captureDirectory, "accounts")
        directory?.listFiles()?.filter { it.isDirectory && safeId(it.name) }?.forEach { folder ->
            runCatching {
                val saved = AccountState.fromJson(File(folder, STATE_FILE).readText())
                check(snapshotId(saved) == folder.name)
                states[folder.name] = saved
                metadata[folder.name] = runCatching {
                    Json.decodeFromString<Metadata>(File(folder, "account.json").readText())
                }.getOrDefault(Metadata())
            }.onFailure { warn("saved account state is unreadable") }
        }
        currentId = runCatching { File(directory, "current").readText().takeIf { it in states } }.getOrNull()
            ?: newestId()
        migrate(File(captureDirectory, STATE_FILE))
    }

    fun apply(target: String, body: JsonObject, now: Long): Response? {
        val id = AccountRouting.userId(target, body, currentId) ?: return null
        if (id == AccountRouting.CONFLICT || !safeId(id)) {
            warn("conflicting or invalid account routing; response dropped")
            return null
        }
        val previous = states[id]
        val next = runCatching { AccountState.reduce(previous, target, body, now) }
            .onFailure { warn("malformed account response; response dropped") }.getOrElse { return null }
        if (next != null && next !== previous) {
            states[id] = next
            dirty += id
        }
        if (next != null && (next !== previous || CaptureNames.classify(target) == CaptureNames.ApiPath.Login)) {
            currentId = id
        }
        return Response(id, previous, next)
    }

    fun accounts(now: Long): List<CaptureAccount> = states.map { (id, state) ->
        val summary = AccountSummary.from(state, now)
        CaptureAccount(id, displayName(id)!!, summary, state.lastUpdateTime)
    }.sortedWith(compareByDescending<CaptureAccount> { it.id == currentId }
        .thenByDescending { it.lastUpdateTime }.thenBy { it.id })

    fun displayName(id: String): String? = states[id]?.let { state ->
        val game = state.tables["userGame"]?.firstOrNull() as? JsonObject
        metadata[id]?.customName ?: (game?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "#$id"
    }

    fun rename(id: String, name: String) {
        if (id !in states) return
        metadata[id] = Metadata(name.trim().takeIf { it.isNotBlank() })
        dirty += id
    }

    fun delete(id: String) {
        if (id !in states) return
        // Only ids validated on routing/load can enter states; never resolve an arbitrary UI path.
        directory?.let { check(File(it, id).deleteRecursively()) }
        states.remove(id)
        metadata.remove(id)
        dirty.remove(id)
        if (currentId == id) currentId = newestId()
        save()
    }

    fun clear() {
        directory?.let { check(it.deleteRecursively()) }
        states.clear()
        metadata.clear()
        dirty.clear()
        currentId = null
    }

    fun save(userId: String? = null) {
        val root = directory ?: return
        for (id in dirty.filter { userId == null || userId == it }) {
            val state = states[id] ?: continue
            val folder = File(root, id)
            atomicWrite(File(folder, STATE_FILE), state.toJson())
            atomicWrite(File(folder, "account.json"), metadataJson.encodeToString(metadata[id] ?: Metadata()))
            dirty.remove(id)
        }
        // A current marker must never point to a snapshot that is still waiting to be written.
        currentId?.let { if (it !in dirty) atomicWrite(File(root, "current"), it) } ?: File(root, "current").delete()
    }

    private fun newestId() = states.entries.sortedWith(
        compareByDescending<Map.Entry<String, AccountState>> { it.value.lastUpdateTime }.thenBy { it.key },
    ).firstOrNull()?.key

    private fun migrate(legacy: File) {
        if (!legacy.isFile) return
        val old = runCatching { AccountState.fromJson(legacy.readText()) }.getOrNull()
        val id = old?.let(::snapshotId)
        if (id == null || !safeId(id)) {
            warn("legacy account has no readable userId; old state deleted")
            check(legacy.delete())
            return
        }
        if (id !in states) {
            states[id] = old
            dirty += id
        }
        currentId = id
        // Publish both the snapshot and marker before removing the legacy file; retry is idempotent.
        save()
        check(legacy.delete())
    }

    private fun snapshotId(state: AccountState): String? = state.tables["userGame"].orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("userId") as? JsonPrimitive)?.contentOrNull }
        .distinct().singleOrNull()

    private fun safeId(id: String) = id.matches(Regex("[a-zA-Z0-9_-]+"))

    private fun atomicWrite(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val STATE_FILE = "account-state.json"
        val metadataJson = Json { encodeDefaults = true }
    }
}
