package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class GameNameMap(
    val items: Map<Long, String> = emptyMap(),
    val servants: Map<Long, String> = emptyMap(),
    val equips: Map<Long, String> = emptyMap(),
    val itemIcons: Map<Long, String> = emptyMap(),
) {
    fun item(id: Long) = items[id] ?: "Item #$id"
    fun servant(id: Long) = servants[id] ?: "Servant #$id"
    fun equip(id: Long) = equips[id] ?: "CE #$id"
    fun drop(type: Int, id: Long): String = when (type) {
        1 -> servant(id)
        2 -> item(id)
        3 -> equip(id)
        else -> "Item #$id"
    }
}

/** Downloads fixed public KR lists only; run data is never included in a network request. */
class GameNames(
    private val directory: File,
    private val download: (String) -> String = ::downloadPublicList,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded: GameNameMap? = null

    suspend fun resolve(runs: List<RunGains>, outsideItemIds: List<Long> = emptyList()): GameNameMap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cached = loaded ?: GameNameMap(read("nice_item"), read("basic_servant"), read("basic_equip"), read("item_icons"))
            val drops = runs.flatMap { it.items }
            val items = refresh("nice_item", cached.items, drops.filter { it.type == 2 }.map { it.objectId } + RunGains.FRUIT_IDS + outsideItemIds,
                needsIcons = !File(directory, "item_icons.json").isFile)
            val servants = refresh("basic_servant", cached.servants,
                drops.filter { it.type == 1 }.map { it.objectId } + runs.flatMap { it.bond.keys })
            val equips = refresh("basic_equip", cached.equips, drops.filter { it.type == 3 }.map { it.objectId })
            GameNameMap(items, servants, equips, read("item_icons")).also { loaded = it }
        }
    }

    private fun read(list: String): Map<Long, String> = runCatching {
        Json.parseToJsonElement(File(directory, "$list.json").readText()).jsonObject.mapNotNull { (id, name) ->
            id.toLongOrNull()?.let { key -> (name as? JsonPrimitive)?.contentOrNull?.let { key to it } }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun refresh(list: String, cache: Map<Long, String>, ids: List<Long>, needsIcons: Boolean = false): Map<Long, String> {
        if (cache.isNotEmpty() && ids.all { it in cache } && !needsIcons) return cache
        val attempt = File(directory, "$list.attempt")
        val last = runCatching { attempt.readText().toLong() }.getOrNull()
        if (last != null && now() - last < DAY_MS) return cache
        return runCatching {
            directory.mkdirs()
            // Persist attempts too: failures and process restarts must not cause repeated downloads.
            attempt.writeText(now().toString())
            val body = download("https://api.atlasacademy.io/export/KR/$list.json")
            val names = parseNames(body)
            check(names.isNotEmpty())
            if (list == "nice_item") writeCaptureFile(File(directory, "item_icons.json"),
                JsonObject(parseIcons(body).mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())
            val target = File(directory, "$list.json")
            val tmp = File(directory, "$list.tmp")
            tmp.writeText(JsonObject(names.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())
            check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)))
            names
        }.getOrDefault(cache)
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        internal fun parseNames(body: String): Map<Long, String> =
            (Json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = (row["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val name = (row["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to name
            }.toMap()

        /** Restrict image requests to Atlas's public HTTPS asset host. */
        internal fun parseIcons(body: String): Map<Long, String> =
            (Json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = (row["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                val icon = (row["icon"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (runCatching { java.net.URI(icon).let { it.scheme == "https" && it.host == "static.atlasacademy.io" && it.userInfo == null } }.getOrDefault(false)) id to icon else null
            }.toMap()
    }
}

private fun downloadPublicList(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200)
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
