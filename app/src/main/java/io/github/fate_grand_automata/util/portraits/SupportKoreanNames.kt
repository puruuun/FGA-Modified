package io.github.fate_grand_automata.util.portraits

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Bundled Korean search terms keyed by internal servant ID.
 * [names] apply only while a servant has no KR record; [aliases] remain searchable after KR release.
 */
data class SupportKoreanDictionary(
    val names: Map<Int, Set<String>> = emptyMap(),
    val aliases: Map<Int, Set<String>> = emptyMap(),
)

/** Parses the bundled Korean dictionary and owner-reviewed nickname aliases. */
object SupportKoreanNames {
    const val ASSET = "servant-names-ko.json"

    fun parse(text: String): SupportKoreanDictionary {
        val root = try { Json.parseToJsonElement(text) } catch (e: SerializationException) { invalid() }
        val servants = (root as? JsonObject)?.get("servants") as? JsonArray ?: invalid()
        val names = linkedMapOf<Int, MutableSet<String>>()
        val aliases = linkedMapOf<Int, MutableSet<String>>()
        for (value in servants) {
            val row = value as? JsonObject ?: invalid()
            val id = (row["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid()
            val ko = row["ko"] as? JsonArray ?: invalid()
            val aliasList = row["aliases"]?.let { it as? JsonArray ?: invalid() }
            if (id <= 0) continue
            collectStrings(ko, id, names)
            if (aliasList != null) collectStrings(aliasList, id, aliases)
        }
        return SupportKoreanDictionary(names, aliases)
    }

    private fun collectStrings(array: JsonArray, id: Int, target: MutableMap<Int, MutableSet<String>>) {
        for (element in array) {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            if (text.isNotBlank()) target.getOrPut(id) { linkedSetOf() }.add(text)
        }
    }

    private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
}
