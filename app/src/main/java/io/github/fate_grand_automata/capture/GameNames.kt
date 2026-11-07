package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class GameNameMap(
    val items: Map<Long, String> = emptyMap(),
    val servants: Map<Long, String> = emptyMap(),
    val equips: Map<Long, String> = emptyMap(),
    val itemIcons: Map<Long, String> = emptyMap(),
    val servantData: Map<Long, AtlasBasic> = emptyMap(),
    val equipData: Map<Long, AtlasBasic> = emptyMap(),
    val mysticData: Map<Long, AtlasBasic> = emptyMap(),
    val itemData: Map<Long, AtlasBasic> = emptyMap(),
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

/** Downloads public KR lists and missing JP item names; only public item ids enter requests. */
class GameNames(
    private val directory: File,
    private val download: (String) -> String = ::downloadPublicList,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded: GameNameMap? = null

    suspend fun resolve(runs: List<RunGains>, outsideItemIds: List<Long> = emptyList()): GameNameMap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val names = resolveKr(runs, outsideItemIds)
            jpFallback.merge(names, runs.flatMap { it.items }.filter { it.type == 2 }.map { it.objectId } + outsideItemIds)
        }
    }

    private val jpFallback = JpItemFallback(directory, download, now)

    // Keep this cache KR-only, so JP results cannot suppress the existing KR refresh attempts.
    private fun resolveKr(runs: List<RunGains>, outsideItemIds: List<Long> = emptyList()): GameNameMap {
        val cached = loaded ?: GameNameMap(read("nice_item"), read("basic_servant"), read("basic_equip"), read("item_icons"))
        val drops = runs.flatMap { it.items }
        val items = refresh("nice_item", cached.items, drops.filter { it.type == 2 }.map { it.objectId } + RunGains.FRUIT_IDS + outsideItemIds)
        // Older installs can have today's names/attempt without an icon map. Its retry budget is independent.
        if (!File(directory, "item_icons.json").isFile) {
            runCatching {
                if (!canAttempt("item_icons")) return@runCatching
                val body = download("https://api.atlasacademy.io/export/KR/nice_item.json")
                check(parseNames(body).isNotEmpty())
                saveIcons(body)
                saveMetadata("nice_item", parseBasic(body))
            }
        }
        val servants = refresh("basic_servant", cached.servants,
            drops.filter { it.type == 1 }.map { it.objectId } + runs.flatMap { it.bond.keys })
        val equips = refresh("basic_equip", cached.equips, drops.filter { it.type == 3 }.map { it.objectId })
        return GameNameMap(items, servants, equips, read("item_icons")).also { loaded = it }
    }

    /** Metadata has an independent migration budget when a legacy name-only cache exists. */
    suspend fun dashboard(states: Collection<AccountState> = emptyList()): GameNameMap {
        return withContext(Dispatchers.IO) { mutex.withLock {
            val names = resolveKr(emptyList())
            refresh("basic_mystic_code", read("basic_mystic_code"), emptyList())
            fun ids(table: String, field: String) = states.flatMap { it.tables[table].orEmpty() }
                .mapNotNull { (it as? JsonObject)?.number(field) }.toSet()
            val required = mutableMapOf("nice_item" to ids("userItem", "itemId"),
                "basic_servant" to ids("userSvtCollection", "svtId"),
                "basic_equip" to emptySet(), "basic_mystic_code" to ids("userEquip", "equipId"))
            fun metadata(list: String): Map<Long, AtlasBasic> {
                val file = File(directory, "${list}_details.json")
                val cached = runCatching { Json.decodeFromString<List<AtlasBasic>>(file.readText()).associateBy { it.id } }.getOrDefault(emptyMap())
                // Servant, CE and mystic code lists never contain every id in the account (EXP cards, unreleased rows), so they refresh by age only.
                val current = if (list == "nice_item") required[list].orEmpty().all { it in cached } else !isStale(list)
                if (cached.isNotEmpty() && current) return cached
                return runCatching {
                    if (!canAttempt("${list}_details")) return cached
                    val body = download("https://api.atlasacademy.io/export/KR/$list.json")
                    val parsed = parseBasic(body)
                    check(parsed.isNotEmpty())
                    saveMetadata(list, parsed)
                    parsed.associateBy { it.id }
                }.getOrDefault(cached)
            }
            val items = metadata("nice_item")
            val servants = metadata("basic_servant")
            required["basic_equip"] = ids("userSvt", "svtId") - servants.keys
            val equips = metadata("basic_equip")
            val mystics = metadata("basic_mystic_code")
            val kr = names.copy(items = names.items + items.mapNotNull { (id, row) ->
                row.name?.takeIf { it.isNotBlank() }?.let { id to it }
            }.toMap(),
                servants = names.servants + servants.mapValues { it.value.name ?: "#${it.key}" },
                equips = names.equips + equips.mapValues { it.value.name ?: "#${it.key}" },
                itemIcons = names.itemIcons + items.mapNotNull { (id, row) -> row.icon?.let { id to it } }.toMap(),
                servantData = servants, equipData = equips, mysticData = mystics, itemData = items)
            jpFallback.merge(kr, required["nice_item"].orEmpty())
        } }
    }

    private fun saveMetadata(list: String, rows: List<AtlasBasic>) {
        writeCaptureFile(File(directory, "${list}_details.json"), Json.encodeToString(rows))
        File(directory, "${list}_details.attempt").writeText(now().toString())
        File(directory, "${list}_details.fetched").writeText(now().toString())
    }

    private fun isStale(list: String): Boolean {
        val fetched = runCatching { File(directory, "${list}_details.fetched").readText().toLong() }.getOrNull() ?: return true
        return now() - fetched >= METADATA_MAX_AGE_MS
    }

    private fun read(list: String): Map<Long, String> = runCatching {
        Json.parseToJsonElement(File(directory, "$list.json").readText()).jsonObject.mapNotNull { (id, name) ->
            id.toLongOrNull()?.let { key -> (name as? JsonPrimitive)?.contentOrNull?.let { key to it } }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun canAttempt(list: String): Boolean {
        val attempt = File(directory, "$list.attempt")
        val last = runCatching { attempt.readText().toLong() }.getOrNull()
        if (last != null && now() - last < DAY_MS) return false
        directory.mkdirs()
        attempt.writeText(now().toString())
        return true
    }

    private fun saveIcons(body: String) = writeCaptureFile(File(directory, "item_icons.json"),
        JsonObject(parseIcons(body).mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())

    private fun refresh(list: String, cache: Map<Long, String>, ids: List<Long>): Map<Long, String> {
        if (cache.isNotEmpty() && ids.all { it in cache }) return cache
        return runCatching {
            if (!canAttempt(list)) return cache
            File(directory, "${list}_details.attempt").writeText(now().toString())
            // A names fetch also satisfies the icon attempt, even if that fetch fails.
            if (list == "nice_item") {
                File(directory, "item_icons.attempt").writeText(now().toString())
            }
            val body = download("https://api.atlasacademy.io/export/KR/$list.json")
            val names = parseNames(body)
            check(names.isNotEmpty())
            if (list == "nice_item") saveIcons(body)
            saveMetadata(list, parseBasic(body))
            val target = File(directory, "$list.json")
            val tmp = File(directory, "$list.tmp")
            tmp.writeText(JsonObject(names.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())
            check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)))
            names
        }.getOrDefault(cache)
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val METADATA_MAX_AGE_MS = 7 * DAY_MS

        internal fun parseBasic(body: String): List<AtlasBasic> =
            (Json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row.number("id") ?: return@mapNotNull null
                val image = row.text("face") ?: row.text("icon") ?: (row["item"] as? JsonObject)?.let { it.text("male") ?: it.text("female") }
                AtlasBasic(id, row.text("name"), row.text("type"), row.text("className"), row.number("rarity"), row.number("collectionNo"),
                    image?.takeIf(IconFileStore::isPublicIcon))
            }

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
                if (IconFileStore.isPublicIcon(icon)) id to icon else null
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
