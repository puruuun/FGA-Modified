package io.github.fate_grand_automata.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

/** Public item metadata only. Called under GameNames' mutex, independently of account storage. */
internal class JpItemFallback(
    private val directory: File,
    private val download: (String) -> String,
    private val now: () -> Long,
) {
    @Serializable
    private data class Item(val name: String, val icon: String? = null)

    private val cacheFile get() = File(directory, "jp_item_fallback.json")
    private val retryFile get() = File(directory, "jp_item_fallback_retry.json")

    fun merge(kr: GameNameMap, ids: Collection<Long>): GameNameMap {
        val saved = runCatching { Json.decodeFromString<Map<Long, Item>>(cacheFile.readText()) }.getOrDefault(emptyMap())
        val savedRetries = runCatching { Json.decodeFromString<Map<Long, Long>>(retryFile.readText()) }.getOrDefault(emptyMap())
        val items = saved.filter { (id, item) -> id > 0 && id !in kr.items && item.name.isNotBlank() }
            .mapValues { (_, item) -> item.copy(icon = item.icon?.takeIf(IconFileStore::isPublicIcon)) }.toMutableMap()
        val retries = savedRetries.filterKeys { it > 0 && it !in kr.items && it !in items }.toMutableMap()
        ids.asSequence().filter { it > 0 && it !in kr.items && it !in items }.distinct()
            .filter { id -> retries[id]?.let { now() - it >= DAY_MS } ?: true }
            .take(MAX_LOOKUPS).forEach { id ->
                val item = runCatching {
                    val row = Json.parseToJsonElement(download("https://api.atlasacademy.io/nice/JP/item/$id")).jsonObject
                    val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    checkNotNull(name)
                    val icon = (row["icon"] as? JsonPrimitive)?.contentOrNull?.takeIf(IconFileStore::isPublicIcon)
                    Item(name, icon)
                }.getOrNull()
                if (item == null) retries[id] = now() else {
                    items[id] = item
                    retries.remove(id)
                }
            }
        // A failed disk write must not break the Log or overwrite any KR data.
        if (items != saved) runCatching { writeCaptureFile(cacheFile, Json.encodeToString(items)) }
        if (retries != savedRetries) runCatching { writeCaptureFile(retryFile, Json.encodeToString(retries)) }
        return kr.copy(items = kr.items + items.mapValues { it.value.name },
            itemIcons = kr.itemIcons + items.mapNotNull { (id, item) -> item.icon?.let { id to it } }.toMap())
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val MAX_LOOKUPS = 30
    }
}
