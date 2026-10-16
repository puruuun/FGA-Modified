package io.github.fate_grand_automata.util.portraits

import io.github.fate_grand_automata.SupportImageKind
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** Display language never chooses which ascensions or costumes exist. */
enum class SupportCatalogKind(val endpoint: String, val storage: SupportImageKind) {
    Servant("servant", SupportImageKind.Servant), CE("equip", SupportImageKind.CE)
}

data class SupportCatalogRecord(
    val id: Int, val number: Int, val name: String, val originalName: String,
    val className: String, val rarity: Int, val face: String?
)

data class SupportCatalogEntry(
    val kind: SupportCatalogKind, val id: Int, val number: Int, val name: String,
    val className: String, val rarity: Int, val face: String?, val aliases: Set<String>,
    val sourceRegion: String? = null
) {
    val folderName get() = ServantPortraitFolder.name(number, name, sourceRegion)
}

object SupportImageCatalog {
    val lookupRegions = listOf("JP", "KR", "NA")
    // Display-name localization is independent of the three image/search sources.
    val regions = setOf("JP", "NA", "KR", "CN", "TW")

    fun languageRegion(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return when (locale.language) {
            "ko" -> "KR"
            "ja" -> "JP"
            "en" -> "NA"
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "TW" else "CN"
            else -> "JP"
        }
    }

    fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun parse(text: String, kind: SupportCatalogKind): List<SupportCatalogRecord> {
        val array = Json.parseToJsonElement(text) as? JsonArray ?: invalid()
        return array.mapNotNull { value ->
            val item = value as? JsonObject ?: invalid()
            val type = item["type"]?.jsonPrimitive?.content
            if (type !in (if (kind == SupportCatalogKind.Servant) setOf("normal", "heroine") else setOf("servantEquip"))) return@mapNotNull null
            val number = item["collectionNo"]?.jsonPrimitive?.intOrNull ?: invalid()
            if (number == 0) return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.intOrNull ?: invalid()
            val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Regional campaign numbers can exceed four digits; names join by id, not this number.
            if (id <= 0 || number < 1) invalid()
            val original = item["originalName"]?.jsonPrimitive?.contentOrNull ?: name
            val face = item["face"]?.jsonPrimitive?.contentOrNull?.takeIf {
                SupportAssetMetadata.validUrl(it, "Faces")
            }
            SupportCatalogRecord(id, number, name, original,
                item["className"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item["rarity"]?.jsonPrimitive?.intOrNull ?: 0, face)
        }.distinctBy { it.id }
    }

    /** Join by internal identity, since regional collection numbers can differ. */
    fun merge(kind: SupportCatalogKind, canonical: List<SupportCatalogRecord>, localized: List<SupportCatalogRecord>,
              japanese: List<SupportCatalogRecord>): List<SupportCatalogEntry> {
        val jp = japanese.associateBy { it.id }
        val names = localized.associateBy { it.id }
        val all = (canonical + localized + japanese).groupBy { it.id }
        return canonical.map { record ->
            val id = record.id
            val variants = all.getValue(id)
            val display = names[id] ?: jp[id] ?: record
            SupportCatalogEntry(kind, id, jp[id]?.number ?: record.number, display.name, record.className,
                record.rarity, record.face ?: display.face,
                variants.flatMap { listOf(it.name, it.originalName) }.toSet())
        }
    }

    fun search(entries: List<SupportCatalogEntry>, query: String): List<SupportCatalogEntry> {
        val key = normalized(query)
        if (key.isEmpty()) throw PortraitImportException(PortraitImportError.Query)
        val numeric = key.all { it in '0'..'9' }
        val number = if (numeric) key.toIntOrNull()?.takeIf { it > 0 }
            ?: throw PortraitImportException(PortraitImportError.Number) else null
        return entries.mapNotNull { entry ->
            val aliases = entry.aliases.map(::normalized)
            val rank = when {
                number != null -> if (entry.number == number) 0 else return@mapNotNull null
                key in aliases -> 1
                aliases.any { it.startsWith(key) } -> 2
                aliases.any { key in it } -> 3
                else -> return@mapNotNull null
            }
            rank to entry
        }.sortedWith(compareBy<Pair<Int, SupportCatalogEntry>> { it.first }.thenBy { it.second.number }.thenBy { it.second.id })
            .map { it.second }
    }

    private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
}

/** Only dedicated face assets are accepted; card illustrations are never cropped speculatively. */
object SupportAssetMetadata {
    fun validUrl(url: String, family: String, region: String? = null): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host in setOf("static.atlasacademy.io", "assets.atlasacademy.io") &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("/(?:GameData/)?${region ?: "(?:JP|NA|KR|CN|TW)"}/$family/f_[A-Za-z0-9_]+\\.png").matches(uri.path)
    }.getOrDefault(false)

    fun parse(text: String, entry: SupportCatalogEntry, region: String): List<ServantPortrait> {
        if (region !in SupportImageCatalog.regions) invalid()
        val root = Json.parseToJsonElement(text) as? JsonObject ?: invalid()
        if (root["id"]?.jsonPrimitive?.intOrNull != entry.id ||
            root["type"]?.jsonPrimitive?.content !in (if (entry.kind == SupportCatalogKind.Servant) setOf("normal", "heroine") else setOf("servantEquip"))) invalid()
        val assets = root["extraAssets"] as? JsonObject ?: invalid()
        val portraits = mutableListOf<ServantPortrait>()
        val family = if (entry.kind == SupportCatalogKind.CE) "EquipFaces" else "Faces"
        fun collect(element: JsonElement?, path: List<String>) {
            if (path.size > 8 || portraits.size > 64) invalid()
            when (element) {
                null -> Unit
                is JsonObject -> element.forEach { (key, value) -> collect(value, path + key) }
                is JsonPrimitive -> {
                    val key = path.joinToString("_")
                    if (!element.isString || !validUrl(element.content, family, region) ||
                        !Regex("[A-Za-z0-9_-]{1,160}").matches(key)) invalid()
                    portraits += ServantPortrait(key, element.content)
                }
                else -> invalid()
            }
        }
        for (field in if (entry.kind == SupportCatalogKind.CE) listOf("equipFace") else listOf("faces", "facesChange"))
            collect(assets[field], listOf(field))
        return portraits.distinctBy { it.url }
    }
    private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
}
