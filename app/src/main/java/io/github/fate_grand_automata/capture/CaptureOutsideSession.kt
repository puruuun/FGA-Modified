package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.OutsidePeriod
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * One baseline table as its JSON text (issue #258), written back into outside-period.json verbatim, so the file keeps
 * the same format as when the baseline was a JsonArray.
 */
@Serializable(with = RawTableSerializer::class)
@JvmInline
internal value class RawTable(val json: String)

@OptIn(ExperimentalSerializationApi::class)
internal object RawTableSerializer : KSerializer<RawTable> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder) = RawTable((decoder as JsonDecoder).decodeJsonElement().toString())
    override fun serialize(encoder: Encoder, value: RawTable) =
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.json))
}

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
        val resourceBaseline: Map<String, RawTable> = emptyMap(),
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
    // Battle scripts pause outside recording; every script marks changes made during its lifetime.
    private var scriptActive = false
    private var scriptMade = false
    private var scriptServer: String? = null

    fun load(captureDirectory: File) {
        directory = File(captureDirectory, "accounts")
        accounts.ids.toList().forEach { id ->
            val file = file(id)!!
            if (file.isFile) {
                runCatching {
                    val saved = file.inputStream().buffered().use { Json.decodeFromStream<Pending>(it) }
                    check(saved.period.accountId == id)
                    periods[id] = saved
                    if (saved.resourceBaseline.isNotEmpty()) accounts.restoreResourceBaseline(id, saved.resourceBaseline.mapValues { it.value.json }, saved.baselineTime)
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

    fun scriptStart(battle: Boolean = true, server: String = "KR") {
        scriptServer = server
        scriptMade = true
        scriptActive = battle
        if (battle) closeServer(server)
    }
    fun scriptEnd() {
        scriptActive = false
        scriptMade = false
        scriptServer = null
    }
    fun serviceStop() { scriptEnd(); closeAll() }

    fun collect(target: String, response: CaptureAccounts.Response) {
        if (!running) return
        val server = CaptureNames.server(target) ?: accounts.serverOf(response.id)
        // Switch by the routed response within the same server, even if its holdings did not change.
        periods.keys.filter { it != response.id && (server == null || accounts.serverOf(it) == server) }
            .toList().forEach(::close)
        val matchesScript = scriptServer == null || server == scriptServer
        if (scriptActive && matchesScript) return
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
            period = previous.period.add(ChangeSource.of(target).copy(script = scriptMade && matchesScript), delta, time),
            resourceBaseline = response.next!!.rawTables.filterKeys { it in outsideResourceTables }.mapValues { RawTable(it.value) },
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
            resourceBaseline = state.rawTables.filterKeys { it in outsideResourceTables }.mapValues { RawTable(it.value) },
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
            writeCaptureStream(it) { out -> Json.encodeToStream(periods.getValue(id), out) }
        }
    }

    private fun closeAll() { periods.keys.toList().forEach(::close) }
    private fun closeServer(server: String) {
        periods.keys.filter { (accounts.serverOf(it) ?: server) == server }.toList().forEach(::close)
    }
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
