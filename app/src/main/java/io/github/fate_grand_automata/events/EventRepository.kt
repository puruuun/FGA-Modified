package io.github.fate_grand_automata.events

import io.github.fate_grand_automata.capture.IconFileStore
import io.github.fate_grand_automata.capture.writeCaptureFile
import io.github.fate_grand_automata.scripts.events.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class EventData(
    val events: List<GameEvent>? = null,
    val bonuses: Map<Long, List<BonusEquip>> = emptyMap(),
    val banners: Map<Long, String?> = emptyMap(),
) {
    fun running(now: Long) = GameEvents.running(events.orEmpty(), now)
}

/** Uses the same public Atlas cache and atomic writer as GameNames, independently of capture settings. */
class EventRepository(
    private val directory: File,
    private val download: (String) -> InputStream = ::downloadEventData,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded: EventData? = null

    suspend fun load(): EventData = withContext(Dispatchers.IO) { mutex.withLock { cached() } }

    suspend fun refresh(publish: (EventData) -> Unit = {}): EventData = withContext(Dispatchers.IO) {
        mutex.withLock {
            var data = cached()
            attempt {
                if (canAttempt("basic_event")) {
                    val events = download("https://api.atlasacademy.io/export/KR/basic_event.json").use(GameEvents::parse)
                    // A nonempty export with no valid rows is a failed response, preserving the previous schedule.
                    check(events.isNotEmpty())
                    save("basic_event", events)
                    data = data.copy(events = events)
                    loaded = data
                    publish(data)
                }
            }
            val limited = data.running(now() / 1000).limited
            attempt {
                if (limited.any { it.id !in data.bonuses } && canAttempt("event_bonuses")) {
                    val bonuses = download("https://api.atlasacademy.io/export/KR/nice_equip.json").use(GameEvents::bonusMap).toMutableMap()
                    // An empty list records a successful lookup for events without bonus CEs.
                    limited.forEach { bonuses.putIfAbsent(it.id, emptyList()) }
                    save("event_bonuses", bonuses)
                    data = data.copy(bonuses = bonuses)
                    loaded = data
                    publish(data)
                }
            }
            limited.filter { it.id !in data.banners }.forEach { event ->
                attempt {
                    if (canAttempt("event_banner_${event.id}")) {
                        val banner = download("https://api.atlasacademy.io/nice/KR/event/${event.id}").use { input ->
                            val row = Json.parseToJsonElement(input.bufferedReader().readText()) as JsonObject
                            (row["banner"] as? JsonPrimitive)?.contentOrNull?.takeIf(IconFileStore::isPublicIcon)
                        }
                        val banners = data.banners + (event.id to banner)
                        save("event_banners", banners)
                        data = data.copy(banners = banners)
                        loaded = data
                        publish(data)
                    }
                }
            }
            data.also { loaded = it }
        }
    }

    private fun cached(): EventData = loaded ?: EventData(
        read<List<GameEvent>>("basic_event"), read<Map<Long, List<BonusEquip>>>("event_bonuses").orEmpty(),
        read<Map<Long, String?>>("event_banners").orEmpty().mapValues { (_, url) -> url?.takeIf(IconFileStore::isPublicIcon) },
    ).also { loaded = it }

    private inline fun <reified T> read(name: String): T? =
        runCatching { Json.decodeFromString<T>(File(directory, "$name.json").readText()) }.getOrNull()

    private inline fun <reified T> save(name: String, value: T) =
        writeCaptureFile(File(directory, "$name.json"), Json.encodeToString(value))

    private fun canAttempt(name: String): Boolean {
        val file = File(directory, "$name.attempt")
        val last = runCatching { file.readText().toLong() }.getOrNull()
        if (last != null && now() - last < DAY_MS) return false
        writeCaptureFile(file, now().toString())
        return true
    }

    private inline fun attempt(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retain the last public data. */ }
    }

    companion object { internal const val DAY_MS = 86_400_000L }
}

/** Closing the stream also closes the connection; the CE export never becomes one in-memory string. */
private fun downloadEventData(address: String): InputStream {
    val connection = URL(address).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200)
        return object : FilterInputStream(connection.inputStream) {
            override fun close() { try { super.close() } finally { connection.disconnect() } }
        }
    } catch (e: Exception) { connection.disconnect(); throw e }
}
