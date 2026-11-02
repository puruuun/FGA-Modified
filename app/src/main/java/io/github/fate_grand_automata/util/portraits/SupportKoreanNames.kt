package io.github.fate_grand_automata.util.portraits

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Bundled Korean names of servants not yet released on KR, keyed by internal servant ID. */
object SupportKoreanNames {
    const val ASSET = "servant-names-ko.json"

    fun parse(text: String): Map<Int, Set<String>> {
        val root = try { Json.parseToJsonElement(text) } catch (e: SerializationException) { invalid() }
        val servants = (root as? JsonObject)?.get("servants") as? JsonArray ?: invalid()
        val names = linkedMapOf<Int, MutableSet<String>>()
        for (value in servants) {
            val row = value as? JsonObject ?: invalid()
            val id = (row["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: invalid()
            val ko = row["ko"] as? JsonArray ?: invalid()
            if (id <= 0) continue
            for (element in ko) {
                val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                if (name.isNotBlank()) names.getOrPut(id) { linkedSetOf() }.add(name)
            }
        }
        return names
    }

    private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
}
