package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaCollectionNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class GameNameMap(
    val items: Map<Long, String> = emptyMap(),
    val servants: Map<Long, String> = emptyMap(),
    val equips: Map<Long, String> = emptyMap(),
    val itemIcons: Map<Long, String> = emptyMap(),
    val servantData: Map<Long, AtlasBasic> = emptyMap(),
    val equipData: Map<Long, AtlasBasic> = emptyMap(),
    val mysticData: Map<Long, AtlasBasic> = emptyMap(),
    val itemData: Map<Long, AtlasBasic> = emptyMap(),
    val svtData: Map<Long, AtlasBasic> = emptyMap(),
    val commandCodeData: Map<Long, AtlasBasic> = emptyMap(),
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

/** Downloads public lists and missing JP item names; only public item ids enter requests. */
class GameNames(
    private val directory: File,
    private val download: (String) -> String = ::downloadPublicList,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class CachedMetadata(val lastModified: Long, val length: Long, val data: Map<Long, AtlasBasic>)
    private data class JpNeed(
        val items: Boolean = false,
        val servants: Boolean = false,
        val equips: Boolean = false,
        val mystics: Boolean = false,
    ) {
        val any get() = items || servants || equips || mystics
        companion object {
            val All = JpNeed(items = true, servants = true, equips = true, mystics = true)
        }
    }

    private val mutex = Mutex()
    private var loaded: GameNameMap? = null
    private var hasMysticNames = false
    private val metadataCache = ConcurrentHashMap<String, CachedMetadata>()

    /** Cache-only CE and command-code collection numbers from KR and JP details files; never downloads or throws. */
    fun collectionNumbers(): ChaldeaCollectionNumbers = runCatching {
        fun readCachedMap(list: String): Map<Long, Long> {
            val out = LinkedHashMap<Long, Long>()
            for (server in listOf("KR", "JP")) {
                for ((id, basic) in cachedMetadata(list, server)) {
                    val colNo = basic.collectionNo
                    if (colNo != null && colNo > 0L) out[id] = colNo
                }
            }
            return out
        }
        ChaldeaCollectionNumbers(
            equips = readCachedMap("basic_equip"),
            commandCodes = readCachedMap("basic_command_code"),
        )
    }.getOrDefault(ChaldeaCollectionNumbers.EMPTY)

    /** Ensures KR and JP CE and command-code lists are cached (respecting daily attempt limits). */
    suspend fun ensureChaldeaCollectionLists(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = collectionNumbers()
            for (list in listOf("basic_equip", "basic_command_code")) {
                for (server in listOf("KR", "JP")) {
                    metadata(list, server = server)
                }
            }
            collectionNumbers() != before
        }
    }

    suspend fun resolve(runs: List<RunGains>, outsideItemIds: List<Long> = emptyList(), includeCards: Boolean = false,
        hasJpAccount: Boolean = false, cardIds: Collection<Long> = emptyList()): GameNameMap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val kr = resolveKr(runs, outsideItemIds)
            val names = if (includeCards) kr.copy(
                svtData = metadata("basic_svt"), commandCodeData = metadata("basic_command_code"),
            ) else kr
            val drops = runs.flatMap { it.items }
            val itemIds = drops.filter { it.type == 2 }.map { it.objectId } + outsideItemIds
            val servantIds = drops.filter { it.type == 1 }.map { it.objectId } + runs.flatMap { it.bond.keys }
            val equipIds = drops.filter { it.type == 3 }.map { it.objectId }
            val missingCards = includeCards && cardIds.any { it !in names.svtData }
            val need = if (hasJpAccount) JpNeed.All else JpNeed(
                items = itemIds.any { it !in names.items },
                servants = servantIds.any { it !in names.servants } || missingCards,
                equips = equipIds.any { it !in names.equips } || missingCards,
            )
            jpFallback.merge(withJpLists(names, need, includeCards), itemIds)
        }
    }

    private val jpFallback = JpItemFallback(directory, download, now)

    // Keep this cache KR-only, so JP results cannot suppress the existing KR refresh attempts.
    private fun resolveKr(runs: List<RunGains>, outsideItemIds: List<Long> = emptyList(),
        servantIds: Collection<Long> = emptyList(), equipIds: Collection<Long> = emptyList()): GameNameMap {
        val cached = loaded ?: GameNameMap(read("nice_item"), read("basic_servant"), read("basic_equip"), read("item_icons"))
        val drops = runs.flatMap { it.items }
        var icons = cached.itemIcons
        val items = refresh("nice_item", cached.items, drops.filter { it.type == 2 }.map { it.objectId } + RunGains.FRUIT_IDS + outsideItemIds) {
            icons = it
        }
        // Older installs can have today's names/attempt without an icon map. Its retry budget is independent.
        if (!File(directory, "item_icons.json").isFile) {
            runCatching {
                if (!canAttempt("item_icons")) return@runCatching
                val body = download("https://api.atlasacademy.io/export/KR/nice_item.json")
                check(parseNames(body).isNotEmpty())
                icons = saveIcons(body)
                saveMetadata("nice_item", parseBasic(body))
            }
        } else if (icons.isEmpty()) {
            icons = read("item_icons")
        }
        val servants = refresh("basic_servant", cached.servants,
            drops.filter { it.type == 1 }.map { it.objectId } + runs.flatMap { it.bond.keys } + servantIds)
        val equips = refresh("basic_equip", cached.equips, drops.filter { it.type == 3 }.map { it.objectId } + equipIds)
        return GameNameMap(items, servants, equips, icons).also { loaded = it }
    }

    /** Servant and CE names for the friend manager; ids missing from the cache follow the usual daily refresh. */
    suspend fun friends(servantIds: Collection<Long>, equipIds: Collection<Long>, hasJpAccount: Boolean = false): GameNameMap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val kr = resolveKr(emptyList(), servantIds = servantIds, equipIds = equipIds)
                .copy(servantData = metadata("basic_servant"))
            val need = if (hasJpAccount) JpNeed.All else JpNeed(
                servants = servantIds.any { it !in kr.servants || it !in kr.servantData },
                equips = equipIds.any { it !in kr.equips },
            )
            withJpLists(kr, need)
        }
    }

    /** Metadata has an independent migration budget when a legacy name-only cache exists. */
    suspend fun dashboard(states: Collection<AccountState> = emptyList(), hasJpAccount: Boolean = false): GameNameMap =
        dashboardTables(states.map(::DashboardStateTables), hasJpAccount)

    internal suspend fun dashboardTables(states: Collection<DashboardStateTables>, hasJpAccount: Boolean = false): GameNameMap {
        return withContext(Dispatchers.IO) { mutex.withLock {
            val names = resolveKr(emptyList())
            val mysticFile = File(directory, "basic_mystic_code.json")
            if (!hasMysticNames || !mysticFile.isFile) {
                hasMysticNames = refresh("basic_mystic_code", read("basic_mystic_code"), emptyList()).isNotEmpty()
            }
            fun ids(table: String, field: String) = states.flatMap { it.rows(table).orEmpty() }
                .mapNotNull { it.number(field) }.toSet()
            val required = mutableMapOf("nice_item" to ids("userItem", "itemId"),
                "basic_servant" to ids("userSvtCollection", "svtId"),
                "basic_equip" to emptySet(), "basic_mystic_code" to ids("userEquip", "equipId"))
            val items = metadata("nice_item", required["nice_item"].orEmpty())
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
            val allSvtIds = required["basic_servant"].orEmpty() + required["basic_equip"].orEmpty()
            val need = if (hasJpAccount) JpNeed.All else JpNeed(
                items = required["nice_item"].orEmpty().any { it !in kr.items && it !in kr.itemData },
                servants = allSvtIds.any { it !in kr.servantData && it !in kr.equipData },
                equips = allSvtIds.any { it !in kr.servantData && it !in kr.equipData },
                mystics = required["basic_mystic_code"].orEmpty().any { it !in kr.mysticData },
            )
            jpFallback.merge(withJpLists(kr, need), required["nice_item"].orEmpty())
        } }
    }

    /** JP exports have their own files and budgets. Never feed the merged result back into loaded. */
    private fun withJpLists(kr: GameNameMap, need: JpNeed, includeCards: Boolean = false): GameNameMap {
        if (!need.any) return kr
        val items = if (need.items) metadata("nice_item", server = "JP").filterKeys { it !in kr.items && it !in kr.itemData } else emptyMap()
        val servants = if (need.servants) metadata("basic_servant", server = "JP").filterKeys { it !in kr.servants && it !in kr.servantData } else emptyMap()
        val equips = if (need.equips) metadata("basic_equip", server = "JP").filterKeys { it !in kr.equips && it !in kr.equipData } else emptyMap()
        val mystics = if (need.mystics) metadata("basic_mystic_code", server = "JP") else emptyMap()
        fun names(rows: Map<Long, AtlasBasic>) = rows.mapNotNull { (id, row) ->
            row.name?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
        return kr.copy(
            items = names(items) + kr.items,
            servants = names(servants) + kr.servants,
            equips = names(equips) + kr.equips,
            itemIcons = items.mapNotNull { (id, row) -> row.icon?.let { id to it } }.toMap() + kr.itemIcons,
            itemData = items + kr.itemData,
            servantData = servants + kr.servantData,
            equipData = equips + kr.equipData,
            mysticData = mystics + kr.mysticData,
            svtData = if (includeCards) servants + equips + kr.svtData else kr.svtData,
        )
    }

    private fun cachedMetadata(list: String, server: String = "KR"): Map<Long, AtlasBasic> {
        val cache = if (server == "JP") "jp_$list" else list
        val file = File(directory, "${cache}_details.json")
        val memory = metadataCache[cache]?.takeIf { file.isFile && file.lastModified() == it.lastModified && file.length() == it.length }?.data
        return memory ?: runCatching {
            val data = Json.decodeFromString<List<AtlasBasic>>(file.readText()).associateBy { it.id }
            if (file.isFile) metadataCache[cache] = CachedMetadata(file.lastModified(), file.length(), data)
            data
        }.getOrDefault(emptyMap())
    }

    private fun metadata(list: String, required: Set<Long> = emptySet(), server: String = "KR"): Map<Long, AtlasBasic> {
        val cache = if (server == "JP") "jp_$list" else list
        val cached = cachedMetadata(list, server)
        // Card exports refresh by age, including when an unknown id is absent from Atlas.
        val current = if (server == "KR" && list == "nice_item") required.all { it in cached } else !isStale(cache)
        if (cached.isNotEmpty() && current) return cached
        return runCatching {
            if (!canAttempt("${cache}_details")) return cached
            val body = download("https://api.atlasacademy.io/export/$server/$list.json")
            val parsed = parseBasic(body)
            check(parsed.isNotEmpty())
            saveMetadata(cache, parsed)
        }.getOrDefault(cached)
    }

    private fun saveMetadata(list: String, rows: List<AtlasBasic>): Map<Long, AtlasBasic> {
        val file = File(directory, "${list}_details.json")
        writeCaptureFile(file, Json.encodeToString(rows))
        File(directory, "${list}_details.attempt").writeText(now().toString())
        File(directory, "${list}_details.fetched").writeText(now().toString())
        val map = rows.associateBy { it.id }
        metadataCache[list] = CachedMetadata(file.lastModified(), file.length(), map)
        return map
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

    private fun saveIcons(body: String): Map<Long, String> {
        val icons = parseIcons(body)
        writeCaptureFile(File(directory, "item_icons.json"),
            JsonObject(icons.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())
        return icons
    }

    private fun refresh(list: String, cache: Map<Long, String>, ids: List<Long>, onIcons: (Map<Long, String>) -> Unit = {}): Map<Long, String> {
        if (cache.isNotEmpty() && ids.all { it in cache }) return cache
        return runCatching {
            if (!canAttempt(list)) return cache
            File(directory, "${list}_details.attempt").writeText(now().toString())
            // A names fetch also satisfies the icon attempt, even if that fetch fails.
            if (list == "nice_item") {
                File(directory, "item_icons.attempt").writeText(now().toString())
            }
            val body = download("https://api.atlasacademy.io/export/KR/$list.json")
            val basic = parseBasic(body)
            val names = basic.mapNotNull { row -> row.name?.takeIf { it.isNotBlank() }?.let { row.id to it } }.toMap()
            check(names.isNotEmpty())
            if (list == "nice_item") onIcons(saveIcons(body))
            saveMetadata(list, basic)
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
