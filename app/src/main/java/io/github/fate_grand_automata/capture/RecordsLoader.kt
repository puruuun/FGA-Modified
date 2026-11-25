package io.github.fate_grand_automata.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream

/** The rows of one stored table; [complete] is false when its text stopped being readable part way (the rows before that point are kept). */
internal data class TableRead<R>(val rows: List<R>, val complete: Boolean)

private val tableJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Decodes a stored table one row at a time into a small typed row. A table's text is up to about 1 MB (6,600 quests) and
 * a JsonElement tree of it would cost about ten times that (issue #258), so no tree is built; unknown fields are skipped.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <R> readTableRows(stream: InputStream, row: DeserializationStrategy<R>): Sequence<R> =
    tableJson.decodeToSequence(stream, row, DecodeSequenceMode.ARRAY_WRAPPED)

internal fun <R> readTable(stream: InputStream, row: DeserializationStrategy<R>, keep: (R) -> Boolean): TableRead<R> {
    val rows = ArrayList<R>()
    val iterator = readTableRows(stream, row).iterator()
    while (true) {
        val next = try {
            if (!iterator.hasNext()) return TableRead(rows, complete = true)
            iterator.next()
        } catch (_: SerializationException) {
            return TableRead(rows, complete = false)
        } catch (_: IllegalArgumentException) {
            return TableRead(rows, complete = false)
        }
        if (keep(next)) rows.add(next)
    }
}

/**
 * [Ready]: names from the account's own server lists. [Loading]: the lists are being fetched. [Waiting]: there is no
 * cached list and a script is running, so nothing is fetched until it ends. [Missing]: the fetch failed; numbers are shown.
 */
enum class RecordNameStatus { Ready, Loading, Waiting, Missing }

/**
 * [records] is null while the stored table is read. [paused] is true when the account changed while a script was
 * running: the records are not rebuilt then and show the last result. [damaged] is true when the stored table could
 * only be read in part.
 */
data class RecordsState<T>(
    val records: T? = null,
    val names: RecordNameStatus = RecordNameStatus.Loading,
    val paused: Boolean = false,
    val damaged: Boolean = false,
)

/** What a rebuild depends on: the account's revision (it changes after every battle) and its server. */
internal data class RecordsKey(val revision: Long?, val server: String)

/**
 * The records of one account built from one stored table and the public name lists, only while a block collects this
 * flow, and dropped with it. They are not part of the dashboard projection, which is rebuilt after every battle. While a
 * script runs nothing is rebuilt and nothing is downloaded; the first build when the block opens still reads the stored
 * table, without a download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RecordsLoader<R, N, T>(
    private val readTable: suspend (userId: String) -> String?,
    private val parse: (String) -> TableRead<R>,
    /** The name lists of [server], from the cache, or refreshed when [allowDownload]; [force] skips the wait between failed attempts. */
    private val names: suspend (server: String, allowDownload: Boolean, force: Boolean) -> N,
    private val hasNames: (N) -> Boolean,
    private val project: (TableRead<R>, N) -> T,
    private val scriptRunning: Flow<Boolean>,
    private val isScriptRunning: () -> Boolean,
) {
    fun load(userId: String, keys: Flow<RecordsKey>, retries: Flow<Int>): Flow<RecordsState<T>> {
        var last: RecordsState<T>? = null
        var builtKey: RecordsKey? = null
        var read: TableRead<R>? = null
        var lastRetry: Int? = null
        return combine(keys.distinctUntilChanged(), scriptRunning.distinctUntilChanged(), retries) { key, running, retry -> Triple(key, running, retry) }
            .transformLatest { (key, running, retry) ->
                val previous = last
                // The flag is read as well as the flow: an account update can arrive before the flow's own value does.
                if (previous != null && (running || isScriptRunning())) {
                    val paused = previous.copy(paused = key != builtKey)
                    if (paused != previous) { last = paused; emit(paused) }
                    return@transformLatest
                }
                val rebuild = previous == null || key != builtKey || read == null
                val retried = lastRetry != null && retry != lastRetry
                lastRetry = retry
                if (!rebuild && !retried && previous.names == RecordNameStatus.Ready) {
                    if (previous.paused) previous.copy(paused = false).also { last = it; emit(it) }
                    return@transformLatest
                }
                if (rebuild) {
                    if (previous == null) emit(RecordsState())
                    val raw = readTable(userId)
                    read = withContext(Dispatchers.Default) { raw?.let(parse) ?: TableRead(emptyList(), complete = true) }
                    builtKey = key
                }
                val rows = checkNotNull(read)
                val cached = names(key.server, false, false)
                var state = RecordsState(
                    records = withContext(Dispatchers.Default) { project(rows, cached) },
                    names = if (hasNames(cached)) RecordNameStatus.Ready else RecordNameStatus.Loading,
                    damaged = !rows.complete,
                )
                if (isScriptRunning()) {
                    if (!hasNames(cached)) state = state.copy(names = RecordNameStatus.Waiting)
                    last = state
                    emit(state)
                    return@transformLatest
                }
                last = state
                emit(state)
                // A failed refresh returns the cached lists, so the fetched result is never worse than the cached one.
                val fetched = names(key.server, true, retried)
                val full = state.copy(
                    records = if (fetched == cached) state.records else withContext(Dispatchers.Default) { project(rows, fetched) },
                    names = if (hasNames(fetched)) RecordNameStatus.Ready else RecordNameStatus.Missing,
                )
                last = full
                if (full != state) emit(full)
            }
    }
}
