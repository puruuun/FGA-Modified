package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

internal val outsideResourceTables = setOf("userGame", "tblUserGame", "userItem", "userSvt", "userSvtStorage", "userCommandCode")

/** All calls, including file access, share CaptureTracker's response/lifecycle lock. */
internal class CaptureOutsideSession(
    private val accounts: CaptureAccounts,
    private val write: (OutsideLogEntry) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val warn: () -> Unit = {},
) {
    @Serializable
    private data class Pending(
        val id: String,
        val period: OutsidePeriod,
        val resourceBaseline: Map<String, JsonArray> = emptyMap(),
        val baselineTime: Long = 0,
    )

    private val periods = mutableMapOf<String, Pending>()
    private val _openEntries = MutableStateFlow<List<OutsideLogEntry>>(emptyList())
    val openEntries = _openEntries.asStateFlow()

    fun publish() {
        _openEntries.value = periods.values.filterNot { it.period.isEmpty }.map { pending ->
            val period = pending.period
            OutsideLogEntry(pending.id, period.accountId, accounts.displayName(period.accountId) ?: "#${period.accountId}",
                period.startedAt * 1000, period.endedAt * 1000, period)
        }
    }

    /** A log id is distinct from an account id; do not let an old dialog delete a newer period. */
    fun deleteEntry(entryId: String) {
        periods.entries.find { it.value.id == entryId }?.key?.let(::delete)
    }
    private var directory: File? = null
    private var running = false
    private var scriptActive = false

    fun load(captureDirectory: File) {
        directory = File(captureDirectory, "accounts")
        accounts.states.keys.forEach { id ->
            val file = file(id)!!
            if (file.isFile) {
                runCatching {
                    val saved = Json.decodeFromString<Pending>(file.readText())
                    check(saved.period.accountId == id)
                    periods[id] = saved
                    if (saved.resourceBaseline.isNotEmpty()) accounts.restoreResourceBaseline(id, saved.resourceBaseline, saved.baselineTime)
                }.onFailure { warn() }
            }
        }
        // Loading is not a closing point: a process restart resumes the same period.
        publish()
    }

    fun setRunning(value: Boolean) {
        running = value
        if (!value) closeAll()
    }

    fun scriptStart() {
        scriptActive = true
        closeAll()
    }
    fun scriptEnd() { scriptActive = false }
    fun serviceStop() { scriptActive = false; closeAll() }

    fun collect(target: String, response: CaptureAccounts.Response) {
        if (!running) return
        // Switch by the routed response, even if its holdings did not change.
        periods.keys.filter { it != response.id }.toList().forEach(::close)
        if (scriptActive) return
        if (CaptureNames.classify(target) == CaptureNames.ApiPath.Login) {
            checkpoint(response)
            return
        }
        val delta = AccountDelta.between(response.previous, response.next)
        if (delta.isEmpty) {
            checkpoint(response)
            return
        }
        val time = now()
        val previous = periods[response.id] ?: Pending(UUID.randomUUID().toString(), OutsidePeriod(response.id, time))
        val next = previous.copy(
            period = previous.period.add(ChangeSource.of(target), delta, time),
            resourceBaseline = response.next!!.tables.filterKeys { it in outsideResourceTables },
            baselineTime = response.next.lastUpdateTime,
        )
        periods[response.id] = next
        publish()
        persist(response.id)
    }

    private fun checkpoint(response: CaptureAccounts.Response) {
        val pending = periods[response.id] ?: return
        val state = response.next ?: return
        periods[response.id] = pending.copy(
            resourceBaseline = state.tables.filterKeys { it in outsideResourceTables },
            baselineTime = state.lastUpdateTime,
        )
        persist(response.id)
    }

    fun delete(id: String) {
        file(id)?.let { check(!it.exists() || it.delete()) }
        periods.remove(id)
        publish()
    }
    fun clear() { periods.keys.toList().forEach(::delete) }

    private fun persist(id: String) {
        file(id)?.let {
            // A newly discovered account must be loadable before its first pending period is saved.
            if (!File(it.parentFile, "account-state.json").isFile) accounts.save(id)
            writeCaptureFile(it, Json.encodeToString(periods.getValue(id)))
        }
    }

    private fun closeAll() { periods.keys.toList().forEach(::close) }
    private fun close(id: String) {
        val pending = periods[id] ?: return
        val period = pending.period.copy(endedAt = maxOf(pending.period.endedAt, now()))
        // Once the pending checkpoint is removed, the normal account snapshot owns this baseline.
        accounts.save(id)
        if (!period.isEmpty) write(OutsideLogEntry(
            pending.id, id, accounts.displayName(id) ?: "#$id",
            period.startedAt * 1000, period.endedAt * 1000, period,
        ))
        // A crash after writing but before removal replays the same id, replacing rather than duplicating it.
        delete(id)
    }

    private fun file(id: String) = directory?.let {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        File(it, "$id/outside-period.json")
    }
}
