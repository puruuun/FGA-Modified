package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountRouting
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CaptureAccount(
    val id: String,
    val displayName: String,
    val summary: AccountSummary,
    val lastUpdateTime: Long,
    val server: String = "KR",
    /** Null while the account has no friend roster. The roster itself stays on disk (issue #258). */
    val friendCount: Int? = null,
    val friendUpdatedAt: Long = 0,
)

/** Runs file work. The app version moves it to one low-priority thread; tests run it inline. */
internal interface DiskRunner {
    fun <T> run(block: () -> T): T

    object Direct : DiskRunner {
        override fun <T> run(block: () -> T): T = block()
    }
}

/**
 * App-private account storage. All calls are serialized by CaptureTracker, including disk writes.
 *
 * Issue #258: only the current account's [AccountState] stays resident, plus states that still wait to be saved.
 * Every account keeps a small index entry (metadata, [AccountSummary], friend count), which is also persisted as
 * `summary.json` so that starting up and the AP widget never parse an account state. Other states and all friend
 * rosters are read from disk when needed and dropped afterwards. Without a directory (tests) everything stays resident.
 */
internal class CaptureAccounts(
    private val warn: (String) -> Unit = {},
    private val disk: DiskRunner = DiskRunner.Direct,
) {
    @Serializable
    private data class Metadata(val customName: String? = null, val server: String = "KR")

    /** The persisted `summary.json`. [summary] was computed when it was written; use [AccountSummary.atTime]. */
    @Serializable
    private data class Stored(
        val userId: String,
        val gameName: String? = null,
        val summary: AccountSummary,
        val friendCount: Int? = null,
        val friendUpdatedAt: Long = 0,
    )

    private class Entry(var metadata: Metadata, var stored: Stored)

    private val index = mutableMapOf<String, Entry>()
    private val resident = mutableMapOf<String, AccountState>()
    private val dirty = mutableSetOf<String>()
    private val sideDirty = mutableSetOf<String>()
    var currentId: String? = null
        private set
    private var directory: File? = null

    /** The current account's state, read from disk the first time it is needed. */
    val state: AccountState? get() = currentId?.let(::stateOf)

    val ids: Set<String> get() = index.keys
    operator fun contains(id: String) = id in index
    val hasUnsaved get() = dirty.isNotEmpty() || sideDirty.isNotEmpty()

    data class Response(val id: String, val previous: AccountState?, val next: AccountState?)

    /**
     * Reads the account index. With [migrateLegacy] false (widget refresh) nothing but small files is read, and
     * accounts that have no `summary.json` yet are skipped until the app starts normally.
     */
    fun load(captureDirectory: File, migrateLegacy: Boolean = true) {
        val first = directory == null
        directory = File(captureDirectory, "accounts")
        directory?.listFiles()?.filter { it.isDirectory && safeId(it.name) && it.name !in index }?.forEach { folder ->
            runCatching {
                val metadata = runCatching { Json.decodeFromString<Metadata>(File(folder, "account.json").readText()) }
                    .getOrDefault(Metadata())
                val stored = runCatching { Json.decodeFromString<Stored>(File(folder, SUMMARY_FILE).readText()) }
                    .getOrNull()?.takeIf { it.userId == folder.name }
                if (stored != null) index[folder.name] = Entry(metadata, stored)
                else if (migrateLegacy) migrateFolder(folder, metadata)
            }.onFailure { warn("saved account state is unreadable") }
        }
        if (first || currentId == null) {
            currentId = runCatching { File(directory, "current").readText().takeIf { it in index } }.getOrNull() ?: newestId()
        }
        if (migrateLegacy) {
            migrate(File(captureDirectory, STATE_FILE))
            if (hasUnsaved) runCatching { save() }.onFailure { warn("rewriting a saved account state failed") }
        }
    }

    /** One-time upgrade of a folder written before summary.json existed. */
    private fun migrateFolder(folder: File, metadata: Metadata) {
        val (saved, hadOthers) = readStateChecked(File(folder, STATE_FILE)) ?: error("unreadable")
        check(snapshotId(saved) == folder.name)
        val roster = runCatching { disk.run { File(folder, ROSTER_FILE).inputStream().buffered().use(FriendRoster::fromStream) } }.getOrNull()
        index[folder.name] = Entry(metadata, storedFor(folder.name, saved, 0, roster))
        sideDirty += folder.name
        // A state saved before other players' tables were dropped is rewritten without them.
        if (hadOthers) { resident[folder.name] = saved; dirty += folder.name }
    }

    private fun storedFor(id: String, state: AccountState, now: Long, roster: FriendRoster?): Stored {
        val game = state.tables["userGame"]?.firstOrNull() as? JsonObject
        return Stored(id, (game?.get("name") as? JsonPrimitive)?.contentOrNull,
            AccountSummary.from(state, now), roster?.friends?.size, roster?.updatedAt ?: 0)
    }

    /** The saved state of [id], without keeping it. */
    fun readState(id: String): AccountState? = directory?.let { readStateChecked(File(File(it, id), STATE_FILE))?.first }

    private fun readStateChecked(file: File): Pair<AccountState, Boolean>? = runCatching {
        disk.run { file.inputStream().buffered().use(AccountState::fromStreamChecked) }
    }.onFailure { warn("saved account state is unreadable") }.getOrNull()

    /** Resident state, or a transient copy from disk (kept only for the current account). */
    fun stateOf(id: String): AccountState? {
        resident[id]?.let { return it }
        if (id !in index) return null
        val loaded = readState(id) ?: return null
        if (id == currentId) resident[id] = loaded
        return loaded
    }

    fun residentState(id: String): AccountState? = resident[id]

    /** Rosters of a store without a directory (unit tests); a real store keeps none in memory. */
    private val memoryRosters = mutableMapOf<String, FriendRoster>()

    fun roster(id: String): FriendRoster? {
        if (directory == null) return memoryRosters[id]
        val file = directory?.let { File(File(it, id), ROSTER_FILE) } ?: return null
        return runCatching { disk.run { file.inputStream().buffered().use(FriendRoster::fromStream) } }.getOrNull()
    }

    private fun writeRoster(id: String, roster: FriendRoster) {
        val root = directory ?: return run { memoryRosters[id] = roster }
        disk.run { atomicWriteStream(File(File(root, id), ROSTER_FILE)) { roster.writeTo(it) } }
    }

    /** Local schema diagnostics only. Never copies a row value, except numeric Grand flag counts. */
    fun recordFriendFields(target: String, body: JsonObject, now: Long) {
        val root = directory?.parentFile ?: return
        runCatching {
            val updated = (body["cache"] as? JsonObject)?.get("updated") as? JsonObject ?: return
            val source: String
            val rows: List<JsonObject>
            when (CaptureNames.path(target)) {
                "friend/top" -> {
                    source = "friendTop"
                    rows = (updated["otherUserGame"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                }
                "follower/list", "battle/result" -> {
                    val followers = updated["userFollower"] as? JsonArray ?: return
                    source = "followers"
                    rows = followers.filterIsInstance<JsonObject>().flatMap {
                        (it["followerInfo"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    }
                }
                else -> return
            }
            fun slots(row: JsonObject): List<JsonObject> = listOf("userSvtLeaderHash", "eventUserSvtLeaderHash").flatMap { key ->
                when (val value = row[key]) {
                    is JsonArray -> value.filterIsInstance<JsonObject>()
                    is JsonObject -> value.values.filterIsInstance<JsonObject>()
                    else -> emptyList()
                }
            }
            val counts = linkedMapOf("0" to 0, "1" to 0)
            rows.forEach { row -> slots(row).forEach { slot ->
                (slot["grandSvt"] as? JsonPrimitive)?.longOrNull?.toString()?.let { value ->
                    counts[value] = (counts[value] ?: 0) + 1
                }
            } }
            val diagnostic = buildJsonObject {
                put("server", CaptureNames.server(target) ?: "unknown")
                put("source", source)
                put("time", now)
                putJsonArray("friendFields") { rows.firstOrNull()?.keys?.sorted()?.forEach { add(it) } }
                putJsonArray("slotFields") { rows.firstOrNull()?.let(::slots)?.firstOrNull()?.keys?.sorted()?.forEach { add(it) } }
                putJsonObject("grandValues") { counts.toSortedMap().forEach { (value, count) -> put(value, count) } }
            }
            disk.run {
                writeCaptureStream(File(root, "diagnostics/friend-fields.json")) { it.write(diagnostic.toString().toByteArray(Charsets.UTF_8)) }
            }
        }.onFailure { warn("writing friend field diagnostics failed") }
    }

    /** Feeds a friend list or support list response for [id]; returns true when the roster changed. */
    fun applyFriends(id: String, target: String, body: JsonObject, now: Long): Boolean {
        val entry = index[id] ?: return false
        val ownId = AccountRouting.bodyUserId(target, body)
        if (ownId == AccountRouting.CONFLICT) return false
        val path = CaptureNames.path(target)
        if (path == "friend/top") {
            if (AccountRouting.friendOwner(body) != id || (ownId != null && ownId != id)) return false
        } else if (ownId?.let { it != id } ?: (id != currentId)) return false
        // A support list can only change a roster that exists, and only when it names a friend.
        if (path != "friend/top" && (entry.stored.friendCount == null || !FriendRoster.hasFollowerInfo(body))) return false
        val previous = if (entry.stored.friendCount == null) null else roster(id)
        val next = FriendRoster.update(previous, path, body, now, allowNew = ownId != null)
        if (next == null || next == previous) return false
        if (id in dirty) save(id) // the folder must hold its state before it holds a roster
        writeRoster(id, next)
        entry.stored = entry.stored.copy(friendCount = next.friends.size, friendUpdatedAt = next.updatedAt)
        sideDirty += id
        return true
    }

    /** Roster routing never changes the current account, including a non-current friend/top owner. */
    fun friendTarget(target: String, body: JsonObject): String? = when (CaptureNames.path(target)) {
        "friend/top" -> AccountRouting.friendOwner(body)?.takeIf { owner ->
            val own = AccountRouting.bodyUserId(target, body)
            owner in index && (own == null || own == owner)
        }
        "follower/list", "battle/result" -> AccountRouting.userId(target, body, currentId)
            ?.takeIf { it != AccountRouting.CONFLICT && it in index }
        else -> null
    }

    /**
     * [reduce] defaults to the normal reducer. A streamed login/top supplies its own and passes
     * [loadPrevious] false: a login replaces the account, so nothing is read from disk for it.
     */
    fun apply(
        target: String, body: JsonObject, now: Long, loadPrevious: Boolean = true,
        reduce: (AccountState?) -> AccountState? = { AccountState.reduce(it, target, body, now) },
    ): Response? {
        // A friend list can belong to a saved, non-current account. It must not move the marker
        // or reduce an unverified response into the current account's state.
        if (CaptureNames.path(target) == "friend/top") return null
        val id = AccountRouting.userId(target, body, currentId) ?: return null
        if (id == AccountRouting.CONFLICT || !safeId(id)) {
            warn("conflicting or invalid account routing; response dropped")
            return null
        }
        val previous = if (loadPrevious) stateOf(id) else resident[id]
        val next = runCatching { reduce(previous) }
            .onFailure { warn("malformed account response; response dropped") }.getOrElse { return null }
        if (next != null && next !== previous) {
            resident[id] = next
            dirty += id
            touch(id, next, now)
        }
        if (next != null) CaptureNames.server(target)?.let { server ->
            val entry = index[id]!!
            if (entry.metadata.server != server) {
                entry.metadata = entry.metadata.copy(server = server)
                sideDirty += id
            }
        }
        if (next != null && (next !== previous || CaptureNames.classify(target) == CaptureNames.ApiPath.Login)) {
            currentId = id
        }
        trim()
        return Response(id, previous, next)
    }

    /** Refreshes the index entry after the state of [id] changed. */
    private fun touch(id: String, state: AccountState, now: Long) {
        val old = index[id]
        val keep = old?.stored
        val stored = storedFor(id, state, now, null).copy(friendCount = keep?.friendCount, friendUpdatedAt = keep?.friendUpdatedAt ?: 0)
        if (old == null) index[id] = Entry(Metadata(), stored) else old.stored = stored
        sideDirty += id
    }

    fun accounts(now: Long): List<CaptureAccount> = index.map { (id, entry) ->
        CaptureAccount(id, displayName(id)!!, entry.stored.summary.atTime(now), entry.stored.summary.lastUpdateTime,
            entry.metadata.server, entry.stored.friendCount, entry.stored.friendUpdatedAt)
    }.sortedWith(compareByDescending<CaptureAccount> { it.id == currentId }
        .thenByDescending { it.lastUpdateTime }.thenBy { it.id })

    fun summary(id: String, now: Long): AccountSummary? = index[id]?.stored?.summary?.atTime(now)

    fun displayName(id: String): String? = index[id]?.let { entry ->
        entry.metadata.customName ?: entry.stored.gameName?.takeIf { it.isNotBlank() } ?: "#$id"
    }

    fun rename(id: String, name: String) {
        val entry = index[id] ?: return
        entry.metadata = entry.metadata.copy(customName = name.trim().takeIf { it.isNotBlank() })
        sideDirty += id
    }

    /** Recover the resource baseline atomically checkpointed with an open outside period. */
    fun restoreResourceBaseline(id: String, tables: Map<String, String>, time: Long) {
        val entry = index[id] ?: return
        if (entry.stored.summary.lastUpdateTime > time) return
        val state = stateOf(id) ?: return
        // Old checkpoints contain no card tables. Keep their saved account baseline intact.
        val resources = setOf("userGame", "tblUserGame", "userItem") + (tables.keys intersect outsideResourceTables)
        val next = state.withRawTables(state.rawTables.filterKeys { it !in resources } + tables.filterKeys { it in resources }, time)
        resident[id] = next
        dirty += id
        touch(id, next, time)
        trim()
    }

    fun delete(id: String) {
        if (id !in index) return
        // Only ids validated on routing/load can enter the index; never resolve an arbitrary UI path.
        directory?.let { check(File(it, id).deleteRecursively()) }
        index.remove(id)
        resident.remove(id)
        dirty.remove(id)
        sideDirty.remove(id)
        memoryRosters.remove(id)
        if (currentId == id) currentId = newestId()
        save()
    }

    fun clear() {
        directory?.let { check(it.deleteRecursively()) }
        index.clear()
        resident.clear()
        dirty.clear()
        sideDirty.clear()
        memoryRosters.clear()
        currentId = null
    }

    fun save(userId: String? = null) {
        val root = directory ?: return
        for (id in (dirty + sideDirty).filter { userId == null || userId == it }) {
            val entry = index[id] ?: continue
            val folder = File(root, id)
            if (id in dirty) {
                val state = resident[id] ?: continue
                disk.run { atomicWriteStream(File(folder, STATE_FILE)) { state.writeTo(it) } }
            }
            disk.run {
                atomicWriteStream(File(folder, "account.json")) { it.write(metadataJson.encodeToString(entry.metadata).toByteArray()) }
                atomicWriteStream(File(folder, SUMMARY_FILE)) { it.write(metadataJson.encodeToString(entry.stored).toByteArray()) }
            }
            dirty.remove(id)
            sideDirty.remove(id)
        }
        // A current marker must never point to a snapshot that is still waiting to be written.
        disk.run {
            currentId?.let { if (it !in dirty) atomicWriteStream(File(root, "current")) { out -> out.write(it.toByteArray()) } }
                ?: File(root, "current").delete()
        }
        trim()
    }

    /** Only the current account and states that still wait for a save stay resident. */
    private fun trim() {
        if (directory == null) return
        resident.keys.retainAll { it == currentId || it in dirty }
    }

    private fun newestId() = index.entries.sortedWith(
        compareByDescending<Map.Entry<String, Entry>> { it.value.stored.summary.lastUpdateTime }.thenBy { it.key },
    ).firstOrNull()?.key

    private fun migrate(legacy: File) {
        if (!legacy.isFile) return
        val old = runCatching { disk.run { legacy.inputStream().buffered().use(AccountState::fromStream) } }.getOrNull()
        val id = old?.let(::snapshotId)
        if (id == null || !safeId(id)) {
            warn("legacy account has no readable userId; old state deleted")
            check(legacy.delete())
            return
        }
        if (id !in index) {
            resident[id] = old
            dirty += id
            touch(id, old, old.lastUpdateTime)
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

    private fun atomicWriteStream(file: File, write: (OutputStream) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().buffered().use(write)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val STATE_FILE = "account-state.json"
        const val ROSTER_FILE = "friends.json"
        const val SUMMARY_FILE = "summary.json"
        val metadataJson = Json { encodeDefaults = true }
    }
}
