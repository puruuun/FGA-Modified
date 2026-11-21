package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.util.replaceFile
import io.github.fate_grand_automata.scripts.weekly.AtlasRouteRepository
import io.github.fate_grand_automata.scripts.weekly.AtlasWeekly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** The current weekly mission set: [start]..[end] are epoch seconds, [done] of [total] missions are achieved. */
data class WeeklyProgress(val start: Long, val end: Long, val done: Int, val total: Int)

/** [max] is the highest group-0 point reward, null when the event has no point rewards. */
data class EventProgress(val eventId: Long, val name: String, val points: Long, val max: Long?, val startedAt: Long, val endedAt: Long) {
    val complete get() = max != null && points >= max
    val fraction get() = if (max != null && max > 0) (points.toFloat() / max).coerceIn(0f, 1f) else 0f
}

/** What the Overview's "in progress" block shows; empty until the master data has been read or fetched. */
data class DashboardProgress(val weekly: WeeklyProgress? = null, val events: List<EventProgress> = emptyList()) {
    /** Only events that are running at [now] (epoch seconds). */
    fun running(now: Long) = events.filter { it.startedAt <= now && now < it.endedAt }
    fun isEmpty(now: Long) = weekly == null && running(now).isEmpty()
}

/** The parts of an Atlas `nice` event the dashboard uses; this is also what the cache file holds. */
@Serializable
internal data class EventInfo(val name: String, val startedAt: Long, val endedAt: Long, val maxPoint: Long? = null, val v: Int = FORMAT)

private const val FORMAT = 1

internal fun weeklyProgress(week: AtlasWeekly.Week?, achieved: Set<Long>?): WeeklyProgress? {
    if (week == null || achieved == null || week.missions.isEmpty()) return null
    // A mission without a row counts as not done (owner), so only the rows that exist can add to the count.
    return WeeklyProgress(week.start, week.end, week.missions.count { it.id.toLong() in achieved }, week.missions.size)
}

@Serializable private class RewardDto(val groupId: Long = 0, val point: Long = 0)
@Serializable private class EventDto(val name: String = "", val startedAt: Long = 0, val endedAt: Long = 0, val rewards: List<RewardDto> = emptyList())

private val lenient = Json { ignoreUnknownKeys = true }

private fun EventDto.toInfo(): EventInfo? {
    if (name.isBlank() || startedAt <= 0 || endedAt <= startedAt) return null
    return EventInfo(name, startedAt, endedAt, rewards.filter { it.groupId == 0L && it.point > 0 }.maxOfOrNull { it.point })
}

/** The full event response holds shops, missions and more, so it is decoded as a stream and only these fields are kept. */
@OptIn(ExperimentalSerializationApi::class)
internal fun parseEventInfo(stream: InputStream): EventInfo? = runCatching { lenient.decodeFromStream<EventDto>(stream).toInfo() }.getOrNull()

internal fun parseEventInfo(json: String): EventInfo? = runCatching { lenient.decodeFromString<EventDto>(json).toInfo() }.getOrNull()

/**
 * One small file per server and event id. A cached event is not fetched again. A corrupt, partial or outdated file is
 * ignored and fetched again; a failed fetch leaves the event out until the next time the dashboard opens.
 */
internal class EventInfoStore(
    private val dir: File,
    private val fetch: (server: String, eventId: Long) -> EventInfo? = ::downloadEvent,
) {
    private fun file(server: String, eventId: Long) = File(dir, "$server-$eventId.json")

    fun cached(server: String, eventId: Long): EventInfo? = runCatching {
        lenient.decodeFromString<EventInfo>(file(server, eventId).readText()).takeIf {
            it.v == FORMAT && it.name.isNotBlank() && it.endedAt > it.startedAt
        }
    }.getOrNull()

    /** Blocking: call on Dispatchers.IO. */
    fun get(server: String, eventId: Long): EventInfo? {
        cached(server, eventId)?.let { return it }
        val info = runCatching { fetch(server, eventId) }.getOrNull() ?: return null
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "${file(server, eventId).name}.tmp")
            temp.writeText(lenient.encodeToString(info))
            replaceFile(temp, file(server, eventId))
        }
        return info
    }
}

private fun downloadEvent(server: String, eventId: Long): EventInfo? {
    require(server == "KR" || server == "JP")
    val connection = URL("https://api.atlasacademy.io/nice/$server/event/$eventId").openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 20_000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "FGA-Modified dashboard/1")
    connection.setRequestProperty("Accept-Encoding", "gzip")
    return try {
        if (connection.responseCode != 200) return null
        val raw = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(connection.inputStream) else connection.inputStream
        raw.use { parseEventInfo(LimitedStream(it, 32L * 1024 * 1024)) }
    } finally { connection.disconnect() }
}

private class LimitedStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var total = 0L
    override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }
    private fun count(n: Int) { total += n; if (total > limit) throw java.io.IOException("response too large") }
}

/** At most this many events are looked up per dashboard opening. */
private const val MAX_EVENT_LOOKUPS = 8

/**
 * Joins the stored state with master data that is not in it. The first emission reads only what is cached, so the
 * Overview renders without waiting for the network; the second may download and is emitted only if it differs.
 */
internal class DashboardProgressLoader(
    private val weeklyRepository: AtlasRouteRepository,
    private val events: EventInfoStore,
    /** False while a script is running: like the other dashboard lists, nothing is downloaded then. */
    private val allowDownload: () -> Boolean = { true },
) {
    fun load(input: DashboardProgressInput?, server: String): Flow<DashboardProgress> = flow {
        if (input == null || server !in setOf("KR", "JP")) { emit(DashboardProgress()); return@flow }
        val local = resolve(input, server, network = false)
        emit(local)
        val full = if (allowDownload()) resolve(input, server, network = true) else local
        if (full != local) emit(full)
    }

    internal suspend fun resolve(input: DashboardProgressInput, server: String, network: Boolean): DashboardProgress = withContext(Dispatchers.IO) {
        val week = try {
            weeklyRepository.weekAt(server, input.snapshotTime, download = network && allowDownload())
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val weekly = weeklyProgress(week, input.achievedMissions)
        val lines = input.eventPoints.take(MAX_EVENT_LOOKUPS).mapNotNull { row ->
            // A script may start between two requests, so the check is made before each one.
            val info = if (network && allowDownload()) events.get(server, row.eventId) else events.cached(server, row.eventId)
            info?.let { EventProgress(row.eventId, it.name, row.value, it.maxPoint, it.startedAt, it.endedAt) }
        }.sortedWith(compareBy<EventProgress>({ it.endedAt }, { it.eventId }))
        DashboardProgress(weekly, lines)
    }
}
